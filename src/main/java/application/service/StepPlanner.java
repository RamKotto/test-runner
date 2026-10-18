package application.service;

import application.config.AppProperties;
import application.dto.Dtos.ParsedTestCase;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
            String question,
            String reason
    ) {
        public boolean needsClarification() {
            return "needClarification".equalsIgnoreCase(operation);
        }
    }

    public Plan planStep(ParsedTestCase tc,
                         int stepIndex,
                         String stepText,
                         String uiHtmlSnapshot,
                         String apiBaseUrl) {
        StringBuilder user = new StringBuilder();
        user.append("Тип кейса: ").append(tc.type()).append("\n");
        user.append("Тест-кейс: ").append(tc.id()).append(" — ").append(tc.title()).append("\n");
        user.append("Ожидаемый результат всего кейса: ").append(tc.expectedResult()).append("\n\n");
        user.append("Шаг #").append(stepIndex + 1).append(": ").append(stepText).append("\n\n");
        if (uiHtmlSnapshot != null && !uiHtmlSnapshot.isBlank()) {
            user.append("HTML текущей страницы (обрезан):\n");
            user.append(uiHtmlSnapshot.length() > 12000
                    ? uiHtmlSnapshot.substring(0, 12000)
                    : uiHtmlSnapshot);
        }
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            user.append("\n\nAPI base URL: ").append(apiBaseUrl);
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
