package application.service;

import application.config.AppProperties;
import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.ParsedTestCase;
import application.dto.Dtos.StepEnrichment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StepPlanner {

    private final OpenRouterClient openRouter;
    private final AppProperties appProps;
    private final ObjectMapper mapper;

    public record Plan(
            String operation, String locator, String value, String method, String path,
            String bodyJson, Integer expectedStatus, String topicKey, String question, String reason,
            String locatorStrategy, String elementRole, String elementName, String locatorAlternatives
    ) {
        public boolean needsClarification() {
            return "needClarification".equalsIgnoreCase(operation);
        }

        public boolean isSkip() {
            return "skip".equalsIgnoreCase(operation);
        }

        public boolean isUiOperation() {
            String op = operation == null ? "" : operation.toLowerCase();
            return switch (op) {
                case "navigate", "click", "fill", "asserttext", "waitfortext", "assertbody" -> true;
                default -> false;
            };
        }
    }

    /**
     * @param domSnapshot компактный DOM-снапшот интерактивных элементов
     *                    (см. UiExecutor.snapshotForPlanning)
     */
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
            user.append("\nДля проверки тела используй assertBody, заголовка — assertHeader, ")
                    .append("валидности JSON — parseJson, статуса — assertStatus.\n\n");
        }

        if (domSnapshot != null && !domSnapshot.isBlank()) {
            user.append("DOM-СНАПШОТ страницы (только интерактивные элементы с атрибутами):\n");
            user.append(domSnapshot.length() > 30000 ? domSnapshot.substring(0, 30000) : domSnapshot)
                    .append("\n");
        }
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            user.append("\nAPI base URL: ").append(apiBaseUrl);
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
            user.append("\nТело последнего ответа API:\n")
                    .append(lastResponseBody.length() > 8000
                            ? lastResponseBody.substring(0, 8000) : lastResponseBody)
                    .append("\n");
        }

        String response = openRouter.complete(appProps.getPrompts().getPlanStep(), user.toString());
        String cleaned = response.replaceAll("^```json\\s*", "").replaceAll("```\\s*$", "").trim();
        try {
            Map<String, Object> map = mapper.readValue(cleaned, new TypeReference<>() {
            });
            return new Plan(
                    str(map.get("operation")), str(map.get("locator")), str(map.get("value")),
                    str(map.get("method")), str(map.get("path")), str(map.get("bodyJson")),
                    map.get("expectedStatus") instanceof Number n ? n.intValue() : null,
                    str(map.get("topicKey")), str(map.get("question")), str(map.get("reason")),
                    str(map.get("locatorStrategy")), str(map.get("elementRole")),
                    str(map.get("elementName")), str(map.get("locatorAlternatives"))
            );
        } catch (Exception e) {
            throw new application.exception.GenerationException(
                    "Модель вернула невалидный JSON: " + e.getMessage(), e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
