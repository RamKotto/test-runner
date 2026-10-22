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

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridCaseRunner {

    private final StepPlanner planner;
    private final UiExecutor uiExecutor;
    private final ApiExecutor apiExecutor;

    public record Result(
            ExecutionOutcome outcome,
            List<Clarification> clarifications,
            int stoppedAtStepIndex
    ) {
    }

    public Result run(ParsedTestCase tc, String startUrl, String apiBaseUrl) {
        return run(tc, startUrl, apiBaseUrl, 0);
    }

    public Result run(ParsedTestCase tc, String startUrl, String apiBaseUrl, int startStep) {
        String type = tc.type() == null ? "UI" : tc.type().trim().toUpperCase();
        boolean allowUi = !"API".equals(type);
        boolean allowApi = !"UI".equals(type);

        List<StepEnrichment> enrichments = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Clarification> clarifications = new ArrayList<>();

        String lastBody = null;
        Map<String, String> lastHeaders = new LinkedHashMap<>();
        Integer lastStatus = null;

        boolean apiDataAvailable = false;

        UiExecutor.UiSession uiSession = null;
        int fromStep = Math.max(0, startStep);
        try {
            for (int i = fromStep; i < tc.steps().size(); i++) {
                String step = tc.steps().get(i);

                // Ленивое поднятие UI-сессии ДО планирования, чтобы агент
                // получил полный DOM-снапшот и сам нашёл локатор.
                if (uiSession == null && allowUi) {
                    String resolved = resolveStartUrl(tc, startUrl);
                    if (resolved != null && !resolved.isBlank()
                            && !"SKIP".equalsIgnoreCase(resolved.trim())
                            && (resolved.startsWith("http://") || resolved.startsWith("https://"))) {
                        try {
                            uiSession = uiExecutor.newSession(resolved);
                        } catch (Exception ex) {
                            log.warn("Не удалось поднять UI-сессию для {}: {}", tc.id(), ex.getMessage());
                        }
                    }
                }

                StepPlanner.Plan plan;
                try {
                    String domSnapshot = uiSession != null
                            ? uiExecutor.snapshotForPlanning(uiSession)
                            : null;
                    plan = planner.planStep(tc, i, step, domSnapshot, apiBaseUrl,
                            enrichments, lastBody, lastHeaders, lastStatus);
                } catch (StepPlanner.PlannerInputTooLargeException tooLarge) {
                    String topicKey = "context_overflow";
                    if (isSkippedInThisCase(tc, topicKey)
                            || findAnswerInThisCase(tc, topicKey) != null) {
                        enrichments.add(new StepEnrichment(i, "skip",
                                "⏭ SKIP по ответу (topic=" + topicKey + "): "
                                        + tooLarge.getMessage()));
                        continue;
                    }
                    clarifications.add(new Clarification(tc.id(), i,
                            withTopic("Шаг " + (i + 1) + " («" + step + "»): "
                                    + tooLarge.getMessage(), topicKey),
                            "Контекст планера переполнен", "DATA_MISSING", topicKey));
                    return new Result(new ExecutionOutcome(
                            tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                            "Прервано на шаге " + (i + 1), enrichments),
                            clarifications, i);
                } catch (GenerationException ge) {
                    failures.add("Шаг " + (i + 1) + ": " + ge.getMessage());
                    enrichments.add(new StepEnrichment(i, "planning-error",
                            "❌ Не удалось построить план: " + ge.getMessage()));
                    continue;
                }

                if (plan.isSkip()) {
                    String reason = plan.reason() == null ? "" : plan.reason();
                    String topic = plan.topicKey();
                    boolean cascaded = "cascaded_skip".equalsIgnoreCase(topic)
                            || (reason != null && reason.toLowerCase().contains("предыдущ"));
                    if ("apicall".equalsIgnoreCase(plan.operation())) {
                        apiDataAvailable = false;
                    }
                    enrichments.add(new StepEnrichment(i, "skip",
                            (cascaded ? "⏭ SKIP (каскад): " : "⏭ SKIP: ") + reason));
                    continue;
                }

                if (plan.needsClarification()) {
                    String topicKey = normalizeTopic(plan.topicKey(), plan.reason());
                    if (isSkippedInThisCase(tc, topicKey)
                            || findAnswerInThisCase(tc, topicKey) != null) {
                        enrichments.add(new StepEnrichment(i, "skip",
                                "⏭ SKIP по ответу пользователя (topic=" + topicKey + ")"));
                        continue;
                    }
                    clarifications.add(new Clarification(tc.id(), i,
                            withTopic(plan.question(), topicKey), plan.reason(),
                            "DATA_MISSING", topicKey));
                    return new Result(new ExecutionOutcome(
                            tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                            "Прервано на шаге " + (i + 1), enrichments),
                            clarifications, i);
                }

                String op = plan.operation() == null ? "" : plan.operation().toLowerCase();
                boolean isUiOp = StepPlanner.UI_OPS.contains(op);
                boolean isApiOp = StepPlanner.API_OPS.contains(op);

                boolean isApiAssert = Set.of("assertbody", "assertbodyabsent",
                        "assertheader", "assertheaderabsent",
                        "assertstatus", "parsejson").contains(op);
                if (isApiAssert && !apiDataAvailable) {
                    enrichments.add(new StepEnrichment(i, op,
                            "⏭ SKIP (каскад): нет данных от предыдущего apiCall"));
                    continue;
                }

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
                        String resolved = resolveStartUrl(tc, startUrl);
                        if (resolved == null || resolved.isBlank()
                                || "SKIP".equalsIgnoreCase(resolved.trim())
                                || !(resolved.startsWith("http://") || resolved.startsWith("https://"))) {
                            String topicKey = "start_url";
                            if (isSkippedInThisCase(tc, topicKey)
                                    || findAnswerInThisCase(tc, topicKey) != null) {
                                enrichments.add(new StepEnrichment(i, "skip",
                                        "⏭ SKIP по ответу (topic=start_url)"));
                                continue;
                            }
                            clarifications.add(new Clarification(tc.id(), i,
                                    withTopic("Шаг " + (i + 1) + " («" + step
                                                    + "»): укажите стартовый URL (http(s)://...) для UI-кейса",
                                            topicKey),
                                    "startUrl не передан в RunRequest и не дан в ответах",
                                    "DATA_MISSING", topicKey));
                            return new Result(new ExecutionOutcome(
                                    tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                                    "Прервано на шаге " + (i + 1), enrichments),
                                    clarifications, i);
                        }
                        uiSession = uiExecutor.newSession(resolved);
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
                        String topicKey = normalizeUiLocatorTopic(lnr, plan);
                        if (isSkippedInThisCase(tc, topicKey)
                                || findAnswerInThisCase(tc, topicKey) != null) {
                            enrichments.add(new StepEnrichment(i, "skip",
                                    "⏭ SKIP по ответу (topic=" + topicKey + "): " + lnr.getMessage()));
                            continue;
                        }
                        clarifications.add(new Clarification(tc.id(), i,
                                withTopic("Шаг " + (i + 1) + " («" + step + "»): "
                                        + lnr.getMessage(), topicKey),
                                "Неоднозначность/отсутствие локатора",
                                "LOCATOR_NOT_FOUND", topicKey));
                        return new Result(new ExecutionOutcome(
                                tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                                "Прервано на шаге " + (i + 1), enrichments),
                                clarifications, i);
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
                        if (r.responseStatusCode() != null) {
                            lastStatus = r.responseStatusCode();
                            apiDataAvailable = true;
                        }
                    } catch (AssertionFailure af) {
                        if ("apicall".equals(op) && isConfigError(af.getMessage())) {
                            String topicKey = "api_base_url";
                            if (isSkippedInThisCase(tc, topicKey)
                                    || findAnswerInThisCase(tc, topicKey) != null) {
                                enrichments.add(new StepEnrichment(i, "skip",
                                        "⏭ SKIP по ответу (topic=" + topicKey + "): "
                                                + af.getMessage()));
                                continue;
                            }
                            clarifications.add(new Clarification(tc.id(), i,
                                    withTopic("Шаг " + (i + 1) + " («" + step + "»): "
                                            + af.getMessage(), topicKey),
                                    "Не задан/недоступен API base URL",
                                    "DATA_MISSING", topicKey));
                            return new Result(new ExecutionOutcome(
                                    tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                                    "Прервано на шаге " + (i + 1), enrichments),
                                    clarifications, i);
                        }
                        enrichments.add(new StepEnrichment(i, op, "❌ " + af.getMessage()));
                        failures.add("Шаг " + (i + 1) + ": " + af.getMessage());
                    } catch (UiExecutor.LocatorNotResolved lnr) {
                        String topicKey = normalizeTopic(plan.topicKey(), lnr.getMessage());
                        if (topicKey == null || topicKey.isBlank()
                                || "clarification".equals(topicKey)) {
                            if (lnr.getMessage() != null
                                    && lnr.getMessage().toLowerCase().contains("api_base_url")) {
                                topicKey = "api_base_url";
                            }
                        }
                        if (isSkippedInThisCase(tc, topicKey)
                                || findAnswerInThisCase(tc, topicKey) != null) {
                            enrichments.add(new StepEnrichment(i, "skip",
                                    "⏭ SKIP по ответу (topic=" + topicKey + "): " + lnr.getMessage()));
                            continue;
                        }
                        clarifications.add(new Clarification(tc.id(), i,
                                withTopic("Шаг " + (i + 1) + " («" + step + "»): "
                                        + lnr.getMessage(), topicKey),
                                "Ошибка планирования API", "DATA_MISSING", topicKey));
                        return new Result(new ExecutionOutcome(
                                tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                                "Прервано на шаге " + (i + 1), enrichments),
                                clarifications, i);
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
                        enrichments), List.of(), tc.steps().size());
            }
            return new Result(new ExecutionOutcome(
                    tc.id(), tc.sourceFilename(), "PASSED", null, enrichments),
                    List.of(), tc.steps().size());
        } finally {
            if (uiSession != null) {
                try {
                    uiSession.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean isConfigError(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("unknown protocol")
                || m.contains("api_base_url")
                || m.contains("api base url")
                || m.contains("unknown host")
                || m.contains("connection refused")
                || m.contains("connect timed out")
                || m.contains("connection timed out")
                || m.contains("не отвечает")
                || m.contains("некорректный абсолютный url")
                || m.contains("не задан корректный apibaseurl");
    }

    private String resolveStartUrl(ParsedTestCase tc, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        if (tc.answers() == null) return null;
        for (var a : tc.answers()) {
            if (a.topicKey() != null && a.topicKey().equalsIgnoreCase("start_url")) {
                String ans = a.answer();
                if (ans != null && !ans.isBlank()
                        && !"SKIP".equalsIgnoreCase(ans.trim())) {
                    return ans.trim();
                }
                return null;
            }
        }
        return fallback;
    }

    private boolean isSkippedInThisCase(ParsedTestCase tc, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || tc.answers() == null) return false;
        String needle = topicKey.trim().toLowerCase();
        for (var a : tc.answers()) {
            String tk = a.topicKey() == null ? "" : a.topicKey().trim().toLowerCase();
            String ans = a.answer() == null ? "" : a.answer().trim();
            if (!"SKIP".equalsIgnoreCase(ans)) continue;
            if (tk.equals(needle)) return true;
            if (tk.isEmpty()) {
                String q = a.question() == null ? "" : a.question().toLowerCase();
                if (q.contains(needle)) return true;
            }
        }
        return false;
    }

    private String findAnswerInThisCase(ParsedTestCase tc, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || tc.answers() == null) return null;
        String needle = topicKey.trim().toLowerCase();
        for (var a : tc.answers()) {
            String tk = a.topicKey() == null ? "" : a.topicKey().trim().toLowerCase();
            String ans = a.answer();
            if (ans == null || ans.isBlank()) continue;
            if (tk.equals(needle)) return ans;
            if (tk.isEmpty()) {
                String q = a.question() == null ? "" : a.question().toLowerCase();
                if (q.contains(needle)) return ans;
            }
        }
        return null;
    }

    /**
     * Стабильный topicKey для UI-неоднозначности: "ui_locator_<role>",
     * где role берётся из LocatorNotResolved.roleKey() (elementRole из плана)
     * или из плана. Если role нет — "ui_locator_element".
     */
    private String normalizeUiLocatorTopic(UiExecutor.LocatorNotResolved lnr,
                                           StepPlanner.Plan plan) {
        String role = lnr.roleKey();
        if (role == null || role.isBlank()) {
            role = plan.elementRole();
        }
        if (role == null || role.isBlank()) {
            role = "element";
        }
        String norm = role.trim().toLowerCase().replaceAll("[^a-z0-9_]+", "_");
        if (norm.isBlank()) norm = "element";
        return "ui_locator_" + (norm.length() > 30 ? norm.substring(0, 30) : norm);
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
