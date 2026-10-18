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
            List<Clarification> clarifications
    ) {
    }

    public RunnerOutcome runCase(ParsedTestCase tc, String startUrl, String apiBaseUrl) {
        // Если apiBaseUrl пуст, но в ответах есть URL — используем его
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

    private RunnerOutcome runUiCase(ParsedTestCase tc, String startUrl) {
        List<StepEnrichment> enrichments = new ArrayList<>();
        String failure = null;

        try (UiExecutor.UiSession session = uiExecutor.newSession(startUrl)) {
            for (int i = 0; i < tc.steps().size(); i++) {
                String step = tc.steps().get(i);
                String html = uiExecutor.snapshotHtml(session);
                StepPlanner.Plan plan = planner.planStep(tc, i, step, html, null);

                if (plan.needsClarification()) {
                    Clarification c = new Clarification(
                            tc.id(), i,
                            plan.question() != null ? plan.question() : "Недостаточно данных для шага",
                            plan.reason() != null ? plan.reason() : "Шаг недостаточно конкретен");
                    return new RunnerOutcome(
                            new ExecutionOutcome(tc.id(), tc.sourceFilename(),
                                    "NEEDS_CLARIFICATION",
                                    "Прервано на шаге " + (i + 1) + ": " + c.question(),
                                    enrichments),
                            List.of(c));
                }

                try {
                    StepEnrichment e = uiExecutor.executeStep(session, i, plan,
                            uiExecutor.currentUrl(session));
                    enrichments.add(e);
                } catch (UiExecutor.LocatorNotResolved ex) {
                    Clarification c = new Clarification(
                            tc.id(), i,
                            "Шаг " + (i + 1) + " («" + step + "»): " + ex.getMessage(),
                            "Не удалось найти элемент на странице");
                    return new RunnerOutcome(
                            new ExecutionOutcome(tc.id(), tc.sourceFilename(),
                                    "NEEDS_CLARIFICATION",
                                    "Прервано на шаге " + (i + 1) + ": " + ex.getMessage(),
                                    enrichments),
                            List.of(c));
                }
            }
        } catch (Exception e) {
            log.error("Ошибка выполнения UI-кейса {}", tc.id(), e);
            failure = e.getMessage();
        }

        String status = failure == null ? "PASSED" : "FAILED";
        return new RunnerOutcome(
                new ExecutionOutcome(tc.id(), tc.sourceFilename(), status, failure, enrichments),
                List.of());
    }

    private RunnerOutcome runApiCase(ParsedTestCase tc, String apiBaseUrl) {
        List<StepEnrichment> enrichments = new ArrayList<>();

        for (int i = 0; i < tc.steps().size(); i++) {
            String step = tc.steps().get(i);
            StepPlanner.Plan plan = planner.planStep(tc, i, step, null, apiBaseUrl);

            if (plan.needsClarification()) {
                Clarification c = new Clarification(
                        tc.id(), i,
                        plan.question() != null ? plan.question() : "Недостаточно данных для API-шага",
                        plan.reason() != null ? plan.reason() : "В шаге нет метода/пути");
                return new RunnerOutcome(
                        new ExecutionOutcome(tc.id(), tc.sourceFilename(),
                                "NEEDS_CLARIFICATION",
                                "Прервано на шаге " + (i + 1) + ": " + c.question(),
                                enrichments),
                        List.of(c));
            }

            try {
                StepEnrichment e = apiExecutor.executeStep(i, plan, apiBaseUrl, Map.of(), Map.of());
                enrichments.add(e);
            } catch (UiExecutor.LocatorNotResolved ex) {
                Clarification c = new Clarification(
                        tc.id(), i,
                        "Шаг " + (i + 1) + " («" + step + "»): " + ex.getMessage(),
                        "Не удалось выполнить API-шаг");
                return new RunnerOutcome(
                        new ExecutionOutcome(tc.id(), tc.sourceFilename(),
                                "NEEDS_CLARIFICATION",
                                "Прервано на шаге " + (i + 1) + ": " + ex.getMessage(),
                                enrichments),
                        List.of(c));
            }
        }

        return new RunnerOutcome(
                new ExecutionOutcome(tc.id(), tc.sourceFilename(), "PASSED", null, enrichments),
                List.of());
    }

    private boolean isApiType(String type) {
        return type != null && type.equalsIgnoreCase("API");
    }
}
