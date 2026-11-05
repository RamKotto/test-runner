package application.service;

import application.config.OpenRouterProperties;
import application.exception.GenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP-клиент OpenRouter для запросов к LLM, которые строят план шага.
 * <p>
 * Место в конвейере: {@link StepPlanner} формирует системный и пользовательский промпты
 * и получает ответ модели через {@link #complete}. Клиент выполняет один запрос
 * к {@code /chat/completions} с {@code response_format=json_object} и извлекает текст
 * ответа. Все ошибки транспорта и пустые ответы превращаются в {@link GenerationException}.
 */
@Slf4j
@Service
public class OpenRouterClient {

    /**
     * Настройки OpenRouter: модель и лимит выходных токенов.
     */
    private final OpenRouterProperties props;

    /**
     * REST-клиент, настроенный на базовый URL OpenRouter.
     */
    private final RestClient http;

    /**
     * Создаёт клиент.
     *
     * @param props                настройки OpenRouter
     * @param openRouterRestClient предварительно настроенный REST-клиент
     */
    public OpenRouterClient(OpenRouterProperties props, RestClient openRouterRestClient) {
        this.props = props;
        this.http = openRouterRestClient;
    }

    /**
     * Отправляет запрос к модели и возвращает текст ответа.
     * <p>
     * Запрос фиксирует недетерминизм модели: {@code temperature=0} (жадное декодирование),
     * {@code top_p=1} (без отсечения по nucleus) и {@code seed=42}. Провайдеры, которые поддерживают seed,
     * дают воспроизводимые ответы. Режим рассуждений задан как {@code effort=high}.
     * <p>
     * Текст ответа берётся из {@code choices[0].message.content}. Если он пуст, используются
     * по порядку {@code reasoning_content} и аргументы первого {@code tool_calls}.
     * Если и они пусты, запрос считается неудачным. Ошибка HTTP не логируется отдельно: её текст с телом
     * ответа, обрезанным до 800 символов, попадает в сообщение {@link GenerationException}.
     * Ответы без {@code choices}, {@code message} или content логируются с обрезкой до 2000 символов.
     *
     * @param systemPrompt системный промпт
     * @param userText     пользовательский текст
     * @return непустой текст ответа модели
     * @throws GenerationException HTTP-ошибка, отсутствие или пустота {@code choices}/{@code message}/content,
     *                             либо любая иная ошибка клиента
     */
    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userText) {
        // Map.of и List.of не принимают null и бросили бы NPE до входа в try: ответ был бы 500 вместо 502.
        if (systemPrompt == null || userText == null) {
            throw new GenerationException("Не заданы системный промпт или текст для запроса к OpenRouter");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userText)
        ));
        body.put("max_tokens", props.getMaxOutputTokens());
        body.put("response_format", Map.of("type", "json_object"));
        body.put("reasoning", Map.of("effort", "high"));

        body.put("temperature", 0);
        body.put("top_p", 1);
        body.put("seed", 42);

        try {
            Map<String, Object> response = http.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) throw new GenerationException("Пустой ответ от OpenRouter");

            var choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("OpenRouter не вернул choices. Полный ответ: {}",
                        truncate(response.toString(), 2000));
                throw new GenerationException("OpenRouter не вернул choices");
            }
            var message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) {
                log.error("OpenRouter вернул message=null. Полный ответ: {}",
                        truncate(response.toString(), 2000));
                throw new GenerationException("OpenRouter вернул пустой message");
            }

            Object content = message.get("content");

            if (content == null || content.toString().isBlank()) {
                Object reasoning = message.get("reasoning_content");
                if (reasoning != null && !reasoning.toString().isBlank()) {
                    log.warn("content пуст, использую reasoning_content: {}",
                            truncate(reasoning.toString(), 200));
                    content = reasoning;
                }
            }

            if (content == null || content.toString().isBlank()) {
                Object toolCalls = message.get("tool_calls");
                if (toolCalls instanceof List<?> list && !list.isEmpty()) {
                    Map<String, Object> firstCall = (Map<String, Object>) list.get(0);
                    Map<String, Object> function = (Map<String, Object>) firstCall.get("function");
                    Object args = function == null ? null : function.get("arguments");
                    if (args != null && !args.toString().isBlank()) {
                        log.warn("content пуст, использую tool_calls.arguments: {}",
                                truncate(args.toString(), 200));
                        content = args;
                    }
                }
            }

            if (content == null || content.toString().isBlank()) {
                log.error("Пустой content в ответе OpenRouter. Полный ответ: {}",
                        truncate(response.toString(), 2000));
                throw new GenerationException("Модель вернула пустой content. Проверьте max_tokens и формат промпта.");
            }
            return content.toString();
        } catch (GenerationException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            throw new GenerationException("OpenRouter HTTP " + e.getStatusCode() + ": "
                    + truncate(e.getResponseBodyAsString(), 800), e);
        } catch (Exception e) {
            log.error("Ошибка OpenRouter", e);
            throw new GenerationException("OpenRouter: " + e.getMessage(), e);
        }
    }

    /**
     * Обрезает строку до {@code max} символов и добавляет «…», если она длиннее.
     *
     * @param s   исходная строка; может быть {@code null}
     * @param max максимальная длина без многоточия
     * @return обрезанная строка либо пустая строка для {@code null}
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
