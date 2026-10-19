package application.service;

import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.ParsedTestCase;
import application.dto.Dtos.StepEnrichment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TestCaseMarkdownRenderer {

    /**
     * Обогащение пишется СТРОГО под шагом, к которому относится.
     * Многострочный detail рендерится с префиксом "> " на каждой строке,
     * чтобы markdown-блок оставался единым.
     */
    public String renderEnriched(ParsedTestCase tc, List<StepEnrichment> enrichments) {
        Map<Integer, StepEnrichment> byIndex = enrichments == null
                ? Map.of()
                : enrichments.stream().collect(Collectors.toMap(
                StepEnrichment::stepIndex, Function.identity(), (a, b) -> a));

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
            StepEnrichment e = byIndex.get(i);
            if (e != null && e.detail() != null && !e.detail().isBlank()) {
                String[] lines = e.detail().split("\\R", -1);
                for (String line : lines) {
                    sb.append("   > 🔍 ").append(line).append("\n");
                }
            }
        }
        sb.append("\n");

        if (tc.expectedResult() != null && !tc.expectedResult().isBlank()) {
            sb.append("## Ожидаемый результат\n\n").append(tc.expectedResult()).append("\n");
        }
        return sb.toString();
    }

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
