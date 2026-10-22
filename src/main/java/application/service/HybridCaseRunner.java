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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridCaseRunner {

    private final StepPlanner planner;
    private final UiExecutor uiExecutor;
    private final ApiExecutor apiExecutor;

    /**
     * Признаки «этот шаг требует сравнения с предыдущим».
     */
    private static final Pattern COMPARE_HINT = Pattern.compile(
            "сравн|идентичн|совпад|оба ответ|не измен|равн",
            Pattern.CASE_INSENSITIVE);

    /**
     * Извлечение пути вида hourly.temperature_2m из текста шага.
     */
    private static final Pattern JSON_PATH_HINT = Pattern.compile(
            "([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)+)",
            Pattern.CASE_INSENSITIVE);

    public record Result(
            ExecutionOutcome outcome,
            List<Clarification> clarifications,
            int stoppedAtStepIndex
    ) {
    }

    /**
     * Результат выполнения одной API-операции.
     * apiResult != null только для apiCall — остальные операции
     * не меняют lastBody/lastHeaders/lastStatus.
     */
    private record ApiOpResult(StepEnrichment enrichment,
                               ApiExecutor.ApiStepResult apiResult) {
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

        Map<String, String> snapshots = new LinkedHashMap<>();

        UiExecutor.UiSession uiSession = null;
        int fromStep = Math.max(0, startStep);
        try {
            for (int i = fromStep; i < tc.steps().size(); i++) {
                String step = tc.steps().get(i);

                List<StepPlanner.Plan> plans;
                try {
                    String domSnapshot = uiSession != null
                            ? uiExecutor.snapshotForPlanning(uiSession)
                            : null;
                    plans = planner.planStep(tc, i, step, domSnapshot, apiBaseUrl,
                            enrichments, lastBody, lastHeaders, lastStatus, snapshots);
                } catch (GenerationException ge) {
                    failures.add("Шаг " + (i + 1) + ": " + ge.getMessage());
                    enrichments.add(new StepEnrichment(i, "planning-error",
                            "❌ Не удалось построить план: " + ge.getMessage()));
                    continue;
                }

                // Детерминированный препроцессор: если в будущих шагах есть сравнение,
                // и модель забыла snapshotApiValue — добавляем его программно.
                plans = maybeAddSnapshotForFutureCompare(plans, tc, i);

                for (StepPlanner.Plan plan : plans) {
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

                    if (isApiOp && isApiAssert(op) && !apiDataAvailable) {
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
                            StartUrlOutcome su = resolveOrClarifyStartUrl(tc, startUrl, i, step,
                                    enrichments);
                            if (su.clarification() != null) {
                                clarifications.add(su.clarification());
                                return new Result(new ExecutionOutcome(
                                        tc.id(), tc.sourceFilename(), "NEEDS_CLARIFICATION",
                                        "Прервано на шаге " + (i + 1), enrichments),
                                        clarifications, i);
                            }
                            if (su.isSkip()) continue;
                            uiSession = uiExecutor.newSession(su.url());
                        }

                        try {
                            uiExecutor.dumpHtml(uiSession, tc.id(), i);
                            StepEnrichment e = executeUiOp(uiSession, i, plan, snapshots);
                            enrichments.add(e);
                        } catch (AssertionFailure af) {
                            enrichments.add(new StepEnrichment(i, op, "❌ " + af.getMessage()));
                            failures.add("Шаг " + (i + 1) + ": " + af.getMessage());
                        } catch (UiExecutor.LocatorNotResolved lnr) {
                            String topicKey = normalizeTopic(plan.topicKey(), lnr.getMessage());
                            if (isSkippedInThisCase(tc, topicKey)
                                    || findAnswerInThisCase(tc, topicKey) != null) {
                                enrichments.add(new StepEnrichment(i, "skip",
                                        "⏭ SKIP по ответу (topic=" + topicKey + "): "
                                                + lnr.getMessage()));
                                continue;
                            }
                            clarifications.add(new Clarification(tc.id(), i,
                                    withTopic("Шаг " + (i + 1) + " («" + step + "»): "
                                            + lnr.getMessage(), topicKey),
                                    "Ошибка планирования UI", "LOCATOR_NOT_FOUND", topicKey));
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
                        continue;
                    }

                    if (isApiOp) {
                        try {
                            ApiOpResult r = executeApiOp(i, plan, apiBaseUrl,
                                    lastBody, lastHeaders, lastStatus, snapshots);
                            enrichments.add(r.enrichment());
                            if (r.apiResult() != null) {
                                if (r.apiResult().responseBody() != null) {
                                    lastBody = r.apiResult().responseBody();
                                }
                                if (r.apiResult().responseHeaders() != null
                                        && !r.apiResult().responseHeaders().isEmpty()) {
                                    lastHeaders = r.apiResult().responseHeaders();
                                }
                                if (r.apiResult().responseStatusCode() != null) {
                                    lastStatus = r.apiResult().responseStatusCode();
                                    apiDataAvailable = true;
                                }
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
                                        "⏭ SKIP по ответу (topic=" + topicKey + "): "
                                                + lnr.getMessage()));
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
                        continue;
                    }

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

            boolean allSkipped = !enrichments.isEmpty() && enrichments.stream()
                    .allMatch(e -> "skip".equalsIgnoreCase(e.action()));
            if (allSkipped) {
                return new Result(new ExecutionOutcome(
                        tc.id(), tc.sourceFilename(), "SKIPPED",
                        "Все шаги пропущены по ответам пользователя", enrichments),
                        List.of(), tc.steps().size());
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

    // =========================== helpers ===========================

    private static boolean isApiAssert(String op) {
        return Set.of("assertbody", "assertbodyabsent",
                "assertheader", "assertheaderabsent",
                "assertstatus", "parsejson",
                "assertapivalueequalssnapshot").contains(op);
    }

    /**
     * Детерминированный препроцессор: если текущий шаг — apiCall,
     * а в БУДУЩИХ шагах упоминается сравнение («сравнить», «идентичны»,
     * «оба ответа» и т.д.), и модель забыла snapshotApiValue — добавляем
     * snapshotApiValue программно.
     * <p>
     * Это страховка на случай недетерминизма LLM: ПРАВИЛО 8.1 в промпте
     * просит модель снимать snapshot сама, а этот метод гарантирует,
     * что снимок будет снят.
     */
    private List<StepPlanner.Plan> maybeAddSnapshotForFutureCompare(
            List<StepPlanner.Plan> plans, ParsedTestCase tc, int currentStep) {
        if (plans == null || plans.isEmpty()) return plans;

        String type = tc.type() == null ? "" : tc.type().trim().toUpperCase();
        if ("UI".equals(type)) return plans; // только для API/гибридных

        boolean hasApiCall = plans.stream()
                .anyMatch(p -> "apicall".equalsIgnoreCase(p.operation()));
        if (!hasApiCall) return plans;

        if (!futureMentionsComparison(tc.steps(), currentStep)) return plans;

        boolean hasSnapshot = plans.stream()
                .anyMatch(p -> "snapshotapivalue".equalsIgnoreCase(p.operation()));
        if (hasSnapshot) return plans;

        String jsonPath = guessJsonPath(tc.steps(), currentStep);
        String snapshotKey = "auto_step" + (currentStep + 1);

        log.info("Детерминированный snapshot: шаг {}, jsonPath={}, key={}",
                currentStep + 1, jsonPath, snapshotKey);

        StepPlanner.Plan snap = new StepPlanner.Plan(
                "snapshotApiValue", null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), null, snapshotKey, jsonPath, null);
        List<StepPlanner.Plan> result = new ArrayList<>(plans);
        result.add(snap);
        return result;
    }

    private boolean futureMentionsComparison(List<String> steps, int currentStep) {
        if (steps == null) return false;
        for (int j = currentStep + 1; j < steps.size(); j++) {
            if (COMPARE_HINT.matcher(steps.get(j)).find()) return true;
        }
        return false;
    }

    private String guessJsonPath(List<String> steps, int currentStep) {
        if (steps == null) return "$";
        for (int j = currentStep + 1; j < steps.size(); j++) {
            Matcher m = JSON_PATH_HINT.matcher(steps.get(j));
            if (m.find()) {
                return "$." + m.group(1);
            }
        }
        return "$";
    }

    private record StartUrlOutcome(String url, boolean isSkip, Clarification clarification) {
        static StartUrlOutcome ok(String url) {
            return new StartUrlOutcome(url, false, null);
        }

        static StartUrlOutcome skip() {
            return new StartUrlOutcome(null, true, null);
        }

        static StartUrlOutcome clarify(Clarification c) {
            return new StartUrlOutcome(null, false, c);
        }
    }

    private StartUrlOutcome resolveOrClarifyStartUrl(ParsedTestCase tc, String startUrl,
                                                     int i, String step,
                                                     List<StepEnrichment> enrichments) {
        String resolved = resolveStartUrl(tc, startUrl);
        boolean valid = resolved != null && !resolved.isBlank()
                && !"SKIP".equalsIgnoreCase(resolved.trim())
                && (resolved.startsWith("http://") || resolved.startsWith("https://"));
        if (valid) return StartUrlOutcome.ok(resolved);

        String topicKey = "start_url";
        if (isSkippedInThisCase(tc, topicKey)
                || findAnswerInThisCase(tc, topicKey) != null) {
            enrichments.add(new StepEnrichment(i, "skip",
                    "⏭ SKIP по ответу (topic=start_url)"));
            return StartUrlOutcome.skip();
        }
        Clarification c = new Clarification(tc.id(), i,
                withTopic("Шаг " + (i + 1) + " («" + step
                        + "»): укажите стартовый URL (http(s)://...) для UI-кейса", topicKey),
                "startUrl не передан в RunRequest и не дан в ответах",
                "DATA_MISSING", topicKey);
        return StartUrlOutcome.clarify(c);
    }

    private StepEnrichment executeUiOp(UiExecutor.UiSession uiSession, int i,
                                       StepPlanner.Plan plan,
                                       Map<String, String> snapshots) {
        String op = plan.operation() == null ? "" : plan.operation().toLowerCase();
        return switch (op) {
            case "snapshotuitext" -> {
                UiExecutor.SnapshotResult sr = uiExecutor.snapshotUiText(uiSession, i, plan);
                snapshots.put(sr.key(), sr.value());
                yield sr.enrichment();
            }
            case "assertuitextequalssnapshot" -> uiExecutor.assertUiTextEqualsSnapshot(uiSession, i, plan, snapshots);
            default -> uiExecutor.executeStep(uiSession, i, plan,
                    uiExecutor.currentUrl(uiSession));
        };
    }

    private ApiOpResult executeApiOp(int i, StepPlanner.Plan plan, String apiBaseUrl,
                                     String lastBody, Map<String, String> lastHeaders,
                                     Integer lastStatus, Map<String, String> snapshots) {
        String op = plan.operation() == null ? "" : plan.operation().toLowerCase();
        return switch (op) {
            case "snapshotapivalue" -> {
                ApiExecutor.SnapshotResult sr =
                        apiExecutor.snapshotApiValue(i, plan, lastBody);
                snapshots.put(sr.key(), sr.value());
                yield new ApiOpResult(sr.enrichment(), null);
            }
            case "assertapivalueequalssnapshot" -> {
                ApiExecutor.ApiStepResult r =
                        apiExecutor.assertApiValueEqualsSnapshot(i, plan, lastBody, snapshots);
                yield new ApiOpResult(r.enrichment(), null);
            }
            default -> {
                ApiExecutor.ApiStepResult r = apiExecutor.executeStep(
                        i, plan, apiBaseUrl, Map.of(), Map.of(),
                        lastBody, lastHeaders, lastStatus);
                yield new ApiOpResult(r.enrichment(), r);
            }
        };
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
