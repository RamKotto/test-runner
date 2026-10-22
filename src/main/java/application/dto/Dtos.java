package application.dto;

import java.util.List;

public class Dtos {

    public record RunRequest(String sourceUrl, String startUrl, String apiBaseUrl) {
        public boolean hasSourceUrl() {
            return sourceUrl != null && !sourceUrl.isBlank();
        }
    }

    public record AnswerRequest(List<Answer> answers) {
        public record Answer(String question, String answer, String topicKey) {
        }
    }

    /**
     * FIX 6: добавлен topicKey — чтобы клиент мог возвращать его явно,
     * а сервер матчил ответы по точному topicKey, а не по подстроке в question.
     */
    public record Clarification(
            String testCaseId,
            Integer stepIndex,
            String question,
            String reason,
            String category,
            String topicKey
    ) {
        public Clarification(String testCaseId, Integer stepIndex, String question,
                             String reason, String category) {
            this(testCaseId, stepIndex, question, reason, category, null);
        }
    }

    public record ElementInfo(
            int index,
            String locator,
            String xpath,
            String tag,
            String testidOrId,
            String role,
            String ariaLabel,
            String type,
            String text,
            Integer count,
            List<String> alternativesCss,
            List<String> alternativesXpath
    ) {
    }

    public record StepEnrichment(
            int stepIndex,
            String action,
            String detail,
            List<ElementInfo> elements,
            String requestCurl,
            String requestBody,
            String responseSample,
            List<StepEnrichment> subEnrichments
    ) {
        public StepEnrichment(int stepIndex, String action, String detail) {
            this(stepIndex, action, detail, List.of(), null, null, null, List.of());
        }

        public static StepEnrichment ui(int stepIndex, String action, String detail,
                                        List<ElementInfo> elements) {
            return new StepEnrichment(stepIndex, action, detail, elements,
                    null, null, null, List.of());
        }

        public static StepEnrichment api(int stepIndex, String action, String detail,
                                         String requestCurl, String requestBody,
                                         String responseSample) {
            return new StepEnrichment(stepIndex, action, detail, List.of(),
                    requestCurl, requestBody, responseSample, List.of());
        }

        public static StepEnrichment apiWithSub(int stepIndex, String action, String detail,
                                                String requestCurl, String requestBody,
                                                String responseSample,
                                                List<StepEnrichment> subs) {
            return new StepEnrichment(stepIndex, action, detail, List.of(),
                    requestCurl, requestBody, responseSample,
                    subs == null ? List.of() : subs);
        }
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
