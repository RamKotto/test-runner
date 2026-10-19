package application.service;

import application.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseRunnerService {

    private final StepPlanner planner;
    private final UiExecutor uiExecutor;
    private final ApiExecutor apiExecutor;

    public record RunnerOutcome(ExecutionOutcome outcome, List<Clarification> clarifications, boolean skipCase) {
    }

    public RunnerOutcome runCase(ParsedTestCase tc, String startUrl, String apiBaseUrl) {
        String effectiveApiBaseUrl = resolveApiBaseUrl(tc, apiBaseUrl);
        String effectiveStartUrl = resolveStartUrl(tc, startUrl);
        if (isApiType(tc)) return runApiCase(tc, effectiveApiBaseUrl);
        return runUiCase(tc, effectiveStartUrl);
    }

    private String resolveApiBaseUrl(ParsedTestCase tc, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        if (tc.answers() == null) return null;
        for (AnswerRequest.Answer a : tc.answers()) {
            String ans = a.answer();
            if (ans != null && (ans.startsWith("http://") || ans.startsWith("https://"))) return ans;
        }
        return fallback;
    }

    private String resolveStartUrl(ParsedTestCase tc, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        if (tc.answers() == null) return null;
        for (AnswerRequest.Answer a : tc.answers()) {
            String ans = a.answer();
            if (ans != null && (ans.startsWith("http://") || ans.startsWith("https://"))) return ans;
        }
        return fallback;
    }

    private boolean isSkippedInThisCase(ParsedTestCase tc, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || tc.answers() == null) return false;
        String needle = topicKey.trim().toLowerCase();
        for (AnswerRequest.Answer a : tc.answers()) {
            String q = a.question() == null ? "" : a.question().toLowerCase();
            String ans = a.answer() == null ? "" : a.answer().trim();
            if (q.contains(needle) && "SKIP".equalsIgnoreCase(ans)) return true;
        }
        return false;
    }

    private String findAnswerInThisCase(ParsedTestCase tc, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || tc.answers() == null) return null;
        String needle = topicKey.trim().toLowerCase();
        for (AnswerRequest.Answer a : tc.answers()) {
            String q = a.question() == null ? "" : a.question().toLowerCase();
            if (q.contains(needle)) return a.answer();
        }
        return null;
    }

    private RunnerOutcome runUiCase(ParsedTestCase tc, String startUrl) {
        List<StepEnrichment> enrichments = new ArrayList<>();
        String failure = null;

        try (UiExecutor.UiSession session = uiExecutor.newSession(startUrl)) {
            for (int i = 0; i < tc.steps().size(); i++) {
                String step = tc.steps().get(i);
                String text = uiExecutor.snapshotHtml(session);
                StepPlanner.Plan plan = planner.planStep(tc, i, step, text, null, enrichments, null, null, null);

                if (plan.isSkip()) return skipped(tc, enrichments, i, plan.reason());
                if (plan.needsClarification()) {
                    String topicKey = normalizeTopic(plan.topicKey(), plan.question(), plan.reason());
                    if (isSkippedInThisCase(tc, topicKey) || findAnswerInThisCase(tc, topicKey) != null) {
                        return skipped(tc, enrichments, i, "ответ по теме был, но шаг не выполнен");
                    }
                    Clarification c = new Clarification(tc.id(), i, withTopic(plan.question(), topicKey), plan.reason());
                    return new RunnerOutcome(new ExecutionOutcome(tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION", "Прервано на шаге " + (i + 1), enrichments), List.of(c), false);
                }

                try {
                    enrichments.add(uiExecutor.executeStep(session, i, plan, uiExecutor.currentUrl(session)));
                } catch (UiExecutor.LocatorNotResolved ex) {
                    String topicKey = normalizeTopic(plan.topicKey(), null, ex.getMessage());
                    if (isSkippedInThisCase(tc, topicKey) || findAnswerInThisCase(tc, topicKey) != null) {
                        return skipped(tc, enrichments, i, ex.getMessage());
                    }
                    Clarification c = new Clarification(tc.id(), i, withTopic("Шаг " + (i + 1) + " («" + step + "»): " + ex.getMessage(), topicKey), "Ошибка UI");
                    return new RunnerOutcome(new ExecutionOutcome(tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION", "Прервано на шаге " + (i + 1), enrichments), List.of(c), false);
                }
            }
        } catch (Exception e) {
            failure = e.getMessage();
        }
        return new RunnerOutcome(new ExecutionOutcome(tc.id(), tc.sourceFilename(), failure == null ? "PASSED" : "FAILED", failure, enrichments), List.of(), false);
    }

    private RunnerOutcome runApiCase(ParsedTestCase tc, String apiBaseUrl) {
        List<StepEnrichment> enrichments = new ArrayList<>();
        String lastBody = null;
        Map<String, String> lastHeaders = new LinkedHashMap<>();
        Integer lastStatus = null;

        for (int i = 0; i < tc.steps().size(); i++) {
            String step = tc.steps().get(i);
            StepPlanner.Plan plan = planner.planStep(tc, i, step, null, apiBaseUrl, enrichments, lastBody, lastHeaders, lastStatus);

            if (plan.isSkip()) return skipped(tc, enrichments, i, plan.reason());
            if (plan.needsClarification()) {
                String topicKey = normalizeTopic(plan.topicKey(), plan.question(), plan.reason());
                if (isSkippedInThisCase(tc, topicKey) || findAnswerInThisCase(tc, topicKey) != null) {
                    return skipped(tc, enrichments, i, "ответ по теме был, но шаг не выполнен");
                }
                Clarification c = new Clarification(tc.id(), i, withTopic(plan.question(), topicKey), plan.reason());
                return new RunnerOutcome(new ExecutionOutcome(tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION", "Прервано на шаге " + (i + 1), enrichments), List.of(c), false);
            }

            try {
                ApiExecutor.ApiStepResult result = apiExecutor.executeStep(i, plan, apiBaseUrl, Map.of(), Map.of(), lastBody, lastHeaders, lastStatus);
                enrichments.add(result.enrichment());
                if (result.responseBody() != null) lastBody = result.responseBody();
                if (result.responseHeaders() != null && !result.responseHeaders().isEmpty())
                    lastHeaders = result.responseHeaders();
                if (result.responseStatusCode() != null) lastStatus = result.responseStatusCode();
            } catch (UiExecutor.LocatorNotResolved ex) {
                String topicKey = normalizeTopic(plan.topicKey(), null, ex.getMessage());
                if (isSkippedInThisCase(tc, topicKey) || findAnswerInThisCase(tc, topicKey) != null) {
                    return skipped(tc, enrichments, i, ex.getMessage());
                }
                Clarification c = new Clarification(tc.id(), i, withTopic("Шаг " + (i + 1) + " («" + step + "»): " + ex.getMessage(), topicKey), "Ошибка API");
                return new RunnerOutcome(new ExecutionOutcome(tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION", "Прервано на шаге " + (i + 1), enrichments), List.of(c), false);
            }
        }
        return new RunnerOutcome(new ExecutionOutcome(tc.id(), tc.sourceFilename(), "PASSED", null, enrichments), List.of(), false);
    }

    private RunnerOutcome skipped(ParsedTestCase tc, List<StepEnrichment> enrichments, int stepIndex, String reason) {
        String msg = "Пропущено по запросу пользователя (SKIP)" + (reason == null ? "" : ": " + reason);
        return new RunnerOutcome(new ExecutionOutcome(tc.id(), tc.sourceFilename(), "SKIPPED", msg, enrichments), List.of(), false);
    }

    private String normalizeTopic(String topicKey, String question, String reason) {
        String base = topicKey != null ? topicKey : (question != null ? question : reason);
        if (base == null) base = "unknown";
        String normalized = base.trim().toLowerCase().replaceAll("\\s+", " ");
        return normalized.length() > 40 ? normalized.substring(0, 40) : normalized;
    }

    private String withTopic(String question, String topicKey) {
        if (topicKey == null || topicKey.isBlank()) return question;
        return "[" + topicKey + "] " + (question == null ? "" : question);
    }

    private boolean isApiType(ParsedTestCase tc) {
        if (tc.type() == null) return false;
        String t = tc.type().trim().toUpperCase();
        if (t.equals("API")) return true;
        if (t.equals("NEGATIVE")) {
            String all = String.join(" ", tc.steps()).toLowerCase();
            return all.contains("get-запрос") || all.contains("post-запрос") || all.contains("http://") || all.contains("https://") || all.contains("http-код") || all.contains("api.");
        }
        return false;
    }
}
