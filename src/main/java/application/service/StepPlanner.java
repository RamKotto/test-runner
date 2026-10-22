package application.service;

import application.config.AppProperties;
import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.ParsedTestCase;
import application.dto.Dtos.StepEnrichment;
import application.exception.GenerationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StepPlanner {

    public static final Set<String> UI_OPS = Set.of(
            "navigate", "click", "fill", "asserttext", "waitfortext", "assertpagetext");

    public static final Set<String> API_OPS = Set.of(
            "apicall", "assertbody", "assertheader", "assertstatus", "parsejson",
            "assertbodyabsent", "assertheaderabsent");

    private final OpenRouterClient openRouter;
    private final AppProperties appProps;
    private final ObjectMapper mapper;

    public record ElementTarget(
            String locator,
            String locatorStrategy,
            String elementRole,
            String elementName,
            List<String> alternatives
    ) {
    }

    public record Plan(
            String operation, String locator, String value, String method, String path,
            String bodyJson, Integer expectedStatus, String topicKey, String question, String reason,
            String locatorStrategy, String elementRole, String elementName, String locatorAlternatives,
            List<ElementTarget> elements,
            String requestHeaders
    ) {
        public boolean needsClarification() {
            return "needClarification".equalsIgnoreCase(operation);
        }

        public boolean isSkip() {
            return "skip".equalsIgnoreCase(operation);
        }

        public boolean isUiOperation() {
            String op = operation == null ? "" : operation.toLowerCase();
            return UI_OPS.contains(op);
        }

        public boolean isApiOperation() {
            String op = operation == null ? "" : operation.toLowerCase();
            return API_OPS.contains(op);
        }

        public Plan validateAgainst(String caseType) {
            if (isSkip() || needsClarification()) return this;
            String op = operation == null ? "" : operation.toLowerCase();
            String t = caseType == null ? "" : caseType.trim().toUpperCase();

            switch (t) {
                case "UI" -> {
                    if (!UI_OPS.contains(op)) {
                        throw new GenerationException(
                                "План не соответствует типу кейса (UI): operation=" + op
                                        + ". Разрешены: " + UI_OPS);
                    }
                }
                case "API" -> {
                    if (!API_OPS.contains(op)) {
                        throw new GenerationException(
                                "План не соответствует типу кейса (API): operation=" + op
                                        + ". Разрешены: " + API_OPS);
                    }
                }
                case "E2E", "NEGATIVE", "SECURITY" -> {
                    if (!UI_OPS.contains(op) && !API_OPS.contains(op)) {
                        throw new GenerationException(
                                "План не соответствует типу кейса (" + t + "): operation=" + op
                                        + ". Разрешены: " + UI_OPS + " ∪ " + API_OPS);
                    }
                }
                default -> log.warn("validateAgainst: неизвестный тип кейса '{}', пропускаю", t);
            }
            return this;
        }

        public List<ElementTarget> effectiveElements() {
            if (elements != null && !elements.isEmpty()) return elements;
            if (locator != null && !locator.isBlank()) {
                return List.of(new ElementTarget(
                        locator, locatorStrategy, elementRole, elementName,
                        splitAlternativesInternal(locatorAlternatives)));
            }
            return List.of();
        }
    }

    private static List<String> splitAlternativesInternal(String alts) {
        if (alts == null || alts.isBlank() || "null".equalsIgnoreCase(alts)) return List.of();
        String s = alts.trim();
        if (s.startsWith("[")) {
            try {
                var m = new com.fasterxml.jackson.databind.ObjectMapper();
                var arr = m.readTree(s);
                if (arr.isArray()) {
                    List<String> out = new ArrayList<>();
                    for (var n : arr) out.add(n.asText());
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    public Plan planStep(ParsedTestCase tc, int stepIndex, String stepText, String domSnapshot,
                         String apiBaseUrl, List<StepEnrichment> previousEnrichments,
                         String lastResponseBody, Map<String, String> lastResponseHeaders,
                         Integer lastStatusCode) {
        StringBuilder user = new StringBuilder();
        user.append("Тип кейса: ").append(tc.type()).append("\n");
        user.append("Тест-кейс: ").append(tc.id()).append(" — ").append(tc.title()).append("\n");
        user.append("Ожидаемый результат всего кейса: ").append(tc.expectedResult()).append("\n\n");
        user.append("Шаг #").append(stepIndex + 1).append(": ").append(stepText).append("\n\n");

        if (tc.answers() != null && !tc.answers().isEmpty()) {
            user.append("Ранее пользователь ответил на уточнения в этом кейсе:\n");
            for (AnswerRequest.Answer a : tc.answers()) {
                user.append("- Вопрос: ").append(a.question())
                        .append("\n  Ответ: ").append(a.answer()).append("\n");
            }
            user.append("\nИспользуй эти ответы. Если ответ равен SKIP — верни operation=\"skip\".\n\n");
        }

        if (previousEnrichments != null && !previousEnrichments.isEmpty()) {
            user.append("Уже выполненные шаги этого кейса:\n");
            for (StepEnrichment e : previousEnrichments) {
                user.append("- Шаг ").append(e.stepIndex() + 1)
                        .append(": ").append(e.action()).append(" → ")
                        .append(e.detail()).append("\n");
            }
            user.append("\nДля проверки тела используй assertBody, отсутствия подстроки — ")
                    .append("assertBodyAbsent, заголовка — assertHeader, отсутствия заголовка — ")
                    .append("assertHeaderAbsent, валидности JSON — parseJson, статуса — assertStatus.\n\n");
        }

        if (domSnapshot != null && !domSnapshot.isBlank()) {
            user.append("DOM-СНАПШОТ страницы (полный, только интерактивные элементы с атрибутами):\n");
            user.append(domSnapshot).append("\n");
        }

        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            user.append("\nAPI base URL НЕ ЗАДАН. ");
            user.append("Если шаг требует HTTP-запроса, верни needClarification ")
                    .append("с topicKey=\"api_base_url\" и question=\"Укажите базовый URL API ")
                    .append("(например, https://api.open-meteo.com)\", ")
                    .append("reason=\"apiBaseUrl не передан в RunRequest\". ")
                    .append("НЕ возвращай относительный path без base — это приведёт к ошибке.\n");
        } else {
            user.append("\nAPI base URL: ").append(apiBaseUrl);
            user.append("\nПравила склейки path с base URL:");
            user.append("\n  • Если path относительный (начинается с /), он будет склеен с этим base URL.");
            user.append("\n  • Если шаг требует ДРУГОЙ хост (например, api.open-meteo.com вместо ")
                    .append(apiBaseUrl).append("), верни path с полным URL http(s)://...");
            user.append("\n  • Нельзя возвращать относительный path, если apiBaseUrl не подходит ")
                    .append("по хосту — либо полный URL в path, либо needClarification(api_base_url).\n");
        }

        if (lastStatusCode != null)
            user.append("\nHTTP статус последнего ответа: ").append(lastStatusCode).append("\n");
        if (lastResponseHeaders != null && !lastResponseHeaders.isEmpty()) {
            user.append("\nЗаголовки последнего ответа API:\n");
            for (Map.Entry<String, String> h : lastResponseHeaders.entrySet()) {
                user.append("- ").append(h.getKey()).append(": ").append(h.getValue()).append("\n");
            }
        }
        if (lastResponseBody != null && !lastResponseBody.isBlank()) {
            user.append("\nТело последнего ответа API (полное):\n")
                    .append(lastResponseBody).append("\n");
        }

        String userText = user.toString();
        int maxInputChars = appProps.getPlanner().getMaxInputChars();
        if (maxInputChars > 0 && userText.length() > maxInputChars) {
            throw new PlannerInputTooLargeException(
                    "Вход планера слишком большой: " + userText.length()
                            + " символов (лимит " + maxInputChars + "). "
                            + "Сузьте startUrl (конкретная страница/раздел) или укажите "
                            + "селектор контейнера в ответе на уточнение.");
        }

        String response = openRouter.complete(appProps.getPrompts().getPlanStep(), userText);
        String cleaned = response.replaceAll("^```json\\s*", "").replaceAll("```\\s*$", "").trim();
        try {
            Map<String, Object> map = mapper.readValue(cleaned, new TypeReference<>() {
            });
            List<ElementTarget> elements = parseElements(map.get("elements"));
            Plan plan = new Plan(
                    str(map.get("operation")), str(map.get("locator")), str(map.get("value")),
                    str(map.get("method")), str(map.get("path")), str(map.get("bodyJson")),
                    parseExpectedStatus(map.get("expectedStatus")),
                    str(map.get("topicKey")), str(map.get("question")), str(map.get("reason")),
                    str(map.get("locatorStrategy")), str(map.get("elementRole")),
                    str(map.get("elementName")), str(map.get("locatorAlternatives")),
                    elements, str(map.get("requestHeaders"))
            );
            return plan.validateAgainst(tc.type());
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new GenerationException("Модель вернула невалидный JSON: " + e.getMessage(), e);
        }
    }

    private List<ElementTarget> parseElements(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) return List.of();
        List<ElementTarget> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object loc = m.get("locator");
            if (loc == null || loc.toString().isBlank()) continue;
            List<String> alts = new ArrayList<>();
            Object altRaw = m.get("alternatives");
            if (altRaw instanceof List<?> al) {
                for (Object a : al) if (a != null) alts.add(a.toString());
            } else if (altRaw != null) {
                alts = splitAlternativesInternal(altRaw.toString());
            }
            out.add(new ElementTarget(
                    loc.toString(),
                    str(m.get("locatorStrategy")),
                    str(m.get("elementRole")),
                    str(m.get("elementName")),
                    alts
            ));
        }
        return out;
    }

    private static Integer parseExpectedStatus(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && s.matches("\\d{3}")) return Integer.parseInt(s);
        throw new GenerationException("expectedStatus должен быть числом из 3 цифр, получено: " + raw);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Сигнал о переполнении входа планера — конвертируется HybridCaseRunner
     * в needClarification с topicKey="context_overflow".
     */
    public static final class PlannerInputTooLargeException extends RuntimeException {
        public PlannerInputTooLargeException(String message) {
            super(message);
        }
    }
}
