package application.controller;

import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.AnswerRequest.Answer;
import application.dto.Dtos.Clarification;
import application.dto.Dtos.DraftRequest;
import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.ParsedTestCase;
import application.dto.Dtos.RunRequest;
import application.dto.Dtos.RunResponse;
import application.dto.Dtos.RunSessionView;
import application.dto.Dtos.RunState;
import application.exception.AssertionFailure;
import application.exception.ConflictException;
import application.exception.FetchException;
import application.repository.ArtifactRepository;
import application.repository.ArtifactRepository.NewArtifact;
import application.repository.ArtifactRepository.StoredArtifact;
import application.repository.ClarificationSessionRepository.SessionRow;
import application.service.EnrichedStorageService;
import application.service.RunSessionService;
import application.service.TestCaseFetcher;
import application.service.TestCaseFetcher.FetchResult;
import application.service.TestCaseFetcher.FetchedArtifact;
import application.service.TestCaseMarkdownRenderer;
import application.service.TestCaseParser;
import application.service.TestCaseRunnerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST-контроллер исполнения тест-кейсов: запуск, ответы на уточнения, состояние сессии и черновики.
 * <p>
 * Эндпоинты: {@code POST /api/run}, {@code POST /api/run/{taskId}/answers}, {@code GET /api/run/{taskId}},
 * {@code PUT /api/run/{taskId}/draft}. Источник кейсов: {@code sourceRunId} (артефакты md-to-test в БД) или
 * {@code sourceUrl} (HTTP). Состояние сессии, ответы и файлы сохраняются в БД; в памяти процесса ничего не хранится.
 */
@Slf4j
@RestController
@RequestMapping("/api/run")
@RequiredArgsConstructor
public class RunController {

    /**
     * Текст сообщения при запуске без источника или с двумя источниками.
     */
    private static final String ONE_SOURCE_REQUIRED = "Укажите ровно одно из полей: sourceRunId или sourceUrl";

    private final TestCaseFetcher fetcher;
    private final TestCaseParser parser;
    private final TestCaseRunnerService runner;
    private final TestCaseMarkdownRenderer renderer;
    private final ArtifactRepository artifacts;
    private final EnrichedStorageService enriched;
    private final RunSessionService sessions;

    /**
     * Запускает прогон и возвращает результат. Источник — ровно один из {@code sourceRunId} и {@code sourceUrl}.
     * <p>
     * При {@code sourceRunId} индекс (артефакт {@code index} с этим run_id) задаёт порядок, а кейсы
     * ({@code test_case}) берутся по именам из индекса. Если индекса или кейса нет, либо он не разобран,
     * прогон не запускается ({@link FetchException}). При {@code sourceUrl} скачанные файлы сохраняются
     * артефактами {@code test_case} с run_id нового прогона.
     *
     * @param request тело запроса
     * @return ответ 200 с {@link RunResponse}
     * @throws IllegalArgumentException заданы оба источника или ни одного, либо идентификатор некорректен (HTTP 400)
     * @throws FetchException           кейсы не удалось получить или разобрать (HTTP 502)
     * @throws ConflictException        не используется при создании прогона (HTTP 409 — только при ответах)
     */
    @PostMapping
    public ResponseEntity<RunResponse> run(@RequestBody RunRequest request) {
        if (request.hasSourceRunId() == request.hasSourceUrl()) {
            throw new IllegalArgumentException(ONE_SOURCE_REQUIRED);
        }
        UUID taskId = UUID.randomUUID();
        List<ParsedTestCase> testCases;
        List<NewArtifact> fetchedFiles = new ArrayList<>();
        String sourceRunId = null;
        String sourceUrl = null;
        if (request.hasSourceRunId()) {
            UUID source = EnrichedStorageService.parseUuid(request.sourceRunId(), "Некорректный sourceRunId");
            sourceRunId = source.toString();
            testCases = loadCasesFromRun(source);
        } else {
            sourceUrl = request.sourceUrl();
            FetchResult fetched = fetcher.fetch(sourceUrl);
            testCases = fetched.cases();
            for (FetchedArtifact f : fetched.files()) {
                fetchedFiles.add(NewArtifact.of(f.kind(), f.filename(), EnrichedStorageService.TEXT_MARKDOWN,
                        f.content().getBytes(StandardCharsets.UTF_8)));
            }
        }

        RunState base = new RunState(testCases, 0, sourceRunId, sourceUrl,
                request.startUrl(), request.apiBaseUrl(), List.of(), null);
        RunExecution exec = executeAll(testCases, request.startUrl(), request.apiBaseUrl(), List.of());
        return complete(taskId, base, exec, new Persist(true, 0, 1, 0, List.of()), fetchedFiles);
    }

    /**
     * Принимает ответы пользователя на уточнения и продолжает прогон с кейса, на котором он остановился.
     * <p>
     * Непустые ответы получают раунд {@code round + 1}. В выполнение передаётся полный набор ответов сессии
     * с дедупликацией по теме и вопросу (побеждает последний). Кейс, на котором остановились, перезапускается
     * целиком; завершённые до остановки кейсы не перезапускаются.
     * <p>
     * Если {@code expectedVersion} передан и не совпадает с текущей версией, либо версия изменилась параллельно,
     * возвращается 409. При успехе версия и раунд увеличиваются на 1.
     *
     * @param taskId  идентификатор прогона из предыдущего ответа
     * @param request ответы и необязательная ожидаемая версия
     * @return ответ 200 с {@link RunResponse}, включая новую версию
     * @throws IllegalArgumentException сессии нет или она истекла; прогон уже завершён (HTTP 400)
     * @throws ConflictException        версия сессии не совпадает (HTTP 409)
     */
    @PostMapping("/{taskId}/answers")
    public ResponseEntity<RunResponse> answer(@PathVariable String taskId, @RequestBody AnswerRequest request) {
        UUID id = parseTaskId(taskId);
        SessionRow session = sessions.requireActive(id);
        if (request.expectedVersion() != null && request.expectedVersion() != session.version()) {
            throw new ConflictException(RunSessionService.CONFLICT_MESSAGE);
        }
        if ("DONE".equals(session.status())) {
            throw new IllegalArgumentException("Прогон уже завершён, уточнения не требуются");
        }
        RunState state = sessions.readState(session.stateJson());
        List<Answer> fresh = RunSessionService.nonEmpty(request.answers());
        log.info("Ответы на уточнения для taskId={}: непустых {}", id, fresh.size());

        List<Answer> all = RunSessionService.dedupe(concat(sessions.historyAnswers(id), fresh));
        List<ParsedTestCase> remaining = state.testCases()
                .subList(state.currentTestCaseIndex(), state.testCases().size());
        RunExecution exec = executeAll(remaining, state.startUrl(), state.apiBaseUrl(), all);
        return complete(id, state, exec,
                new Persist(false, session.version(), session.version() + 1, session.round() + 1, fresh),
                List.of());
    }

    /**
     * Возвращает состояние сессии прогона: статус, версию, раунд, уточнения, историю ответов, черновики
     * и поля последнего ответа.
     *
     * @param taskId идентификатор прогона
     * @return состояние сессии
     * @throws IllegalArgumentException сессии нет или она истекла (HTTP 400)
     */
    @GetMapping("/{taskId}")
    public RunSessionView get(@PathVariable String taskId) {
        return sessions.view(sessions.requireActive(parseTaskId(taskId)));
    }

    /**
     * Перезаписывает черновики ответов целиком. Версия сессии не меняется.
     *
     * @param taskId  идентификатор прогона
     * @param request черновики по индексу уточнения
     * @throws IllegalArgumentException сессии нет или она истекла (HTTP 400)
     */
    @PutMapping("/{taskId}/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void draft(@PathVariable String taskId, @RequestBody DraftRequest request) {
        sessions.updateDraft(parseTaskId(taskId), request == null ? null : request.draft());
    }

    /**
     * Результат выполнения последовательности кейсов.
     *
     * @param outcomes           итоги кейсов до остановки включительно
     * @param clarifications     уточнения, собранные на момент остановки
     * @param stoppedAtCaseIndex индекс кейса в переданном списке, на котором остановились; {@code -1}, если остановки не было
     * @param files              обогащённые файлы кейсов, записанные в этом выполнении
     */
    private record RunExecution(List<ExecutionOutcome> outcomes,
                                List<Clarification> clarifications,
                                int stoppedAtCaseIndex,
                                List<NewArtifact> files) {

        /**
         * Проверяет, была ли остановка на уточнении.
         *
         * @return {@code true}, если остановка была
         */
        boolean stopped() {
            return stoppedAtCaseIndex >= 0;
        }

        /**
         * Возвращает итоги кейсов, полностью завершённых до кейса с уточнением.
         *
         * @return подсписок итогов без остановленного кейса
         */
        List<ExecutionOutcome> completedBeforeStop() {
            return outcomes.subList(0, stoppedAtCaseIndex);
        }
    }

    /**
     * Параметры версии и истории ответов для фиксации запроса.
     *
     * @param create          {@code true} при первом запуске прогона
     * @param expectedVersion версия, которую читал запрос
     * @param version         новая версия
     * @param round           раунд, которым записываются новые ответы
     * @param newAnswers      новые непустые ответы
     */
    private record Persist(boolean create, int expectedVersion, int version, int round, List<Answer> newAnswers) {
    }

    /**
     * Загружает кейсы прогона-источника из БД: порядок задаёт индекс, содержимое берётся по именам файлов.
     *
     * @param sourceRunId taskId генерации (md-to-test)
     * @return кейсы в порядке индекса
     * @throws FetchException индекс не найден, пуст, либо какой-то файл отсутствует или не разобран
     */
    private List<ParsedTestCase> loadCasesFromRun(UUID sourceRunId) {
        List<StoredArtifact> indexes = artifacts.findRunFiles(sourceRunId, "index");
        if (indexes.isEmpty()) {
            throw new FetchException("Индекс тест-кейсов не найден для sourceRunId=" + sourceRunId
                    + ". Прогон не запущен.");
        }
        List<TestCaseParser.IndexEntry> entries = parser.parseIndex(indexes.get(0).contentAsText());
        if (entries.isEmpty()) {
            throw new FetchException("Индекс не содержит ссылок на тест-кейсы: sourceRunId=" + sourceRunId);
        }
        Map<String, StoredArtifact> casesByName = new HashMap<>();
        for (StoredArtifact a : artifacts.findRunFiles(sourceRunId, "test_case")) {
            casesByName.putIfAbsent(a.filename(), a);
        }
        List<ParsedTestCase> result = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (TestCaseParser.IndexEntry entry : entries) {
            String name = parser.toSafeFilename(entry.relativePath());
            if (name == null) {
                problems.add("некорректное имя файла в индексе: `" + entry.relativePath() + "`");
                continue;
            }
            StoredArtifact file = casesByName.get(name);
            if (file == null) {
                problems.add(name + ": файл не найден в артефактах прогона");
                continue;
            }
            try {
                result.add(parser.parse(file.contentAsText(), name));
            } catch (IllegalArgumentException e) {
                problems.add(name + ": " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new FetchException("Не удалось загрузить " + problems.size() + " из " + entries.size()
                    + " тест-кейсов (sourceRunId=" + sourceRunId + "). Прогон не запущен:\n - "
                    + String.join("\n - ", problems));
        }
        return result;
    }

    /**
     * Выполняет кейсы по порядку до первого кейса, который требует уточнения.
     * <p>
     * Каждый кейс выполняется через {@link TestCaseRunnerService} с переданными ответами. Обогащённый файл
     * кейса собирается в {@code files}; запись в БД выполняется при фиксации. Ошибка {@link AssertionFailure}
     * (например, стартовый URL вне allowlist) переводит кейс в {@code FAILED}, прогон продолжается. Остальные
     * исключения прерывают запрос.
     *
     * @param cases     кейсы для выполнения по порядку
     * @param startUrl  стартовый URL веб-приложения; может быть {@code null}
     * @param apiBaseUrl базовый URL API; может быть {@code null}
     * @param answers   полный набор ответов, передаваемый каждому кейсу
     * @return итоги, уточнения, индекс остановки и файлы
     */
    private RunExecution executeAll(List<ParsedTestCase> cases, String startUrl, String apiBaseUrl,
                                    List<Answer> answers) {
        List<ExecutionOutcome> outcomes = new ArrayList<>();
        List<Clarification> clarifications = new ArrayList<>();
        List<NewArtifact> files = new ArrayList<>();

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

            String enrichedMd = renderer.renderEnriched(tc, outcome.enrichments());
            files.add(NewArtifact.of(EnrichedStorageService.KIND_CASE, tc.sourceFilename(),
                    EnrichedStorageService.TEXT_MARKDOWN, enrichedMd.getBytes(StandardCharsets.UTF_8)));
            outcomes.add(outcome);

            if ("NEEDS_CLARIFICATION".equals(outcome.status())) {
                clarifications.addAll(caseClarifications);
                return new RunExecution(outcomes, clarifications, i, files);
            }
        }
        return new RunExecution(outcomes, clarifications, -1, files);
    }

    /**
     * Формирует индекс, собирает состояние и фиксирует запрос в БД одной транзакцией.
     * <p>
     * Статус {@code DONE}, если уточнений нет, иначе {@code NEEDS_CLARIFICATION}. Ссылки на индекс и архив
     * вычисляются до фиксации, поскольку UUID артефакта задан заранее.
     *
     * @param taskId     идентификатор прогона
     * @param base       состояние до запроса
     * @param exec       результат выполнения кейсов в этом запросе
     * @param persist    параметры версии и ответов
     * @param extraFiles дополнительные файлы (скачанные кейсы и индекс источника), если есть
     * @return ответ 200 с {@link RunResponse}
     * @throws ConflictException версия сессии изменена параллельным запросом (HTTP 409)
     */
    private ResponseEntity<RunResponse> complete(UUID taskId, RunState base, RunExecution exec,
                                                 Persist persist, List<NewArtifact> extraFiles) {
        List<ExecutionOutcome> allOutcomes = concat(base.completedOutcomes(), exec.outcomes());
        int nextIndex;
        List<ExecutionOutcome> completed;
        if (exec.stopped()) {
            nextIndex = base.currentTestCaseIndex() + exec.stoppedAtCaseIndex();
            completed = concat(base.completedOutcomes(), exec.completedBeforeStop());
        } else {
            nextIndex = base.testCases().size();
            completed = allOutcomes;
        }
        String status = exec.clarifications().isEmpty() ? "DONE" : "NEEDS_CLARIFICATION";

        NewArtifact index = NewArtifact.of(EnrichedStorageService.KIND_INDEX,
                "_enriched_index_" + taskId + ".md", EnrichedStorageService.TEXT_MARKDOWN,
                renderer.renderIndex(allOutcomes).getBytes(StandardCharsets.UTF_8));
        RunResponse response = new RunResponse(
                taskId.toString(),
                status,
                persist.version(),
                allOutcomes,
                exec.clarifications(),
                enriched.fileUrl(index.id()),
                enriched.archiveUrl(taskId));

        RunState state = new RunState(base.testCases(), nextIndex, base.sourceRunId(), base.sourceUrl(),
                base.startUrl(), base.apiBaseUrl(), completed, response);

        List<NewArtifact> files = new ArrayList<>(extraFiles);
        files.addAll(exec.files());
        files.add(index);
        sessions.commit(new RunSessionService.Commit(taskId, persist.create(), persist.expectedVersion(),
                persist.version(), persist.round(), status, state, exec.clarifications(),
                persist.newAnswers(), files, allOutcomes));
        return ResponseEntity.ok(response);
    }

    /**
     * Разбирает идентификатор сессии. Некорректный UUID считается отсутствующей сессией.
     *
     * @param taskId строка из пути
     * @return UUID прогона
     * @throws IllegalArgumentException строка не является UUID (HTTP 400)
     */
    private static UUID parseTaskId(String taskId) {
        return EnrichedStorageService.parseUuid(taskId, RunSessionService.SESSION_NOT_FOUND);
    }

    /**
     * Объединяет два списка в новый изменяемый список. {@code null}-списки пропускаются.
     *
     * @param first  первый список; может быть {@code null}
     * @param second второй список; может быть {@code null}
     * @param <T>    тип элементов
     * @return новый список
     */
    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        return result;
    }
}
