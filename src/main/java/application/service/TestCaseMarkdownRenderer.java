package application.service;

import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.ParsedTestCase;
import application.dto.Dtos.StepEnrichment;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Формирует Markdown-отчёты прогона: обогащённую версию тест-кейса и индекс результатов.
 * <p>
 * Место в конвейере: {@code RunController} вызывает {@link #renderEnriched} для каждого
 * кейса, результат сохраняет {@link EnrichedStorageService}; {@link #renderIndex} строит
 * индекс всех кейсов прогона, который сохраняется тем же способом.
 */
@Service
public class TestCaseMarkdownRenderer {

    /**
     * Строит Markdown обогащённого кейса: заголовок с полями, предусловия, шаги
     * с результатами выполнения и ожидаемый результат.
     * <p>
     * Записи {@code enrichments} группируются по {@code stepIndex} и выводятся под
     * соответствующим шагом с отступом. Записи, индекс которых не совпадает ни с одним
     * шагом, в отчёт не попадают. Раздел «Ожидаемый результат» выводится только
     * при непустом значении.
     *
     * @param tc          разобранный тест-кейс
     * @param enrichments записи выполнения; может быть {@code null}, тогда шаги выводятся без результатов
     * @return Markdown-текст кейса
     */
    public String renderEnriched(ParsedTestCase tc, List<StepEnrichment> enrichments) {
        Map<Integer, List<StepEnrichment>> byIndex = enrichments == null
                ? Map.of()
                : enrichments.stream().collect(Collectors.groupingBy(
                StepEnrichment::stepIndex, LinkedHashMap::new, Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(tc.id()).append(" — ").append(tc.title()).append("\n\n");
        sb.append("- **ID:** ").append(tc.id()).append("\n");
        sb.append("- **Тип:** ").append(tc.type()).append("\n");
        sb.append("- **Приоритет:** ").append(tc.priority()).append("\n");
        sb.append("- **Требование:** ").append(tc.requirementRef()).append("\n\n");

        if (!tc.preconditions().isEmpty()) {
            sb.append("## Предусловия\n\n");
            for (String p : tc.preconditions()) sb.append("- ").append(p).append("\n");
            sb.append("\n");
        }

        sb.append("## Шаги\n\n");
        for (int i = 0; i < tc.steps().size(); i++) {
            sb.append(i + 1).append(". ").append(tc.steps().get(i)).append("\n");
            List<StepEnrichment> list = byIndex.getOrDefault(i, List.of());
            for (StepEnrichment e : list) {
                renderEnrichment(sb, e, "   ");
            }
        }
        sb.append("\n");

        if (tc.expectedResult() != null && !tc.expectedResult().isBlank()) {
            sb.append("## Ожидаемый результат\n\n").append(tc.expectedResult()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Дописывает в отчёт одну запись выполнения в виде цитатного блока.
     * <p>
     * Маркер выбирается по действию: снимки ({@code snapshotApiValue}, {@code snapshotUiText})
     * получают метку SNAPSHOT, сравнения ({@code assertApiValueEqualsSnapshot},
     * {@code assertUiTextEqualsSnapshot}) получают метку COMPARE. Пропуск с «каскад»
     * в тексте отчёта получает метку каскадного пропуска, остальные записи получают
     * общий маркер. Curl, тело запроса, пример ответа и вложенные записи выводятся
     * отдельными блоками, если они заданы. Вложенные записи получают уровень цитирования глубже.
     *
     * @param sb     отчёт, в который дописывается запись
     * @param e      запись выполнения
     * @param indent отступ, которым начинается каждая строка блока
     */
    private void renderEnrichment(StringBuilder sb, StepEnrichment e, String indent) {
        String action = e.action() == null ? "" : e.action();
        boolean cascaded = "skip".equalsIgnoreCase(action)
                && e.detail() != null
                && e.detail().toLowerCase().contains("каскад");
        String marker;
        if ("snapshotapivalue".equalsIgnoreCase(action)
                || "snapshotuitext".equalsIgnoreCase(action)) {
            marker = "📸 SNAPSHOT";
        } else if ("assertapivalueequalssnapshot".equalsIgnoreCase(action)
                || "assertuitextequalssnapshot".equalsIgnoreCase(action)) {
            marker = "⚖️ COMPARE";
        } else if (cascaded) {
            marker = "⏭ SKIP (каскад)";
        } else {
            marker = "🔍";
        }

        sb.append(indent).append("> ").append(marker).append(" ").append(action).append("\n");
        if (e.requestCurl() != null && !e.requestCurl().isBlank()) {
            sb.append(indent).append("> ```bash\n");
            for (String line : e.requestCurl().split("\\R", -1)) {
                sb.append(indent).append("> ").append(line).append("\n");
            }
            sb.append(indent).append("> ```\n");
        }
        if (e.detail() != null && !e.detail().isBlank()) {
            for (String line : e.detail().split("\\R", -1)) {
                if (line.isEmpty()) {
                    sb.append(indent).append(">\n");
                } else {
                    sb.append(indent).append("> ").append(line).append("\n");
                }
            }
        }
        if (e.requestBody() != null && !e.requestBody().isBlank()) {
            sb.append(indent).append("> **Request body:**\n");
            sb.append(indent).append("> ```json\n");
            for (String line : e.requestBody().split("\\R", -1)) {
                sb.append(indent).append("> ").append(line).append("\n");
            }
            sb.append(indent).append("> ```\n");
        }
        if (e.responseSample() != null && !e.responseSample().isBlank()) {
            sb.append(indent).append("> **Response sample:**\n");
            sb.append(indent).append("> ```json\n");
            for (String line : e.responseSample().split("\\R", -1)) {
                sb.append(indent).append("> ").append(line).append("\n");
            }
            sb.append(indent).append("> ```\n");
        }
        if (e.subEnrichments() != null && !e.subEnrichments().isEmpty()) {
            for (StepEnrichment sub : e.subEnrichments()) {
                renderEnrichment(sb, sub, indent + "> ");
            }
        }
    }

    /**
     * Строит индекс прогона: по одной строке на кейс со ссылкой на его файл,
     * статусом и текстом ошибки в скобках, если он есть.
     *
     * @param outcomes итоги кейсов прогона; может быть {@code null} или пустым
     * @return Markdown-индекс; при пустом списке содержит строку «Пусто»
     */
    public String renderIndex(List<ExecutionOutcome> outcomes) {
        StringBuilder sb = new StringBuilder("# Обогащённые тест-кейсы — индекс\n\n");
        if (outcomes == null || outcomes.isEmpty()) {
            sb.append("_Пусто._\n");
            return sb.toString();
        }
        for (ExecutionOutcome o : outcomes) {
            sb.append("- [").append(o.testCaseId()).append("](./")
                    .append(o.filename()).append(") — ")
                    .append(o.status());
            if (o.failureMessage() != null && !o.failureMessage().isBlank()) {
                sb.append(" (").append(o.failureMessage()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
