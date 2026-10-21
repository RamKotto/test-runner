package application.service;

import application.dto.Dtos.Clarification;
import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.ParsedTestCase;
import application.dto.Dtos.StepEnrichment;
import application.exception.AssertionFailure;
import application.exception.GenerationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Гибридный исполнитель одного кейса: UI + API в одном прогоне.
 * Используется для типов UI, API, E2E, NEGATIVE, SECURITY.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridCaseRunner {

    private final StepPlanner planner;
    private final UiExecutor uiExecutor;
    private final ApiExecutor apiExecutor;

    public record Result(
            ExecutionOutcome outcome,
            List<Clarification> clarifications
    ) {
    }

    public Result run(ParsedTestCase tc, String startUrl, String apiBaseUrl) {
        String type = tc.type() == null ? "UI" : tc.type().trim().toUpperCase();
        boolean allowUi = !"API".equals(type);
        boolean allowApi = !"UI".equals(type);
        // INVALID сюда не доходит — отсеивается в TestCaseRunnerService.

        List<StepEnrichment> enrichments = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Clarification> clarifications = new ArrayList<>();

        // Общий REST-контекст между шагами.
        String lastBody = null;
        Map<String, String> lastHeaders = new LinkedHashMap<>();
        Integer lastStatus = null;

        UiExecutor.UiSession uiSession = null;
        try {
            for (int i = 0; i < tc.steps().size(); i++) {
                String step = tc.steps().get(i);

                StepPlanner.Plan plan;
                try {
                    String domSnapshot = uiSession != null
                            ? uiExecutor.snapshotForPlanning(uiSession)
                            : null;
                    plan = planner.planStep(tc, i, step, domSnapshot, apiBaseUrl,
                            enrichments, lastBody, lastHeaders, lastStatus);
                } catch (GenerationException ge) {
                    failures.add("Шаг " + (i + 1) + ": " + ge.getMessage());
                    enrichments.add(new StepEnrichment(i, "planning-error",
                            "❌ Не удалось построить план: " + ge.getMessage()));
                    continue;
                }

                if (plan.isSkip()) {
                    String reason = plan.reason() == null ? "" : ": " + plan.reason();
                    enrichments.add(new StepEnrichment(i, "skip", "⏭ SKIP" + reason));
                    continue;
                }

                if (plan.needsClarification()) {
                    String topicKey = normalizeTopic(plan.topicKey(), plan.reason());
                    if (isSkippedInThisCase(tc, topicKey) || findAnswerInThisCase(tc, topicKey) != null) {
                        enrichments.add(new StepEnrichment(i, "skip",
                                "⏭ SKIP по ответу пользователя (topic=" + topicKey + ")"));
                        continue;
                    }
                    clarifications.add(new Clarification(tc.id(), i,
                            withTopic(plan.question(), topicKey), plan.reason(), "DATA_MISSING"));
                    return new Result(new ExecutionOutcome(
                            tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                            "Прервано на шаге " + (i + 1), enrichments),
                            clarifications);
                }

                String op = plan.operation() == null ? "" : plan.operation().toLowerCase();
                boolean isUiOp = StepPlanner.UI_OPS.contains(op);
                boolean isApiOp = StepPlanner.API_OPS.contains(op);

                if (isUiOp && !allowUi) {
                    failures.add("Шаг " + (i + 1) + ": API-кейс требует UI-операцию '" + op + "'");
                    enrichments.add(new StepEnrichment(i, "type-mismatch",
                            "❌ UI-операция " + op + " запрещена в API-кейсе"));
                    continue;
                }
                if (isApiOp && !allowApi) {
                    failures.add("Шаг " + (i + 1) + ": UI-кейс требует API-операцию '" + op
                            + "'. Смените type на E2E/NEGATIVE/SECURITY, если нужен REST.");
                    enrichments.add(new StepEnrichment(i, "type-mismatch",
                            "❌ API-операция " + op + " запрещена в UI-кейсе"));
                    continue;
                }

                if (isUiOp) {
                    if (uiSession == null) {
                        uiSession = uiExecutor.newSession(resolveStartUrl(tc, startUrl));
                    }
                    try {
                        uiExecutor.dumpHtml(uiSession, tc.id(), i);
                        StepEnrichment e = uiExecutor.executeStep(
                                uiSession, i, plan, uiExecutor.currentUrl(uiSession));
                        enrichments.add(e);
                    } catch (AssertionFailure af) {
                        enrichments.add(new StepEnrichment(i, op,
                                "❌ " + af.getMessage()));
                        failures.add("Шаг " + (i + 1) + ": " + af.getMessage());
                    } catch (UiExecutor.LocatorNotResolved lnr) {
                        String topicKey = normalizeTopic(plan.topicKey(), lnr.getMessage());
                        if (isSkippedInThisCase(tc, topicKey)
                                || findAnswerInThisCase(tc, topicKey) != null) {
                            enrichments.add(new StepEnrichment(i, "skip",
                                    "⏭ SKIP по ответу (topic=" + topicKey + "): " + lnr.getMessage()));
                            continue;
                        }
                        Clarification c = new Clarification(tc.id(), i,
                                withTopic("Шаг " + (i + 1) + " («" + step + "»): "
                                        + lnr.getMessage(), topicKey),
                                "Ошибка планирования UI", "LOCATOR_NOT_FOUND");
                        clarifications.add(c);
                        return new Result(new ExecutionOutcome(
                                tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                                "Прервано на шаге " + (i + 1), enrichments),
                                clarifications);
                    } catch (UiExecutor.UnsupportedUiOperation uuo) {
                        enrichments.add(new StepEnrichment(i, op, "❌ " + uuo.getMessage()));
                        failures.add("Шаг " + (i + 1) + ": " + uuo.getMessage());
                    } catch (Exception ex) {
                        log.error("UI-шаг {} в {} упал", i + 1, tc.id(), ex);
                        enrichments.add(new StepEnrichment(i, op,
                                "❌ Необработанная ошибка UI: " + ex.getMessage()));
                        failures.add("Шаг " + (i + 1) + ": " + ex.getMessage());
                    }
                } else if (isApiOp) {
                    try {
                        ApiExecutor.ApiStepResult r = apiExecutor.executeStep(
                                i, plan, apiBaseUrl, Map.of(), Map.of(),
                                lastBody, lastHeaders, lastStatus);
                        enrichments.add(r.enrichment());
                        if (r.responseBody() != null) lastBody = r.responseBody();
                        if (r.responseHeaders() != null && !r.responseHeaders().isEmpty())
                            lastHeaders = r.responseHeaders();
                        if (r.responseStatusCode() != null) lastStatus = r.responseStatusCode();
                    } catch (AssertionFailure af) {
                        enrichments.add(new StepEnrichment(i, op, "❌ " + af.getMessage()));
                        failures.add("Шаг " + (i + 1) + ": " + af.getMessage());
                        // После assertStatus FAILED REST-контекст сохраняем:
                        // следующие шаги могут зависеть от ответа.
                    } catch (UiExecutor.LocatorNotResolved lnr) {
                        String topicKey = normalizeTopic(plan.topicKey(), lnr.getMessage());
                        if (isSkippedInThisCase(tc, topicKey)
                                || findAnswerInThisCase(tc, topicKey) != null) {
                            enrichments.add(new StepEnrichment(i, "skip",
                                    "⏭ SKIP по ответу (topic=" + topicKey + "): " + lnr.getMessage()));
                            continue;
                        }
                        clarifications.add(new Clarification(tc.id(), i,
                                withTopic("Шаг " + (i + 1) + " («" + step + "»): "
                                        + lnr.getMessage(), topicKey),
                                "Ошибка планирования API", "DATA_MISSING"));
                        return new Result(new ExecutionOutcome(
                                tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                                "Прервано на шаге " + (i + 1), enrichments),
                                clarifications);
                    } catch (Exception ex) {
                        log.error("API-шаг {} в {} упал", i + 1, tc.id(), ex);
                        enrichments.add(new StepEnrichment(i, op,
                                "❌ Необработанная ошибка API: " + ex.getMessage()));
                        failures.add("Шаг " + (i + 1) + ": " + ex.getMessage());
                    }
                } else {
                    enrichments.add(new StepEnrichment(i, "unknown-op",
                            "❌ Неизвестная операция: " + op));
                    failures.add("Шаг " + (i + 1) + ": неизвестная операция '" + op + "'");
                }
            }

            if (!failures.isEmpty()) {
                return new Result(new ExecutionOutcome(
                        tc.id(), tc.sourceFilename(), "FAILED",
                        failures.get(0) + (failures.size() > 1
                                ? " (и ещё " + (failures.size() - 1) + ")" : ""),
                        enrichments), List.of());
            }
            return new Result(new ExecutionOutcome(
                    tc.id(), tc.sourceFilename(), "PASSED", null, enrichments),
                    List.of());
        } finally {
            if (uiSession != null) {
                try {
                    uiSession.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String resolveStartUrl(ParsedTestCase tc, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        if (tc.answers() == null) return null;
        for (var a : tc.answers()) {
            if (a.topicKey() != null && a.topicKey().equalsIgnoreCase("start_url")) {
                return a.answer();
            }
        }
        return fallback;
    }

    private boolean isSkippedInThisCase(ParsedTestCase tc, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || tc.answers() == null) return false;
        String needle = topicKey.trim().toLowerCase();
        for (var a : tc.answers()) {
            String q = a.question() == null ? "" : a.question().toLowerCase();
            String ans = a.answer() == null ? "" : a.answer().trim();
            if (q.contains(needle) && "SKIP".equalsIgnoreCase(ans)) return true;
        }
        return false;
    }

    private String findAnswerInThisCase(ParsedTestCase tc, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || tc.answers() == null) return null;
        String needle = topicKey.trim().toLowerCase();
        for (var a : tc.answers()) {
            String q = a.question() == null ? "" : a.question().toLowerCase();
            if (q.contains(needle)) return a.answer();
        }
        return null;
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
