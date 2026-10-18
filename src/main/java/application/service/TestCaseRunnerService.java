package application.service;

import application.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseRunnerService {

    private final StepPlanner planner;
    private final UiExecutor uiExecutor;
    private final ApiExecutor apiExecutor;

    public record RunnerOutcome(
            ExecutionOutcome outcome,
            List<Clarification> clarifications,
            boolean skipCase
    ) {
    }

    public RunnerOutcome runCase(ParsedTestCase tc, String startUrl, String apiBaseUrl) {
        String effectiveApiBaseUrl = resolveApiBaseUrl(tc, apiBaseUrl);
        String effectiveStartUrl = resolveStartUrl(tc, startUrl);

        if (isApiType(tc.type())) return runApiCase(tc, effectiveApiBaseUrl);
        return runUiCase(tc, effectiveStartUrl);
    }

    private String resolveApiBaseUrl(ParsedTestCase tc, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        if (tc.answers() == null) return null;
        for (AnswerRequest.Answer a : tc.answers()) {
            String ans = a.answer();
            if (ans != null && (ans.startsWith("http://") || ans.startsWith("https://"))) {
                log.info("apiBaseUrl взят из ответа пользователя: {}", ans);
                return ans;
            }
        }
        return fallback;
    }

    private String resolveStartUrl(ParsedTestCase tc, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        if (tc.answers() == null) return null;
        for (AnswerRequest.Answer a : tc.answers()) {
            String ans = a.answer();
            if (ans != null && (ans.startsWith("http://") || ans.startsWith("https://"))) {
                log.info("startUrl взят из ответа пользователя: {}", ans);
                return ans;
            }
        }
        return fallback;
    }

    /**
     * Проверяет, был ли уже ответ SKIP на тему topicKey.
     * Если да — уточнение по этой теме повторно не задаём, кейс пропускаем.
     */
    private boolean isSkipped(ParsedTestCase tc, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || tc.answers() == null) return false;
        String needle = topicKey.trim().toLowerCase();
        for (AnswerRequest.Answer a : tc.answers()) {
            String q = a.question() == null ? "" : a.question().toLowerCase();
            String ans = a.answer() == null ? "" : a.answer().trim();
            if (q.contains(needle) && "SKIP".equalsIgnoreCase(ans)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверяет, задавался ли уже вопрос с этой темой (независимо от ответа).
     * Нужно для правила «не более 1 раза по конкретной теме».
     */
    private boolean wasAsked(ParsedTestCase tc, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || tc.answers() == null) return false;
        String needle = topicKey.trim().toLowerCase();
        for (AnswerRequest.Answer a : tc.answers()) {
            String q = a.question() == null ? "" : a.question().toLowerCase();
            if (q.contains(needle)) return true;
        }
        return false;
    }

    private RunnerOutcome runUiCase(ParsedTestCase tc, String startUrl) {
        List<StepEnrichment> enrichments = new ArrayList<>();
        String failure = null;

        try (UiExecutor.UiSession session = uiExecutor.newSession(startUrl)) {
            for (int i = 0; i < tc.steps().size(); i++) {
                String step = tc.steps().get(i);
                String html = uiExecutor.snapshotHtml(session);
                StepPlanner.Plan plan = planner.planStep(tc, i, step, html, null);

                if (plan.isSkip()) {
                    log.info("{} шаг {}: модель вернула skip", tc.id(), i + 1);
                    return skipped(tc, enrichments, i, plan.reason());
                }

                if (plan.needsClarification()) {
                    String topicKey = normalizeTopic(plan.topicKey(), plan.question(), plan.reason());

                    if (isSkipped(tc, topicKey) || wasAsked(tc, topicKey)) {
                        log.info("{} шаг {}: тема {} уже задавалась — пропуск кейса",
                                tc.id(), i + 1, topicKey);
                        return skipped(tc, enrichments, i, plan.reason());
                    }

                    Clarification c = new Clarification(
                            tc.id(), i,
                            withTopic(plan.question(), topicKey),
                            plan.reason() != null ? plan.reason() : "Шаг недостаточно конкретен");
                    return new RunnerOutcome(
                            new ExecutionOutcome(tc.id(), tc.sourceFilename(),
                                    "NEEDS_CLARIFICATION",
                                    "Прервано на шаге " + (i + 1) + ": " + c.question(),
                                    enrichments),
                            List.of(c),
                            false);
                }

                try {
                    StepEnrichment e = uiExecutor.executeStep(session, i, plan,
                            uiExecutor.currentUrl(session));
                    enrichments.add(e);
                } catch (UiExecutor.LocatorNotResolved ex) {
                    String topicKey = normalizeTopic(plan.topicKey(), null, ex.getMessage());
                    if (isSkipped(tc, topicKey) || wasAsked(tc, topicKey)) {
                        log.info("{} шаг {}: тема {} уже задавалась — пропуск кейса",
                                tc.id(), i + 1, topicKey);
                        return skipped(tc, enrichments, i, ex.getMessage());
                    }
                    Clarification c = new Clarification(
                            tc.id(), i,
                            withTopic("Шаг " + (i + 1) + " («" + step + "»): " + ex.getMessage(), topicKey),
                            "Не удалось найти элемент на странице");
                    return new RunnerOutcome(
                            new ExecutionOutcome(tc.id(), tc.sourceFilename(),
                                    "NEEDS_CLARIFICATION",
                                    "Прервано на шаге " + (i + 1) + ": " + ex.getMessage(),
                                    enrichments),
                            List.of(c),
                            false);
                }
            }
        } catch (Exception e) {
            log.error("Ошибка выполнения UI-кейса {}", tc.id(), e);
            failure = e.getMessage();
        }

        String status = failure == null ? "PASSED" : "FAILED";
        return new RunnerOutcome(
                new ExecutionOutcome(tc.id(), tc.sourceFilename(), status, failure, enrichments),
                List.of(),
                false);
    }

    private RunnerOutcome runApiCase(ParsedTestCase tc, String apiBaseUrl) {
        List<StepEnrichment> enrichments = new ArrayList<>();

        for (int i = 0; i < tc.steps().size(); i++) {
            String step = tc.steps().get(i);
            StepPlanner.Plan plan = planner.planStep(tc, i, step, null, apiBaseUrl);

            if (plan.isSkip()) {
                log.info("{} шаг {}: модель вернула skip", tc.id(), i + 1);
                return skipped(tc, enrichments, i, plan.reason());
            }

            if (plan.needsClarification()) {
                String topicKey = normalizeTopic(plan.topicKey(), plan.question(), plan.reason());

                if (isSkipped(tc, topicKey) || wasAsked(tc, topicKey)) {
                    log.info("{} шаг {}: тема {} уже задавалась — пропуск кейса",
                            tc.id(), i + 1, topicKey);
                    return skipped(tc, enrichments, i, plan.reason());
                }

                Clarification c = new Clarification(
                        tc.id(), i,
                        withTopic(plan.question(), topicKey),
                        plan.reason() != null ? plan.reason() : "В шаге нет метода/пути");
                return new RunnerOutcome(
                        new ExecutionOutcome(tc.id(), tc.sourceFilename(),
                                "NEEDS_CLARIFICATION",
                                "Прервано на шаге " + (i + 1) + ": " + c.question(),
                                enrichments),
                        List.of(c),
                        false);
            }

            try {
                StepEnrichment e = apiExecutor.executeStep(i, plan, apiBaseUrl, Map.of(), Map.of());
                enrichments.add(e);
            } catch (UiExecutor.LocatorNotResolved ex) {
                String topicKey = normalizeTopic(plan.topicKey(), null, ex.getMessage());
                if (isSkipped(tc, topicKey) || wasAsked(tc, topicKey)) {
                    log.info("{} шаг {}: тема {} уже задавалась — пропуск кейса",
                            tc.id(), i + 1, topicKey);
                    return skipped(tc, enrichments, i, ex.getMessage());
                }
                Clarification c = new Clarification(
                        tc.id(), i,
                        withTopic("Шаг " + (i + 1) + " («" + step + "»): " + ex.getMessage(), topicKey),
                        "Не удалось выполнить API-шаг");
                return new RunnerOutcome(
                        new ExecutionOutcome(tc.id(), tc.sourceFilename(),
                                "NEEDS_CLARIFICATION",
                                "Прервано на шаге " + (i + 1) + ": " + ex.getMessage(),
                                enrichments),
                        List.of(c),
                        false);
            }
        }

        return new RunnerOutcome(
                new ExecutionOutcome(tc.id(), tc.sourceFilename(), "PASSED", null, enrichments),
                List.of(),
                false);
    }

    private RunnerOutcome skipped(ParsedTestCase tc, List<StepEnrichment> enrichments,
                                  int stepIndex, String reason) {
        String msg = "Пропущено по запросу пользователя (SKIP)"
                + (reason == null ? "" : ": " + reason);
        return new RunnerOutcome(
                new ExecutionOutcome(tc.id(), tc.sourceFilename(), "SKIPPED", msg, enrichments),
                List.of(),
                true);
    }

    private String normalizeTopic(String topicKey, String question, String reason) {
        String base = firstNonBlank(topicKey, question, reason);
        if (base == null) base = "unknown";
        String normalized = base.trim().toLowerCase().replaceAll("\\s+", " ");
        return normalized.length() > 40 ? normalized.substring(0, 40) : normalized;
    }

    private String withTopic(String question, String topicKey) {
        if (topicKey == null || topicKey.isBlank()) return question;
        String prefix = "[" + topicKey + "] ";
        return question == null ? prefix.trim() : prefix + question;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private boolean isApiType(String type) {
        return type != null && type.equalsIgnoreCase("API");
    }
}
