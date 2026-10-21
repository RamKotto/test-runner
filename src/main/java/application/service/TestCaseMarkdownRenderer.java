package application.service;

import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.ParsedTestCase;
import application.dto.Dtos.StepEnrichment;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TestCaseMarkdownRenderer {

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

    private void renderEnrichment(StringBuilder sb, StepEnrichment e, String indent) {
        sb.append(indent).append("> 🔍 ").append(e.action()).append("\n");
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
