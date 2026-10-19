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
            String operation,
            String locator,
            String value,
            String method,
            String path,
            String bodyJson,
            Integer expectedStatus,
            String topicKey,
            String question,
            String reason
    ) {
        public boolean needsClarification() {
            return "needClarification".equalsIgnoreCase(operation);
        }

        public boolean isSkip() {
            return "skip".equalsIgnoreCase(operation);
        }
    }

    public Plan planStep(ParsedTestCase tc,
                         int stepIndex,
                         String stepText,
                         String uiHtmlSnapshot,
                         String apiBaseUrl,
                         List<StepEnrichment> previousEnrichments,
                         String lastResponseBody) {
        StringBuilder user = new StringBuilder();
        user.append("Тип кейса: ").append(tc.type()).append("\n");
        user.append("Тест-кейс: ").append(tc.id()).append(" — ").append(tc.title()).append("\n");
        user.append("Ожидаемый результат всего кейса: ").append(tc.expectedResult()).append("\n\n");
        user.append("Шаг #").append(stepIndex + 1).append(": ").append(stepText).append("\n\n");

        // Ответы пользователя (накопленные в этом кейсе)
        if (tc.answers() != null && !tc.answers().isEmpty()) {
            user.append("Ранее пользователь ответил на уточнения в этом кейсе:\n");
            for (AnswerRequest.Answer a : tc.answers()) {
                user.append("- Вопрос: ").append(a.question()).append("\n");
                user.append("  Ответ: ").append(a.answer()).append("\n");
            }
            user.append("\nИспользуй эти ответы. Не задавай повторно те же вопросы. ");
            user.append("Если ответ равен SKIP — верни operation=\"skip\".\n\n");
        }

        // Уже выполненные шаги этого кейса
        if (previousEnrichments != null && !previousEnrichments.isEmpty()) {
            user.append("Уже выполненные шаги этого кейса:\n");
            for (StepEnrichment e : previousEnrichments) {
                user.append("- Шаг ").append(e.stepIndex() + 1).append(": ")
                        .append(e.action()).append(" → ").append(e.detail()).append("\n");
            }
            user.append("\nУчитывай это: если нужный API-запрос уже выполнен — используй его результат, ");
            user.append("не повторяй запрос. Для проверки тела используй operation=\"assertBody\".\n\n");
        }

        if (uiHtmlSnapshot != null && !uiHtmlSnapshot.isBlank()) {
            user.append("HTML текущей страницы (обрезан):\n");
            user.append(uiHtmlSnapshot.length() > 12000
                    ? uiHtmlSnapshot.substring(0, 12000)
                    : uiHtmlSnapshot);
            user.append("\n");
        }
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            user.append("\nAPI base URL: ").append(apiBaseUrl);
        }
        if (lastResponseBody != null && !lastResponseBody.isBlank()) {
            user.append("\nТело последнего ответа API (обрезано):\n");
            user.append(lastResponseBody.length() > 8000
                    ? lastResponseBody.substring(0, 8000)
                    : lastResponseBody);
            user.append("\n");
        }

        String response = openRouter.complete(appProps.getPrompts().getPlanStep(), user.toString());
        String cleaned = response.replaceAll("^```json\\s*", "")
                .replaceAll("```\\s*$", "").trim();
        try {
            Map<String, Object> map = mapper.readValue(cleaned, new TypeReference<>() {
            });
            return new Plan(
                    str(map.get("operation")),
                    str(map.get("locator")),
                    str(map.get("value")),
                    str(map.get("method")),
                    str(map.get("path")),
                    str(map.get("bodyJson")),
                    map.get("expectedStatus") instanceof Number n ? n.intValue() : null,
                    str(map.get("topicKey")),
                    str(map.get("question")),
                    str(map.get("reason"))
            );
        } catch (Exception e) {
            log.error("Не удалось распарсить план шага: {}", cleaned, e);
            throw new application.exception.GenerationException(
                    "Модель вернула невалидный JSON для шага: " + e.getMessage(), e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
