package application.controller;

import application.dto.Dtos.*;
import application.exception.AssertionFailure;
import application.repository.RunRepository;
import application.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * REST-контроллер исполнения тест-кейсов: {@code POST /api/run} и {@code POST /api/run/{taskId}/answers}.
 * <p>
 * Контроллер оркестрирует прогон: скачивает кейсы ({@link TestCaseFetcher}), исполняет их по порядку
 * ({@link TestCaseRunnerService}), записывает обогащённые файлы и индекс ({@link EnrichedStorageService},
 * {@link TestCaseMarkdownRenderer}), хранит состояние остановленного прогона ({@link SessionStore}) и
 * записывает результат в БД ({@link RunRepository}).
 */
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
    private final RunRepository runRepository;

    /**
     * Запускает прогон: скачивает тест-кейсы по {@code sourceUrl}, исполняет их по порядку и возвращает результат.
     * <p>
     * Если прогон остановлен уточнением, его состояние сохраняется в {@link SessionStore} под новым taskId,
     * а статус ответа — {@code NEEDS_CLARIFICATION}. Результат всегда записывается в БД.
     *
     * @param request тело запроса; поле {@code sourceUrl} обязательно
     * @return ответ 200 с {@link RunResponse}
     * @throws IllegalArgumentException если {@code sourceUrl} пуст или прямая ссылка на {@code .md} указывает
     *                                  на недопустимый хост или некорректный URL (HTTP 400)
     * @throws application.exception.FetchException если тест-кейсы не удалось получить или разобрать,
     *                                              в том числе для каталога и {@code _index.md} (HTTP 502)
     * @throws application.exception.StorageException при ошибке записи обогащённых файлов (HTTP 500)
     */
    @PostMapping
    public ResponseEntity<RunResponse> run(@RequestBody RunRequest request) {
        if (!request.hasSourceUrl()) {
            throw new IllegalArgumentException("sourceUrl обязателен");
        }
        String taskId = UUID.randomUUID().toString();
        List<ParsedTestCase> testCases = fetcher.fetchAll(request.sourceUrl());

        RunExecution exec = executeAll(testCases, request.startUrl(), request.apiBaseUrl(), List.of());

        if (exec.stopped()) {
            sessions.put(taskId, new SessionStore.Session(
                    testCases, request.sourceUrl(), request.startUrl(), request.apiBaseUrl(),
                    exec.stoppedAtCaseIndex(), Instant.now(), List.of(),
                    exec.completedBeforeStop()));
        }
        return respond(taskId, request.sourceUrl(), request.startUrl(), request.apiBaseUrl(),
                exec.outcomes(), exec.clarifications());
    }

    /**
     * Принимает ответы пользователя на уточнения и продолжает прогон с кейса, на котором он остановился.
     * <p>
     * Кейс, на котором остановились, перезапускается целиком с шага 0, и ему передаются все накопленные ответы.
     * Кейсы, завершённые до остановки, не перезапускаются: их результаты переносятся в ответ. Осознанное
     * следствие: API-вызовы {@code POST}, {@code PUT} и {@code DELETE} этого кейса выполняются повторно.
     * Если прогон снова остановлен, состояние сохраняется под тем же taskId, иначе сессия удаляется.
     * <p>
     * Ответ содержит полный набор итогов прогона, а не только новые.
     *
     * @param taskId  идентификатор прогона из предыдущего ответа
     * @param request ответы пользователя; {@code answers} может быть {@code null}
     * @return ответ 200 с {@link RunResponse}
     * @throws IllegalArgumentException если сессия с таким taskId не найдена или истекла (HTTP 400)
     */
    @PostMapping("/{taskId}/answers")
    public ResponseEntity<RunResponse> answer(@PathVariable String taskId,
                                              @RequestBody AnswerRequest request) {
        SessionStore.Session session = sessions.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("Сессия не найдена или истекла");
        }
        log.info("Ответы на уточнения для taskId={}: {}", taskId,
                request.answers() == null ? 0 : request.answers().size());

        List<AnswerRequest.Answer> allAnswers = new ArrayList<>(
                session.answers() == null ? List.of() : session.answers());
        if (request.answers() != null) {
            allAnswers.addAll(request.answers());
        }

        List<ParsedTestCase> remaining = session.testCases()
                .subList(session.currentTestCaseIndex(), session.testCases().size());
        RunExecution exec = executeAll(remaining, session.startUrl(), session.apiBaseUrl(), allAnswers);

        List<ExecutionOutcome> allOutcomes = concat(session.completedOutcomes(), exec.outcomes());

        if (exec.stopped()) {
            sessions.put(taskId, new SessionStore.Session(
                    session.testCases(), session.sourceUrl(), session.startUrl(), session.apiBaseUrl(),
                    session.currentTestCaseIndex() + exec.stoppedAtCaseIndex(),
                    Instant.now(), allAnswers,
                    concat(session.completedOutcomes(), exec.completedBeforeStop())));
        } else {
            sessions.remove(taskId);
        }
        return respond(taskId, session.sourceUrl(), session.startUrl(), session.apiBaseUrl(),
                allOutcomes, exec.clarifications());
    }

    /**
     * Итог выполнения последовательности кейсов.
     * <p>
     * Элемент {@code outcomes[i]} соответствует кейсу {@code cases[i]} переданного списка. Поскольку
     * выполнение останавливается на кейсе с уточнением, {@code outcomes} содержит только кейсы до него включительно.
     *
     * @param outcomes             итоги выполненных кейсов
     * @param clarifications       уточнения, собранные на момент остановки
     * @param stoppedAtCaseIndex   индекс кейса в переданном списке, на котором остановились; {@code -1}, если остановки не было
     */
    private record RunExecution(List<ExecutionOutcome> outcomes,
                                List<Clarification> clarifications,
                                int stoppedAtCaseIndex) {

        /**
         * Проверяет, была ли остановка на уточнении.
         *
         * @return {@code true}, если {@code stoppedAtCaseIndex} не отрицательный
         */
        boolean stopped() {
            return stoppedAtCaseIndex >= 0;
        }

        /**
         * Возвращает итоги кейсов, полностью завершённых до кейса с уточнением.
         *
         * @return подсписок {@code outcomes} без последнего элемента, соответствующего остановленному кейсу
         */
        List<ExecutionOutcome> completedBeforeStop() {
            return outcomes.subList(0, stoppedAtCaseIndex);
        }
    }

    /**
     * Выполняет кейсы по порядку до первого кейса, который требует уточнения.
     * <p>
     * Каждый кейс выполняется через {@link TestCaseRunnerService} с полным списком накопленных ответов;
     * обогащённый файл кейса записывается под его исходным именем. Ошибка {@link AssertionFailure}
     * (например, стартовый URL вне {@code app.target.allowed-hosts}) переводит кейс в {@code FAILED},
     * а прогон продолжается. Статусы {@code SKIPPED} и {@code FAILED} прогон не прерывают. Остальные
     * исключения из выполнения кейса прерывают весь запрос.
     * <p>
     * Осознанное ограничение: кейс, на котором остановились, при ответе перезапускается целиком, поэтому его
     * API-вызовы с побочными эффектами (POST, PUT, DELETE) выполняются повторно. Кейсы, завершённые до
     * уточнения, не перезапускаются. Обогащённый файл такого кейса перезаписывается полной историей
     * перезапущенного кейса.
     *
     * @param cases     кейсы для выполнения по порядку
     * @param startUrl  стартовый URL веб-приложения; может быть {@code null}
     * @param apiBaseUrl базовый URL API; может быть {@code null}
     * @param answers   накопленные ответы пользователя, передаваемые каждому кейсу
     * @return итоги выполненных кейсов и индекс остановки
     */
    private RunExecution executeAll(List<ParsedTestCase> cases, String startUrl, String apiBaseUrl,
                                    List<AnswerRequest.Answer> answers) {
        List<ExecutionOutcome> outcomes = new ArrayList<>();
        List<Clarification> clarifications = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            ParsedTestCase tc = cases.get(i);

            ParsedTestCase tcWithAnswers = new ParsedTestCase(
                    tc.id(), tc.type(), tc.title(), tc.requirementRef(),
                    tc.preconditions(), tc.steps(), tc.expectedResult(),
                    tc.priority(), tc.sourceFilename(), tc.rawMarkdown(),
                    answers);

            ExecutionOutcome outcome;
            List<Clarification> caseClarifications = List.of();
            try {
                TestCaseRunnerService.RunnerOutcome result =
                        runner.runCase(tcWithAnswers, startUrl, apiBaseUrl);
                outcome = result.outcome();
                caseClarifications = result.clarifications();
            } catch (AssertionFailure e) {
                log.warn("Кейс {} завершён с ошибкой: {}", tc.id(), e.getMessage());
                outcome = new ExecutionOutcome(tc.id(), tc.sourceFilename(), "FAILED",
                        e.getMessage(), List.of());
            }

            String enriched = renderer.renderEnriched(tc, outcome.enrichments());
            storage.save(tc.sourceFilename(), enriched);

            outcomes.add(outcome);

            if ("NEEDS_CLARIFICATION".equals(outcome.status())) {
                clarifications.addAll(caseClarifications);
                return new RunExecution(outcomes, clarifications, i);
            }
        }
        return new RunExecution(outcomes, clarifications, -1);
    }

    /**
     * Формирует ответ прогона, сохраняет индексный файл и записывает прогон в БД.
     * <p>
     * Статус {@code DONE}, если уточнений нет, иначе {@code NEEDS_CLARIFICATION}. Ошибка записи в БД
     * не перехватывается: запрос завершится с HTTP 500.
     *
     * @param taskId         идентификатор прогона
     * @param sourceUrl      URL источника тест-кейсов
     * @param startUrl       стартовый URL веб-приложения; может быть {@code null}
     * @param apiBaseUrl     базовый URL API; может быть {@code null}
     * @param outcomes       итоги кейсов
     * @param clarifications уточнения прогона
     * @return ответ 200 с {@link RunResponse}
     */
    private ResponseEntity<RunResponse> respond(String taskId, String sourceUrl, String startUrl,
                                                String apiBaseUrl, List<ExecutionOutcome> outcomes,
                                                List<Clarification> clarifications) {
        String status = clarifications.isEmpty() ? "DONE" : "NEEDS_CLARIFICATION";
        String indexUrl = saveIndexAndReturnUrl(outcomes, taskId);
        runRepository.replaceRun(UUID.fromString(taskId), status, sourceUrl, startUrl, apiBaseUrl,
                outcomes, clarifications);
        return ResponseEntity.ok(new RunResponse(taskId, status, outcomes, clarifications, indexUrl));
    }

    /**
     * Записывает индекс обогащённых кейсов прогона и возвращает его download-ссылку.
     * <p>
     * Имя файла {@code _enriched_index_<taskId>.md} уникально для прогона, поэтому параллельные прогоны
     * не перезаписывают индексы друг друга.
     *
     * @param outcomes итоги кейсов, для которых строится индекс
     * @param taskId   идентификатор прогона
     * @return URL сохранённого индекса
     */
    private String saveIndexAndReturnUrl(List<ExecutionOutcome> outcomes, String taskId) {
        String indexMd = renderer.renderIndex(outcomes);
        var saved = storage.save("_enriched_index_" + taskId + ".md", indexMd);
        return saved.downloadUrl();
    }

    /**
     * Объединяет два списка в новый изменяемый список. {@code null}-списки пропускаются.
     *
     * @param first  первый список; может быть {@code null}
     * @param second второй список; может быть {@code null}
     * @param <T>    тип элементов
     * @return новый список, содержащий элементы {@code first}, затем {@code second}
     */
    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        return result;
    }
}
