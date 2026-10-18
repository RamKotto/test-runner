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

@Slf4j
@Service
public class OpenRouterClient {

    private final OpenRouterProperties props;
    private final RestClient http;

    public OpenRouterClient(OpenRouterProperties props, RestClient openRouterRestClient) {
        this.props = props;
        this.http = openRouterRestClient;
    }

    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userText) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userText)
        ));
        body.put("temperature", 0.0);
        body.put("max_tokens", props.getMaxOutputTokens());
        body.put("response_format", Map.of("type", "json_object"));

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

            // Fallback: некоторые модели кладут ответ в reasoning_content
            if (content == null || content.toString().isBlank()) {
                Object reasoning = message.get("reasoning_content");
                if (reasoning != null && !reasoning.toString().isBlank()) {
                    log.warn("content пуст, использую reasoning_content: {}",
                            truncate(reasoning.toString(), 200));
                    content = reasoning;
                }
            }

            // Fallback: tool_calls (если модель выбрала function calling)
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
