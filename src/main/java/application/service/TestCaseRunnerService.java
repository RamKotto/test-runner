package application.service;

import application.dto.Dtos.*;
import application.exception.AssertionFailure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseRunnerService {

    // PATCH: жёсткие наборы допустимых операций по типу кейса.
    private static final Set<String> UI_OPS = Set.of(
            "navigate", "click", "fill", "asserttext", "waitfortext", "assertpagetext");
    private static final Set<String> API_OPS = Set.of(
            "apicall", "assertbody", "assertheader", "assertstatus", "parsejson");

    private final StepPlanner planner;
    private final UiExecutor uiExecutor;
    private final ApiExecutor apiExecutor;

    public record RunnerOutcome(ExecutionOutcome outcome,
                                List<Clarification> clarifications,
                                boolean skipCase) {
    }

    public RunnerOutcome runCase(ParsedTestCase tc, String startUrl, String apiBaseUrl) {
        // PATCH: INVALID-кейсы (без типа) не гоняем.
        if ("INVALID".equalsIgnoreCase(tc.type())) {
            return new RunnerOutcome(new ExecutionOutcome(
                    tc.id(), tc.sourceFilename(), "SKIPPED",
                    "Не указан тип кейса (UI/API)", List.of()),
                    List.of(), true);
        }

        String effectiveApiBaseUrl = resolveApiBaseUrl(tc, apiBaseUrl);
        String effectiveStartUrl = resolveStartUrl(tc, startUrl);
        if (isApiType(tc)) return runApiCase(tc, effectiveApiBaseUrl);
        return runUiCase(tc, effectiveStartUrl);
    }

    // PATCH: без эвристик по подстрокам. Только явный контракт.
    private boolean isApiType(ParsedTestCase tc) {
        if (tc.type() == null) return false;
        String t = tc.type().trim().toUpperCase();
        if (!t.equals("UI") && !t.equals("API")) {
            log.warn("TC {}: неизвестный type='{}', трактую как UI", tc.id(), tc.type());
            return false;
        }
        return t.equals("API");
    }

    // PATCH: матчим ответ по topicKey, а не «любой URL».
    private String resolveApiBaseUrl(ParsedTestCase tc, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        if (tc.answers() == null) return null;
        for (AnswerRequest.Answer a : tc.answers()) {
            if (a.topicKey() != null && a.topicKey().equalsIgnoreCase("api_base_url")) {
                return a.answer();
            }
        }
        return fallback;
    }

    private String resolveStartUrl(ParsedTestCase tc, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        if (tc.answers() == null) return null;
        for (AnswerRequest.Answer a : tc.answers()) {
            if (a.topicKey() != null && a.topicKey().equalsIgnoreCase("start_url")) {
                return a.answer();
            }
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

        try (UiExecutor.UiSession session = uiExecutor.newSession(startUrl)) {
            for (int i = 0; i < tc.steps().size(); i++) {
                String step = tc.steps().get(i);
                String dom = uiExecutor.snapshotForPlanning(session);
                StepPlanner.Plan plan = planner.planStep(
                        tc, i, step, dom, null, enrichments, null, null, null);

                // PATCH (P0): план обязан соответствовать типу кейса.
                // Никаких NEEDS_CLARIFICATION для этой ситуации — это FAILED.
                if (plan.isSkip()) return skipped(tc, enrichments, i, plan.reason());
                if (plan.needsClarification()) {
                    String topicKey = normalizeTopic(plan.topicKey(), plan.reason());
                    if (isSkippedInThisCase(tc, topicKey) || findAnswerInThisCase(tc, topicKey) != null) {
                        return skipped(tc, enrichments, i, "ответ по теме был, но шаг не выполнен");
                    }
                    Clarification c = new Clarification(tc.id(), i,
                            withTopic(plan.question(), topicKey), plan.reason(),
                            "DATA_MISSING");
                    return new RunnerOutcome(new ExecutionOutcome(
                            tc.id(), tc.sourceFilename(),
                            "NEEDS_CLARIFICATION", "Прервано на шаге " + (i + 1), enrichments),
                            List.of(c), false);
                }

                String op = plan.operation() == null ? "" : plan.operation().toLowerCase();
                if (API_OPS.contains(op)) {
                    return new RunnerOutcome(new ExecutionOutcome(
                            tc.id(), tc.sourceFilename(), "FAILED",
                            "Шаг " + (i + 1) + ": UI-кейс требует API-операцию '" + op
                                    + "'. Переклассифицируйте кейс (type=API) или исправьте шаг.",
                            enrichments), List.of(), false);
                }
                if (!UI_OPS.contains(op)) {
                    return new RunnerOutcome(new ExecutionOutcome(
                            tc.id(), tc.sourceFilename(), "FAILED",
                            "Шаг " + (i + 1) + ": неизвестная UI-операция '" + op + "'",
                            enrichments), List.of(), false);
                }

                uiExecutor.dumpHtml(session, tc.id(), i);

                try {
                    enrichments.add(uiExecutor.executeStep(
                            session, i, plan, uiExecutor.currentUrl(session)));
                } catch (AssertionFailure ex) {
                    return new RunnerOutcome(new ExecutionOutcome(
                            tc.id(), tc.sourceFilename(), "FAILED",
                            "Шаг " + (i + 1) + ": " + ex.getMessage(), enrichments),
                            List.of(), false);
                } catch (UiExecutor.UnsupportedUiOperation ex) {
                    // PATCH: это дефект планирования, а не «не нашли локатор».
                    return new RunnerOutcome(new ExecutionOutcome(
                            tc.id(), tc.sourceFilename(), "FAILED",
                            "Шаг " + (i + 1) + ": " + ex.getMessage(), enrichments),
                            List.of(), false);
                } catch (UiExecutor.LocatorNotResolved ex) {
                    String topicKey = normalizeTopic(plan.topicKey(), ex.getMessage());
                    if (isSkippedInThisCase(tc, topicKey) || findAnswerInThisCase(tc, topicKey) != null) {
                        return skipped(tc, enrichments, i, ex.getMessage());
                    }
                    Clarification c = new Clarification(tc.id(), i,
                            withTopic("Шаг " + (i + 1) + " («" + step + "»): " + ex.getMessage(), topicKey),
                            "Ошибка планирования UI",
                            "LOCATOR_NOT_FOUND");
                    return new RunnerOutcome(new ExecutionOutcome(
                            tc.id(), tc.sourceFilename(),
                            "NEEDS_CLARIFICATION", "Прервано на шаге " + (i + 1), enrichments),
                            List.of(c), false);
                }
            }
            return new RunnerOutcome(new ExecutionOutcome(
                    tc.id(), tc.sourceFilename(), "PASSED", null, enrichments),
                    List.of(), false);
        } catch (Exception e) {
            log.error("UI-кейс {} упал с необработанной ошибкой", tc.id(), e);
            return new RunnerOutcome(new ExecutionOutcome(
                    tc.id(), tc.sourceFilename(), "FAILED",
                    "Необработанная ошибка UI: " + e.getMessage(), enrichments),
                    List.of(), false);
        }
    }

    private RunnerOutcome runApiCase(ParsedTestCase tc, String apiBaseUrl) {
        List<StepEnrichment> enrichments = new ArrayList<>();
        String lastBody = null;
        Map<String, String> lastHeaders = new LinkedHashMap<>();
        Integer lastStatus = null;

        for (int i = 0; i < tc.steps().size(); i++) {
            String step = tc.steps().get(i);
            StepPlanner.Plan plan = planner.planStep(
                    tc, i, step, null, apiBaseUrl, enrichments, lastBody, lastHeaders, lastStatus);

            if (plan.isSkip()) return skipped(tc, enrichments, i, plan.reason());
            if (plan.needsClarification()) {
                String topicKey = normalizeTopic(plan.topicKey(), plan.reason());
                if (isSkippedInThisCase(tc, topicKey) || findAnswerInThisCase(tc, topicKey) != null) {
                    return skipped(tc, enrichments, i, "ответ по теме был, но шаг не выполнен");
                }
                Clarification c = new Clarification(tc.id(), i,
                        withTopic(plan.question(), topicKey), plan.reason(),
                        "DATA_MISSING");
                return new RunnerOutcome(new ExecutionOutcome(
                        tc.id(), tc.sourceFilename(),
                        "NEEDS_CLARIFICATION", "Прервано на шаге " + (i + 1), enrichments),
                        List.of(c), false);
            }

            // PATCH (P0): API-кейс не должен содержать UI-операций.
            String op = plan.operation() == null ? "" : plan.operation().toLowerCase();
            if (UI_OPS.contains(op)) {
                return new RunnerOutcome(new ExecutionOutcome(
                        tc.id(), tc.sourceFilename(), "FAILED",
                        "Шаг " + (i + 1) + ": API-кейс требует UI-операцию '" + op + "'",
                        enrichments), List.of(), false);
            }
            if (!API_OPS.contains(op)) {
                return new RunnerOutcome(new ExecutionOutcome(
                        tc.id(), tc.sourceFilename(), "FAILED",
                        "Шаг " + (i + 1) + ": неизвестная API-операция '" + op + "'",
                        enrichments), List.of(), false);
            }

            try {
                ApiExecutor.ApiStepResult result = apiExecutor.executeStep(
                        i, plan, apiBaseUrl, Map.of(), Map.of(), lastBody, lastHeaders, lastStatus);
                enrichments.add(result.enrichment());
                if (result.responseBody() != null) lastBody = result.responseBody();
                if (result.responseHeaders() != null && !result.responseHeaders().isEmpty())
                    lastHeaders = result.responseHeaders();
                if (result.responseStatusCode() != null) lastStatus = result.responseStatusCode();
            } catch (AssertionFailure ex) {
                return new RunnerOutcome(new ExecutionOutcome(
                        tc.id(), tc.sourceFilename(), "FAILED",
                        "Шаг " + (i + 1) + ": " + ex.getMessage(), enrichments),
                        List.of(), false);
            } catch (UiExecutor.LocatorNotResolved ex) {
                String topicKey = normalizeTopic(plan.topicKey(), ex.getMessage());
                if (isSkippedInThisCase(tc, topicKey) || findAnswerInThisCase(tc, topicKey) != null) {
                    return skipped(tc, enrichments, i, ex.getMessage());
                }
                Clarification c = new Clarification(tc.id(), i,
                        withTopic("Шаг " + (i + 1) + " («" + step + "»): " + ex.getMessage(), topicKey),
                        "Ошибка планирования API",
                        "DATA_MISSING");
                return new RunnerOutcome(new ExecutionOutcome(
                        tc.id(), tc.sourceFilename(),
                        "NEEDS_CLARIFICATION", "Прервано на шаге " + (i + 1), enrichments),
                        List.of(c), false);
            }
        }
        return new RunnerOutcome(new ExecutionOutcome(
                tc.id(), tc.sourceFilename(), "PASSED", null, enrichments),
                List.of(), false);
    }

    private RunnerOutcome skipped(ParsedTestCase tc, List<StepEnrichment> enrichments,
                                  int stepIndex, String reason) {
        String msg = "Пропущено по запросу пользователя (SKIP)"
                + (reason == null ? "" : ": " + reason);
        return new RunnerOutcome(new ExecutionOutcome(
                tc.id(), tc.sourceFilename(), "SKIPPED", msg, enrichments),
                List.of(), false);
    }

    private String normalizeTopic(String topicKey, String reason) {
        if (topicKey != null && !topicKey.isBlank()) {
            String t = topicKey.trim().toLowerCase().replaceAll("[^a-z0-9_]+", "_");
            return t.length() > 40 ? t.substring(0, 40) : t;
        }
        String base = (reason != null && !reason.isBlank())
                ? reason.toLowerCase().replaceAll("[^a-z0-9]+", "_")
                : "clarification";
        if (base.isBlank()) base = "clarification";
        return base.length() > 40 ? base.substring(0, 40) : base;
    }

    private String withTopic(String question, String topicKey) {
        if (topicKey == null || topicKey.isBlank()) return question;
        return "[" + topicKey + "] " + (question == null ? "" : question);
    }
}
