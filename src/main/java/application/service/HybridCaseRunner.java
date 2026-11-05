package application.service;

import application.dto.Dtos.Clarification;
import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.ParsedTestCase;
import application.dto.Dtos.StepEnrichment;
import application.exception.AssertionFailure;
import application.exception.GenerationException;
import application.exception.TargetNotAllowedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Оркестратор выполнения одного тест-кейса.
 * <p>
 * Место в конвейере: {@link TestCaseRunnerService} вызывает {@link #run} для каждого
 * кейса. Для каждого шага оркестратор запрашивает план у {@link StepPlanner},
 * направляет план в {@link UiExecutor} или {@link ApiExecutor}, хранит состояние кейса
 * (тело и заголовки последнего ответа, статус, снимки, браузерную сессию) и собирает
 * итоговый {@link ExecutionOutcome}.
 * <p>
 * Статусы итога:
 * <ul>
 *   <li>{@code NEEDS_CLARIFICATION}: шаг требует ответа пользователя; выполнение
 *       прерывается сразу, провалы, накопленные до этого, в статус не попадают;</li>
 *   <li>{@code FAILED}: есть хотя бы один провал, но кейс прошёл до конца;</li>
 *   <li>{@code SKIPPED}: в отчёте есть записи и все они пропуски (action {@code skip}):
 *       по ответам пользователя, каскадные или выбранные планировщиком. Текст итога
 *       при этом всегда говорит «по ответам пользователя»;</li>
 *   <li>{@code PASSED}: иначе.</li>
 * </ul>
 * Тип кейса определяет допустимые операции: {@code API} не допускает UI-операции,
 * {@code UI} не допускает API-операции, для остальных типов допустимы обе группы.
 * Тип {@code null} трактуется как {@code UI}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridCaseRunner {

    /**
     * Планировщик, который по тексту шага строит список планов через LLM.
     */
    private final StepPlanner planner;

    /**
     * Исполнитель UI-операций на базе Playwright.
     */
    private final UiExecutor uiExecutor;

    /**
     * Исполнитель API-операций на базе RestAssured.
     */
    private final ApiExecutor apiExecutor;

    /**
     * Признаки того, что ПОСЛЕДУЮЩИЙ шаг сравнивает результат с предыдущим (сравн, идентичн,
     * совпад, оба ответ, не измен, равн; поиск без учёта регистра). Используется
     * {@link #autoSnapshotAfterApiCall}, который после успешного apiCall на текущем шаге
     * делает snapshotApiValue.
     */
    private static final Pattern COMPARE_HINT = Pattern.compile(
            "сравн|идентичн|совпад|оба ответ|не измен|равн",
            Pattern.CASE_INSENSITIVE);

    /**
     * Ищет в тексте шага первый идентификатор с точкой вида {@code hourly.temperature_2m}.
     * Найденная строка становится кандидатом на jsonPath снимка.
     */
    private static final Pattern JSON_PATH_HINT = Pattern.compile(
            "([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Маппер для разбора тела ответа при подборе пути автоматического снимка.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Итог выполнения кейса.
     *
     * @param outcome             итоговая запись кейса для отчёта
     * @param clarifications      вопросы пользователю; пусто, если уточнения не требуются
     * @param stoppedAtStepIndex  индекс шага (с 0), на котором прервано выполнение;
     *                            при полном проходе равен числу шагов кейса
     */
    public record Result(
            ExecutionOutcome outcome,
            List<Clarification> clarifications,
            int stoppedAtStepIndex
    ) {
    }

    /**
     * Результат API-операции, который передаётся в цикл шагов.
     *
     * @param enrichment запись для отчёта
     * @param apiResult  результат apiCall-подобного шага, из которого обновляется состояние
     *                   последнего ответа; {@code null} для операций снимков, которые
     *                   состояние не меняют
     */
    private record ApiOpResult(StepEnrichment enrichment,
                               ApiExecutor.ApiStepResult apiResult) {
    }

    /**
     * Выполняет тест-кейс с первого шага. Делегирует в {@link #run(ParsedTestCase, String, String, int)}
     * с {@code startStep = 0}.
     *
     * @param tc         разобранный тест-кейс
     * @param startUrl   стартовый URL для UI-шагов; может быть {@code null}
     * @param apiBaseUrl базовый URL API; может быть {@code null}
     * @return итог выполнения кейса
     */
    public Result run(ParsedTestCase tc, String startUrl, String apiBaseUrl) {
        return run(tc, startUrl, apiBaseUrl, 0);
    }

    /**
     * Выполняет тест-кейс, начиная с указанного шага.
     * <p>
     * Состояние предыдущих шагов не восстанавливается: при {@code startStep > 0}
     * тело и заголовки последнего ответа, статус, снимки и браузерная сессия пусты: API-проверки
     * пропускаются каскадно (нет данных apiCall), сравнения со снимком падают, а первый UI-шаг
     * открывает новую сессию через {@link #resolveOrClarifyStartUrl}.
     * <p>
     * Шаг обрабатывается так:
     * <ol>
     *   <li>План строится через {@link StepPlanner#planStep}. Если LLM не справилась
     *       ({@link GenerationException}), в отчёт пишется ошибка планирования, шаг
     *       считается проваленным, выполнение продолжается.</li>
     *   <li>План с признаком пропуска записывается как {@code skip}. Пропуск apiCall
     *       сбрасывает состояние последнего ответа и признак доступных API-данных.</li>
     *   <li>План, требующий уточнения, пропускается каскадно, если предыдущий apiCall
     *       заблокирован хостом (apiHostBlocked). Иначе пропускается, если на его тему
     *       уже есть ответ или {@code SKIP}. Иначе выполнение прерывается с
     *       {@code NEEDS_CLARIFICATION}.</li>
     *   <li>API-проверка (тело, заголовки, статус, parseJson, сравнение со снимком) без
     *       признака доступных API-данных пропускается как каскадная. Ветка
     *       {@code type-mismatch} (операция вне допустимых для типа групп) срабатывает
     *       только при типе {@code null}: обычно такой план уже отклоняет
     *       {@link StepPlanner.Plan#validateAgainst}, и шаг получает {@code planning-error}.</li>
     *   <li>UI-операции выполняются в сессии, которая создаётся при первом UI-шаге
     *       через {@link #resolveOrClarifyStartUrl} и {@link UiExecutor#newSession}.
     *       Провал {@link AssertionFailure} и {@link UiExecutor.UnsupportedUiOperation}
     *       даёт FAILED, {@link UiExecutor.LocatorNotResolved} даёт уточнение (или
     *       пропуск, если на тему уже есть ответ), прочие исключения — FAILED.
     *       Исключения из {@code newSession} в этот блок не попадают и выходят из метода.</li>
     *   <li>API-операции выполняются через {@link #executeApiOp}. Запрет хоста
     *       ({@link TargetNotAllowedException}) — провал, а не уточнение; после него
     *       планы с {@code needClarification} пропускаются каскадно. Ошибка apiCall,
     *       похожая на ошибку конфигурации ({@link #isConfigError}), даёт уточнение по теме
     *       {@code api_base_url}. Перед каждым apiCall состояние последнего ответа
     *       (тело, заголовки, статус, признак доступных API-данных) сбрасывается, поэтому
     *       неуспешный apiCall не оставляет устаревший ответ: следующие API-проверки
     *       пропускаются каскадно («нет данных от предыдущего apiCall»). Успешный apiCall
     *       заполняет состояние заново. После успешного apiCall, если в будущих шагах есть
     *       признак сравнения, делается автоматический снимок ({@link #autoSnapshotAfterApiCall}).</li>
     *   <li>Неизвестная операция даёт провал {@code unknown-op}.</li>
     * </ol>
     * После всех шагов итог определяется по накопленным провалам и пропускам (см. класс).
     * Браузерная сессия закрывается в {@code finally}.
     *
     * @param tc         разобранный тест-кейс; его тип определяет допустимые операции
     * @param startUrl   стартовый URL для UI-шагов; может быть {@code null}, тогда берётся
     *                   ответ на вопрос {@code start_url}
     * @param apiBaseUrl базовый URL API; может быть {@code null}
     * @param startStep  индекс первого шага (с 0); отрицательное значение трактуется как 0
     * @return итог выполнения кейса
     */
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
        // Хост apiCall не входит в app.target.allowed-hosts: это провал (детерминированно),
        // поэтому зависимые шаги не должны превращаться в уточнения, решаемые моделью.
        boolean apiHostBlocked = false;

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

                for (StepPlanner.Plan plan : plans) {
                    if (plan.isSkip()) {
                        String reason = plan.reason() == null ? "" : plan.reason();
                        String topic = plan.topicKey();
                        boolean cascaded = "cascaded_skip".equalsIgnoreCase(topic)
                                || (reason != null && reason.toLowerCase().contains("предыдущ"));
                        if ("apicall".equalsIgnoreCase(plan.operation())) {
                            lastBody = null;
                            lastHeaders = new LinkedHashMap<>();
                            lastStatus = null;
                            apiDataAvailable = false;
                        }
                        enrichments.add(new StepEnrichment(i, "skip",
                                (cascaded ? "⏭ SKIP (каскад): " : "⏭ SKIP: ") + reason));
                        continue;
                    }

                    if (plan.needsClarification() && apiHostBlocked) {
                        enrichments.add(new StepEnrichment(i, "skip",
                                "⏭ SKIP (каскад): предыдущий apiCall заблокирован, хост не входит "
                                        + "в app.target.allowed-hosts"));
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
                        boolean isApiCall = "apicall".equals(op);
                        if (isApiCall) {
                            // Состояние ответа сбрасывается до вызова: неуспешный apiCall не должен
                            // оставить для следующих проверок ответ предыдущего вызова.
                            lastBody = null;
                            lastHeaders = new LinkedHashMap<>();
                            lastStatus = null;
                            apiDataAvailable = false;
                        }
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
                            if (isApiCall && lastBody != null && futureMentionsComparison(tc.steps(), i)
                                    && plans.stream().noneMatch(
                                    p -> "snapshotapivalue".equalsIgnoreCase(p.operation()))) {
                                StepEnrichment snap = autoSnapshotAfterApiCall(tc, i, lastBody, snapshots);
                                if (snap != null) enrichments.add(snap);
                            }
                        } catch (AssertionFailure af) {
                            if (af instanceof TargetNotAllowedException) {
                                apiHostBlocked = true;
                                enrichments.add(new StepEnrichment(i, op, "❌ " + af.getMessage()));
                                failures.add("Шаг " + (i + 1) + ": " + af.getMessage());
                                continue;
                            }
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

    /**
     * Истина для API-операций, которые проверяют уже полученный ответ и потому требуют
     * успешного предыдущего apiCall: тело, заголовки, статус, {@code parseJson} и
     * сравнение со снимком. Имена сравниваются в нижнем регистре.
     *
     * @param op имя операции в нижнем регистре
     * @return {@code true}, если операция зависит от предыдущего apiCall
     */
    private static boolean isApiAssert(String op) {
        return Set.of("assertbody", "assertbodyabsent",
                "assertheader", "assertheaderabsent",
                "assertstatus", "parsejson",
                "assertapivalueequalssnapshot").contains(op);
    }

    /**
     * Делает автоматический снимок сразу после успешного apiCall, если в будущих шагах есть
     * признак сравнения ({@link #COMPARE_HINT}) и модель не запланировала снимок сама.
     * <p>
     * Ключ снимка имеет вид {@code auto_step<N>} (N = номер текущего шага). jsonPath угадывается
     * по тексту будущих шагов и проверяется по фактическому телу ответа ({@link #guessJsonPath}).
     * Если подходящего пути в ответе нет, снимок не делается и шаг не получает провала:
     * сравнение в следующем шаге упадёт с сообщением «нет снимка с ключом».
     *
     * @param tc          тест-кейс
     * @param currentStep индекс текущего шага, с 0
     * @param body        тело успешного apiCall текущего шага
     * @param snapshots   снимки кейса; новый снимок добавляется сюда
     * @return enrichment снимка или {@code null}, если снимок не сделан
     */
    private StepEnrichment autoSnapshotAfterApiCall(ParsedTestCase tc, int currentStep, String body,
                                                    Map<String, String> snapshots) {
        String jsonPath = guessJsonPath(tc.steps(), currentStep, body);
        String snapshotKey = "auto_step" + (currentStep + 1);
        if (jsonPath == null) {
            log.info("Детерминированный snapshot пропущен: шаг {}, в ответе нет пути из будущих шагов",
                    currentStep + 1);
            return null;
        }

        log.info("Детерминированный snapshot: шаг {}, jsonPath={}, key={}",
                currentStep + 1, jsonPath, snapshotKey);

        StepPlanner.Plan snap = new StepPlanner.Plan(
                "snapshotApiValue", null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), null, snapshotKey, jsonPath, null);
        ApiExecutor.SnapshotResult sr = apiExecutor.snapshotApiValue(currentStep, snap, body);
        snapshots.put(sr.key(), sr.value());
        return sr.enrichment();
    }

    /**
     * Проверяет, содержит ли хотя бы один шаг после {@code currentStep} признак сравнения
     * ({@link #COMPARE_HINT}).
     *
     * @param steps       шаги кейса; может быть {@code null}
     * @param currentStep индекс текущего шага, с 0
     * @return {@code true}, если найден признак сравнения в будущих шагах
     */
    private boolean futureMentionsComparison(List<String> steps, int currentStep) {
        if (steps == null) return false;
        for (int j = currentStep + 1; j < steps.size(); j++) {
            if (COMPARE_HINT.matcher(steps.get(j)).find()) return true;
        }
        return false;
    }

    /**
     * Угадывает jsonPath для автоматического снимка по тексту будущих шагов и телу ответа.
     * <p>
     * Кандидаты берутся из шагов после текущего в порядке появления: идентификаторы с точкой
     * ({@link #JSON_PATH_HINT}), точки заменяются на «/» и добавляется ведущий «/»:
     * {@code hourly.temperature_2m} превращается в {@code /hourly/temperature_2m}.
     * Возвращается первый кандидат, который находит значение в {@code body}. Ложные кандидаты
     * (например, «example.com» из текста шага) отсеиваются тем же способом.
     * Если ни один кандидат не найден или тело не JSON, возвращается {@code null}: путь
     * «/» не подставляется, потому что по RFC 6901 он адресует ключ с пустым именем, а не
     * значение, и снимок падал бы с «не найден».
     *
     * @param steps       шаги кейса; может быть {@code null}
     * @param currentStep индекс текущего шага, с 0
     * @param body        тело ответа текущего apiCall
     * @return путь в формате JSON Pointer (RFC 6901), который есть в теле, либо {@code null}
     */
    private String guessJsonPath(List<String> steps, int currentStep, String body) {
        if (steps == null || body == null) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
        if (root == null) return null;
        for (int j = currentStep + 1; j < steps.size(); j++) {
            Matcher m = JSON_PATH_HINT.matcher(steps.get(j));
            while (m.find()) {
                String pointer = "/" + m.group(1).replace('.', '/');
                if (!root.at(pointer).isMissingNode()) return pointer;
            }
        }
        return null;
    }

    /**
     * Решение о стартовом URL для новой UI-сессии. Заполнено ровно одно из трёх полей:
     * {@code url} (открыть сессию), {@code isSkip} (пропустить шаг) или
     * {@code clarification} (запросить уточнение).
     *
     * @param url           действительный http(s)-URL; {@code null} для пропуска и уточнения
     * @param isSkip        {@code true}, если шаг нужно пропустить по ответу пользователя
     * @param clarification вопрос о стартовом URL; {@code null}, если URL уже известен
     */
    private record StartUrlOutcome(String url, boolean isSkip, Clarification clarification) {

        /**
         * Решение «открыть сессию с этим URL».
         *
         * @param url действительный http(s)-URL
         * @return исход с URL
         */
        static StartUrlOutcome ok(String url) {
            return new StartUrlOutcome(url, false, null);
        }

        /**
         * Решение «пропустить шаг».
         *
         * @return исход пропуска
         */
        static StartUrlOutcome skip() {
            return new StartUrlOutcome(null, true, null);
        }

        /**
         * Решение «запросить уточнение».
         *
         * @param c вопрос пользователю
         * @return исход уточнения
         */
        static StartUrlOutcome clarify(Clarification c) {
            return new StartUrlOutcome(null, false, c);
        }
    }

    /**
     * Определяет стартовый URL для UI-сессии.
     * <p>
     * Источник URL: {@code startUrl} из запроса, а при его отсутствии ответ на вопрос
     * {@code start_url} (см. {@link #resolveStartUrl}). Действительным считается непустой
     * URL, который начинается с {@code http://} или {@code https://} и не равен {@code SKIP}.
     * Если URL действителен, возвращается {@link StartUrlOutcome#ok}. Иначе, если на тему
     * {@code start_url} уже есть любой непустой ответ либо SKIP, шаг пропускается, даже
     * когда ответ не является http(s)-URL. В остальных случаях возвращается уточнение.
     *
     * @param tc       тест-кейс
     * @param startUrl стартовый URL из запроса; может быть {@code null}
     * @param i        индекс текущего шага, с 0
     * @param step     текст шага для вопроса пользователю
     * @param enrichments отчёт, в который пишется запись о пропуске
     * @return решение: URL, пропуск или уточнение
     */
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

    /**
     * Маршрутизирует UI-операцию в {@link UiExecutor}.
     * <p>
     * {@code snapshotUiText} сохраняет значение в {@code snapshots} под своим ключом.
     * {@code assertUiTextEqualsSnapshot} сверяется с {@code snapshots}. Остальные
     * операции выполняются через {@link UiExecutor#executeStep} с текущим URL страницы.
     *
     * @param uiSession браузерная сессия
     * @param i         индекс шага, с 0
     * @param plan      план шага
     * @param snapshots снимки кейса, изменяемые операцией {@code snapshotUiText}
     * @return enrichment шага
     */
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

    /**
     * Маршрутизирует API-операцию в {@link ApiExecutor}.
     * <p>
     * {@code snapshotApiValue} сохраняет значение в {@code snapshots}, результат без
     * состояния ответа. {@code assertApiValueEqualsSnapshot} сверяется с {@code snapshots}.
     * Остальные операции выполняются через {@link ApiExecutor#executeStep} с пустыми картами
     * заголовков и параметров пути: заголовки кейса в этом пути не передаются.
     *
     * @param i           индекс шага, с 0
     * @param plan        план шага
     * @param apiBaseUrl  базовый URL API
     * @param lastBody    тело последнего ответа
     * @param lastHeaders заголовки последнего ответа
     * @param lastStatus  статус последнего ответа
     * @param snapshots   снимки кейса
     * @return enrichment шага и результат apiCall-подобного шага или {@code null}
     */
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

    /**
     * Распознаёт сообщения об ошибках конфигурации или недоступности API.
     * <p>
     * Проверяются подстроки в нижнем регистре: неизвестный протокол, неизвестный хост
     * ({@code unknown host}), {@code api_base_url}, отказ соединения, таймаут соединения
     * (connect timed out), «не отвечает», некорректный абсолютный URL, незаданный базовый
     * URL. Таймаут чтения ответа не распознаётся. Такой провал только apiCall (см. {@link #run})
     * переводится в уточнение по теме {@code api_base_url}.
     *
     * @param msg текст ошибки; может быть {@code null}
     * @return {@code true}, если сообщение похоже на ошибку конфигурации
     */
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

    /**
     * Возвращает стартовый URL из запроса либо ответ на вопрос {@code start_url}.
     * <p>
     * Непустой {@code fallback} возвращается без проверки. Иначе ищется ответ с темой
     * {@code start_url} (без учёта регистра): обрезанный ответ, если он не пуст и не равен
     * {@code SKIP}, либо {@code null} для пустого ответа и {@code SKIP}. Если такого ответа нет,
     * возвращается {@code fallback}.
     *
     * @param tc       тест-кейс с ответами пользователя
     * @param fallback URL из запроса; может быть {@code null} или пустым
     * @return стартовый URL, {@code null} либо значение {@code fallback}
     */
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

    /**
     * Проверяет, ответил ли пользователь {@code SKIP} на вопрос по теме {@code topicKey}.
     * <p>
     * Ответ с точным совпадением темы (без учёта регистра) учитывается. Ответ без темы
     * учитывается, если тема входит в текст его вопроса.
     *
     * @param tc       тест-кейс с ответами пользователя
     * @param topicKey тема вопроса; {@code null} или пустая строка дают {@code false}
     * @return {@code true}, если на эту тему получен {@code SKIP}
     */
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

    /**
     * Возвращает первый непустой ответ пользователя на вопрос по теме {@code topicKey}.
     * Сопоставление тем такое же, как в {@link #isSkippedInThisCase}. Ответ {@code SKIP}
     * тоже возвращается, так что проверка {@code != null} срабатывает и для него.
     *
     * @param tc       тест-кейс с ответами пользователя
     * @param topicKey тема вопроса; {@code null} или пустая строка дают {@code null}
     * @return текст ответа либо {@code null}, если ответа нет
     */
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
     * Нормализует тему вопроса к виду, пригодному для сопоставления с ответами.
     * <p>
     * Если тема задана планом, она приводится к нижнему регистру, подряд идущие символы
     * вне {@code [a-z0-9_]} заменяются одним «_», длина ограничивается 40 символами. Иначе
     * тема строится из причины: нижний регистр, подряд идущие символы вне {@code [a-z0-9]}
     * заменяются одним «_». Причина без латинских букв и цифр (например, русская) даёт тему
     * «_», поэтому разные такие причины сливаются в одну тему. Если причина не задана или
     * пуста, тема — {@code clarification}.
     *
     * @param topicKey тема из плана; может быть {@code null}
     * @param reason   причина или текст ошибки; может быть {@code null}
     * @return нормализованная тема длиной не более 40 символов
     */
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

    /**
     * Добавляет префикс {@code [topic] } к тексту вопроса, чтобы ответ можно было
     * сопоставить с темой. Если тема не задана, текст возвращается как есть.
     *
     * @param question текст вопроса; может быть {@code null}
     * @param topicKey тема вопроса; может быть {@code null} или пустой
     * @return вопрос с префиксом либо без него
     */
    private String withTopic(String question, String topicKey) {
        if (topicKey == null || topicKey.isBlank()) return question;
        return "[" + topicKey + "] " + (question == null ? "" : question);
    }
}
