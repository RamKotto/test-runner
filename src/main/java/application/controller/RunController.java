package application.controller;

import application.dto.Dtos.*;
import application.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/run")
@RequiredArgsConstructor
public class RunController {

    private final TestCaseFetcher fetcher;
    private final TestCaseRunnerService runner;
    private final TestCaseMarkdownRenderer renderer;
    private final EnrichedStorageService storage;
    private final SessionStore sessions;

    @PostMapping
    public ResponseEntity<RunResponse> run(@RequestBody RunRequest request) {
        if (!request.hasSourceUrl()) {
            throw new IllegalArgumentException("sourceUrl обязателен");
        }
        List<ParsedTestCase> testCases = fetcher.fetchAll(request.sourceUrl());

        RunExecution exec = executeAll(testCases, request.startUrl(), request.apiBaseUrl(),
                List.of());

        String taskId = UUID.randomUUID().toString();
        String status = exec.clarifications.isEmpty() ? "DONE" : "NEEDS_CLARIFICATION";

        if (!exec.clarifications.isEmpty()) {
            sessions.put(taskId, new SessionStore.Session(
                    testCases, request.startUrl(), request.apiBaseUrl(),
                    exec.stoppedAtCaseIndex, Instant.now(), List.of()));
        }

        String indexUrl = saveIndexAndReturnUrl(exec.outcomes);
        return ResponseEntity.ok(new RunResponse(taskId, status, exec.outcomes,
                exec.clarifications, indexUrl));
    }

    @PostMapping("/{taskId}/answers")
    public ResponseEntity<RunResponse> answer(@PathVariable String taskId,
                                              @RequestBody AnswerRequest request) {
        SessionStore.Session session = sessions.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("Сессия не найдена или истекла");
        }
        log.info("Ответы на уточнения для taskId={}: {}", taskId,
                request.answers() == null ? 0 : request.answers().size());

        // Накопленные ответы: старые + новые
        List<AnswerRequest.Answer> allAnswers = new ArrayList<>(
                session.answers() == null ? List.of() : session.answers());
        if (request.answers() != null) {
            allAnswers.addAll(request.answers());
        }

        // Продолжаем с сохранённого индекса
        List<ParsedTestCase> remaining = session.testCases()
                .subList(session.currentTestCaseIndex(), session.testCases().size());

        RunExecution exec = executeAll(remaining, session.startUrl(), session.apiBaseUrl(),
                allAnswers);
        String status = exec.clarifications.isEmpty() ? "DONE" : "NEEDS_CLARIFICATION";

        if (exec.clarifications.isEmpty()) {
            sessions.remove(taskId);
        } else {
            int absoluteIndex = session.currentTestCaseIndex() + exec.stoppedAtCaseIndex;
            sessions.put(taskId, new SessionStore.Session(
                    session.testCases(), session.startUrl(), session.apiBaseUrl(),
                    absoluteIndex, Instant.now(), allAnswers));
        }

        String indexUrl = saveIndexAndReturnUrl(exec.outcomes);
        return ResponseEntity.ok(new RunResponse(taskId, status, exec.outcomes,
                exec.clarifications, indexUrl));
    }

    private record RunExecution(List<ExecutionOutcome> outcomes,
                                List<Clarification> clarifications,
                                int stoppedAtCaseIndex) {
    }

    private RunExecution executeAll(List<ParsedTestCase> cases, String startUrl, String apiBaseUrl,
                                    List<AnswerRequest.Answer> answers) {
        List<ExecutionOutcome> outcomes = new ArrayList<>();
        List<Clarification> clarifications = new ArrayList<>();
        int stoppedAt = 0;

        for (int i = 0; i < cases.size(); i++) {
            ParsedTestCase tc = cases.get(i);

            // Обогащаем кейс ответами (заменяем answers на накопленные)
            ParsedTestCase tcWithAnswers = new ParsedTestCase(
                    tc.id(), tc.type(), tc.title(), tc.requirementRef(),
                    tc.preconditions(), tc.steps(), tc.expectedResult(),
                    tc.priority(), tc.sourceFilename(), tc.rawMarkdown(),
                    answers);

            var outcome = runner.runCase(tcWithAnswers, startUrl, apiBaseUrl);

            // Обогащённый MD сохраняется всегда, обогащение строго под шагами
            String enriched = renderer.renderEnriched(tc, outcome.outcome().enrichments());
            storage.save(tc.sourceFilename(), enriched);

            outcomes.add(outcome.outcome());

            if ("NEEDS_CLARIFICATION".equals(outcome.outcome().status())) {
                clarifications.addAll(outcome.clarifications());
                stoppedAt = i;
                return new RunExecution(outcomes, clarifications, stoppedAt);
            }
        }
        return new RunExecution(outcomes, clarifications, stoppedAt);
    }

    private String saveIndexAndReturnUrl(List<ExecutionOutcome> outcomes) {
        String indexMd = renderer.renderIndex(outcomes);
        var saved = storage.save("_enriched_index.md", indexMd);
        return saved.downloadUrl();
    }
}
