package application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Контейнер DTO-записей (record) для HTTP-API и обмена между контроллером и сервисами.
 * <p>
 * Все типы неизменяемы. Сериализуются Jackson в JSON с теми же именами полей, что у компонентов записей.
 */
public class Dtos {

    /**
     * Тело запроса {@code POST /api/run}. Нужно ровно одно из полей {@code sourceRunId} и {@code sourceUrl}.
     *
     * @param sourceRunId taskId генерации (md-to-test): кейсы читаются из артефактов БД
     * @param sourceUrl   URL Markdown-источника: файл {@code *.md} или каталог с {@code _index.md}
     * @param startUrl    стартовый URL веб-приложения для UI-шагов; может быть не задан
     * @param apiBaseUrl  базовый URL API для шагов, использующих относительный путь
     */
    public record RunRequest(String sourceRunId, String sourceUrl, String startUrl, String apiBaseUrl) {

        /**
         * Проверяет, что {@code sourceRunId} задан и содержит не только пробелы.
         *
         * @return {@code true}, если поле задано
         */
        public boolean hasSourceRunId() {
            return sourceRunId != null && !sourceRunId.isBlank();
        }

        /**
         * Проверяет, что {@code sourceUrl} задан и содержит не только пробелы.
         *
         * @return {@code true}, если поле задано
         */
        public boolean hasSourceUrl() {
            return sourceUrl != null && !sourceUrl.isBlank();
        }
    }

    /**
     * Тело запроса {@code POST /api/run/{taskId}/answers}.
     *
     * @param answers         ответы пользователя на уточнения; может быть {@code null}
     * @param expectedVersion версия сессии, которую видел пользователь; необязательна. Если задана
     *                        и не совпадает с текущей версией, сервис отвечает 409
     */
    public record AnswerRequest(List<Answer> answers, Integer expectedVersion) {

        /**
         * Ответ пользователя на одно уточнение.
         * <p>
         * Ответ передаётся планировщику при перезапуске кейса. Если планировщик снова запрашивает уточнение по теме, для которой уже есть ответ,
         * шаг пропускается, даже когда ответ отличен от {@code SKIP}. Значение {@code SKIP}
         * (без учёта регистра) пропускает шаг по той же теме.
         *
         * @param question   текст уточнения, на который дан ответ
         * @param answer     текст ответа
         * @param topicKey   ключ темы из {@link Clarification#topicKey()}; если пуст, сопоставление идёт
         *                   по вхождению ключа в {@code question}
         * @param testCaseId идентификатор тест-кейса, к которому относится ответ; может быть {@code null}
         */
        public record Answer(String question, String answer, String topicKey, String testCaseId) {
        }
    }

    /**
     * Тело запроса {@code PUT /api/run/{taskId}/draft}.
     *
     * @param draft черновики ответов по индексу уточнения, например {@code {"0": "текст"}}
     */
    public record DraftRequest(Map<String, String> draft) {
    }

    /**
     * Уточнение, которое сервис запрашивает у пользователя при остановке прогона.
     * <p>
     * Ключ {@code topicKey} клиент должен вернуть в {@link AnswerRequest.Answer} без изменений: сервер
     * сопоставляет ответы по точному совпадению темы, а не по подстроке в {@code question}.
     *
     * @param testCaseId идентификатор тест-кейса, например {@code TC-UI-001}
     * @param stepIndex  индекс шага в кейсе, начиная с 0
     * @param question   текст вопроса; если тема задана, перед ним стоит {@code [topicKey] }
     * @param reason     причина, по которой данных недостаточно
     * @param category   категория: {@code DATA_MISSING} или {@code LOCATOR_NOT_FOUND}
     * @param topicKey   нормализованный ключ темы (строчные латинские буквы, цифры и {@code _},
     *                   не длиннее 40 символов)
     */
    public record Clarification(
            String testCaseId,
            Integer stepIndex,
            String question,
            String reason,
            String category,
            String topicKey
    ) {

        /**
         * Создаёт уточнение без ключа темы: {@code topicKey} остаётся {@code null}.
         *
         * @param testCaseId идентификатор тест-кейса
         * @param stepIndex  индекс шага, начиная с 0
         * @param question   текст вопроса
         * @param reason     причина
         * @param category   категория уточнения
         */
        public Clarification(String testCaseId, Integer stepIndex, String question,
                             String reason, String category) {
            this(testCaseId, stepIndex, question, reason, category, null);
        }
    }

    /**
     * Сведения об элементе страницы, найденном для UI-шага.
     *
     * @param index             порядковый номер элемента в шаге, начиная с 0
     * @param locator           локатор Playwright, которым элемент найден
     * @param xpath             XPath элемента, вычисленный в браузере; может быть {@code null}
     * @param tag               имя тега в нижнем регистре
     * @param testidOrId        значение {@code data-testid} (при его отсутствии {@code data-test-id},
     *                          затем {@code data-test}); если таких атрибутов нет, значение {@code id}
     * @param role              значение атрибута {@code role}
     * @param ariaLabel         значение атрибута {@code aria-label}
     * @param type              значение атрибута {@code type}
     * @param text              видимый текст элемента с нормализованными пробелами
     * @param count             число элементов, совпавших с локатором на момент поиска
     * @param alternativesCss   альтернативные CSS-локаторы из плана
     * @param alternativesXpath альтернативные XPath-локаторы, включая вычисленный XPath
     */
    public record ElementInfo(
            int index,
            String locator,
            String xpath,
            String tag,
            String testidOrId,
            String role,
            String ariaLabel,
            String type,
            String text,
            Integer count,
            List<String> alternativesCss,
            List<String> alternativesXpath
    ) {
    }

    /**
     * Обогащение шага: что было сделано и какие данные получены. Обогащения попадают в БД и в
     * обогащённые Markdown-файлы.
     *
     * @param stepIndex      индекс шага в кейсе, начиная с 0
     * @param action         имя операции, например {@code navigate}, {@code click}, {@code apiCall},
     *                       {@code skip}, {@code planning-error} или {@code type-mismatch}
     * @param detail         многострочное описание результата
     * @param elements       найденные UI-элементы; пустой список для операций без элементов
     * @param requestCurl    эквивалентная команда {@code curl} для API-вызова; может быть {@code null}
     * @param requestBody    тело запроса API-вызова; может быть {@code null}
     * @param responseSample фрагмент тела ответа API; может быть {@code null}
     * @param subEnrichments вложенные обогащения
     */
    public record StepEnrichment(
            int stepIndex,
            String action,
            String detail,
            List<ElementInfo> elements,
            String requestCurl,
            String requestBody,
            String responseSample,
            List<StepEnrichment> subEnrichments
    ) {

        /**
         * Создаёт обогащение без элементов и без данных API.
         *
         * @param stepIndex индекс шага, начиная с 0
         * @param action    имя операции
         * @param detail    описание результата
         */
        public StepEnrichment(int stepIndex, String action, String detail) {
            this(stepIndex, action, detail, List.of(), null, null, null, List.of());
        }

        /**
         * Создаёт обогащение UI-операции.
         *
         * @param stepIndex индекс шага, начиная с 0
         * @param action    имя операции
         * @param detail    описание результата
         * @param elements  найденные элементы страницы
         * @return обогащение без данных API и без вложенных обогащений
         */
        public static StepEnrichment ui(int stepIndex, String action, String detail,
                                        List<ElementInfo> elements) {
            return new StepEnrichment(stepIndex, action, detail, elements,
                    null, null, null, List.of());
        }

        /**
         * Создаёт обогащение API-операции без вложенных обогащений.
         *
         * @param stepIndex      индекс шага, начиная с 0
         * @param action         имя операции
         * @param detail         описание результата
         * @param requestCurl    команда {@code curl} для запроса; может быть {@code null}
         * @param requestBody    тело запроса; может быть {@code null}
         * @param responseSample фрагмент ответа; может быть {@code null}
         * @return обогащение без UI-элементов
         */
        public static StepEnrichment api(int stepIndex, String action, String detail,
                                         String requestCurl, String requestBody,
                                         String responseSample) {
            return new StepEnrichment(stepIndex, action, detail, List.of(),
                    requestCurl, requestBody, responseSample, List.of());
        }

        /**
         * Создаёт обогащение API-операции с вложенными обогащениями.
         *
         * @param stepIndex      индекс шага, начиная с 0
         * @param action         имя операции
         * @param detail         описание результата
         * @param requestCurl    команда {@code curl} для запроса; может быть {@code null}
         * @param requestBody    тело запроса; может быть {@code null}
         * @param responseSample фрагмент ответа; может быть {@code null}
         * @param subs           вложенные обогащения; {@code null} заменяется пустым списком
         * @return обогащение с переданными вложенными обогащениями
         */
        public static StepEnrichment apiWithSub(int stepIndex, String action, String detail,
                                                String requestCurl, String requestBody,
                                                String responseSample,
                                                List<StepEnrichment> subs) {
            return new StepEnrichment(stepIndex, action, detail, List.of(),
                    requestCurl, requestBody, responseSample,
                    subs == null ? List.of() : subs);
        }
    }

    /**
     * Итог исполнения одного тест-кейса.
     *
     * @param testCaseId     идентификатор тест-кейса
     * @param filename       имя файла тест-кейса в источнике
     * @param status         статус: {@code PASSED}, {@code FAILED}, {@code SKIPPED} или
     *                       {@code NEEDS_CLARIFICATION}
     * @param failureMessage причина провала для {@code FAILED}, пояснение для {@code SKIPPED} и
     *                       {@code NEEDS_CLARIFICATION}; для {@code PASSED} равно {@code null}
     * @param enrichments    обогащения шагов кейса
     */
    public record ExecutionOutcome(
            String testCaseId,
            String filename,
            String status,
            String failureMessage,
            List<StepEnrichment> enrichments
    ) {
    }

    /**
     * Ответ на {@code POST /api/run} и {@code POST /api/run/{taskId}/answers}.
     *
     * @param taskId           идентификатор прогона (UUID), который передаётся в URL ответов на уточнения
     * @param status           статус прогона: {@code DONE} или {@code NEEDS_CLARIFICATION}
     * @param version          версия сессии после этого ответа
     * @param outcomes         итоги всех кейсов прогона, включая кейсы до остановки
     * @param clarifications   уточнения, на которые нужно ответить; пусто при {@code DONE}
     * @param enrichedIndexUrl ссылка на индекс обогащённых кейсов прогона (скачивание по UUID артефакта)
     * @param archiveUrl       ссылка на ZIP со всеми обогащёнными файлами прогона
     */
    public record RunResponse(
            String taskId,
            String status,
            int version,
            List<ExecutionOutcome> outcomes,
            List<Clarification> clarifications,
            String enrichedIndexUrl,
            String archiveUrl
    ) {
    }

    /**
     * Ответ на ранее данный ответ пользователя, как он хранится в истории сессии.
     *
     * @param round      номер раунда ответов
     * @param testCaseId идентификатор тест-кейса; может быть {@code null}
     * @param question   текст уточнения
     * @param topicKey   ключ темы; может быть {@code null}
     * @param answer     текст ответа
     * @param answeredAt время ответа
     */
    public record AnswerView(int round, String testCaseId, String question, String topicKey,
                             String answer, Instant answeredAt) {
    }

    /**
     * Ответ {@code GET /api/run/{taskId}}: состояние сессии плюс поля последнего ответа прогона.
     *
     * @param taskId           идентификатор прогона
     * @param status           {@code DONE} или {@code NEEDS_CLARIFICATION}
     * @param version          версия сессии
     * @param round            номер последнего раунда ответов
     * @param outcomes         итоги кейсов из последнего ответа
     * @param clarifications   уточнения из последнего ответа
     * @param answers          история ответов сессии
     * @param draft            черновики ответов по индексу уточнения
     * @param expiresAt        время истечения сессии
     * @param enrichedIndexUrl ссылка на индекс обогащённых кейсов
     * @param archiveUrl       ссылка на архив прогона
     */
    public record RunSessionView(
            String taskId,
            String status,
            int version,
            int round,
            List<ExecutionOutcome> outcomes,
            List<Clarification> clarifications,
            List<AnswerView> answers,
            Map<String, String> draft,
            Instant expiresAt,
            String enrichedIndexUrl,
            String archiveUrl
    ) {
    }

    /**
     * Внутреннее состояние прогона, которое хранится в {@code qa.clarification_sessions.state} (JSON).
     *
     * @param testCases            все кейсы прогона в порядке выполнения
     * @param currentTestCaseIndex индекс кейса, с которого продолжится выполнение
     * @param sourceRunId          taskId генерации, если прогон создан по {@code sourceRunId}
     * @param sourceUrl            URL источника, если прогон создан по {@code sourceUrl}
     * @param startUrl             стартовый URL веб-приложения
     * @param apiBaseUrl           базовый URL API
     * @param completedOutcomes    итоги кейсов, завершённых до {@code currentTestCaseIndex}
     * @param lastResponse         последний ответ сервиса по этому прогону
     */
    public record RunState(
            List<ParsedTestCase> testCases,
            int currentTestCaseIndex,
            String sourceRunId,
            String sourceUrl,
            String startUrl,
            String apiBaseUrl,
            List<ExecutionOutcome> completedOutcomes,
            RunResponse lastResponse
    ) {
    }

    /**
     * Тест-кейс, разобранный из Markdown.
     *
     * @param id             идентификатор, например {@code TC-UI-001}
     * @param type           тип: {@code UI}, {@code API}, {@code E2E}, {@code NEGATIVE},
     *                       {@code SECURITY} или {@code INVALID} (тип не распознан)
     * @param title          заголовок кейса
     * @param requirementRef ссылка на требование
     * @param preconditions  предусловия
     * @param steps          шаги в виде текста
     * @param expectedResult ожидаемый результат
     * @param priority       приоритет
     * @param sourceFilename имя файла, из которого разобран кейс
     * @param rawMarkdown    исходный Markdown кейса
     * @param answers        ответы пользователя, переданные при перезапуске кейса
     */
    public record ParsedTestCase(
            String id,
            String type,
            String title,
            String requirementRef,
            List<String> preconditions,
            List<String> steps,
            String expectedResult,
            String priority,
            String sourceFilename,
            String rawMarkdown,
            List<AnswerRequest.Answer> answers
    ) {
    }
}
