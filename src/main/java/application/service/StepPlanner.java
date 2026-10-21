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

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StepPlanner {

    // PATCH: коллизия assertBody устранена — для UI теперь assertPageText.
    private static final Set<String> UI_OPS = Set.of(
            "navigate", "click", "fill", "asserttext", "waitfortext", "assertpagetext");
    private static final Set<String> API_OPS = Set.of(
            "apicall", "assertbody", "assertheader", "assertstatus", "parsejson");

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
            return UI_OPS.contains(op);
        }

        // PATCH: валидация плана против типа кейса. Вызывается из planStep.
        public Plan validateAgainst(String caseType) {
            if (isSkip() || needsClarification()) return this;
            String op = operation == null ? "" : operation.toLowerCase();
            boolean apiCase = "API".equalsIgnoreCase(caseType);
            Set<String> allowed = apiCase ? API_OPS : UI_OPS;
            if (!allowed.contains(op)) {
                throw new GenerationException(
                        "План не соответствует типу кейса (" + caseType + "): operation=" + op
                                + ". Разрешены: " + allowed);
            }
            return this;
        }
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
            Plan plan = new Plan(
                    str(map.get("operation")), str(map.get("locator")), str(map.get("value")),
                    str(map.get("method")), str(map.get("path")), str(map.get("bodyJson")),
                    parseExpectedStatus(map.get("expectedStatus")),
                    str(map.get("topicKey")), str(map.get("question")), str(map.get("reason")),
                    str(map.get("locatorStrategy")), str(map.get("elementRole")),
                    str(map.get("elementName")), str(map.get("locatorAlternatives"))
            );
            // PATCH (P1): валидация плана прямо здесь — до executor.
            return plan.validateAgainst(tc.type());
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new GenerationException(
                    "Модель вернула невалидный JSON: " + e.getMessage(), e);
        }
    }

    // PATCH: принимаем и число, и строку из 3 цифр.
    private static Integer parseExpectedStatus(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && s.matches("\\d{3}")) return Integer.parseInt(s);
        throw new GenerationException(
                "expectedStatus должен быть числом из 3 цифр, получено: " + raw);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
