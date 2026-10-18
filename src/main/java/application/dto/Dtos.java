package application.dto;

import java.util.List;

public class Dtos {

    public record RunRequest(String sourceUrl, String startUrl, String apiBaseUrl) {
        public boolean hasSourceUrl() {
            return sourceUrl != null && !sourceUrl.isBlank();
        }
    }

    public record AnswerRequest(List<Answer> answers) {
        public record Answer(String question, String answer) {
        }
    }

    public record Clarification(String testCaseId, Integer stepIndex, String question, String reason) {
    }

    public record StepEnrichment(int stepIndex, String action, String detail) {
    }

    public record ExecutionOutcome(
            String testCaseId,
            String filename,
            String status,
            String failureMessage,
            List<StepEnrichment> enrichments
    ) {
    }

    public record RunResponse(
            String taskId,
            String status,
            List<ExecutionOutcome> outcomes,
            List<Clarification> clarifications,
            String enrichedIndexUrl
    ) {
    }

    public record ParsedTestCase(
            String id,
            String type,
            String title,
            String requirementRef,
            List<String> preconditions,
            List<String> steps,
            String expectedResult,
            String priority,
            String sourceFilename,
            String rawMarkdown,
            List<AnswerRequest.Answer> answers
    ) {
    }
}
