package application.service;

import application.dto.Dtos.Clarification;
import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.ParsedTestCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Сервисный фасад над {@link HybridCaseRunner}, который вызывает контроллер прогона.
 * <p>
 * Место в конвейере: {@code RunController} перебирает разобранные кейсы и для каждого
 * вызывает {@link #runCase}. Фасад отсекает кейсы без типа до запуска, а результат
 * {@link HybridCaseRunner.Result} переупаковывает в {@link RunnerOutcome}, добавив признак
 * пропуска кейса.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseRunnerService {

    /**
     * Оркестратор, который выполняет шаги кейса.
     */
    private final HybridCaseRunner hybridRunner;

    /**
     * Результат выполнения кейса на уровне сервиса.
     *
     * @param outcome            итоговая запись кейса для отчёта
     * @param clarifications     вопросы пользователю; пусто, если уточнений нет
     * @param skipCase           {@code true}, если кейс не запускался, потому что тип не задан
     *                           или помечен как {@code INVALID}
     * @param stoppedAtStepIndex индекс шага (с 0), на котором остановлено выполнение
     */
    public record RunnerOutcome(ExecutionOutcome outcome,
                                List<Clarification> clarifications,
                                boolean skipCase,
                                int stoppedAtStepIndex) {

        /**
         * Создаёт результат без индекса остановки; {@code stoppedAtStepIndex} равен 0.
         *
         * @param outcome        итоговая запись кейса
         * @param clarifications вопросы пользователю
         * @param skipCase       признак пропуска кейса без запуска
         */
        public RunnerOutcome(ExecutionOutcome outcome,
                             List<Clarification> clarifications,
                             boolean skipCase) {
            this(outcome, clarifications, skipCase, 0);
        }
    }

    /**
     * Выполняет кейс с первого шага. Делегирует в {@link #runCase(ParsedTestCase, String, String, int)}
     * с {@code startStep = 0}.
     *
     * @param tc         разобранный тест-кейс
     * @param startUrl   стартовый URL для UI-шагов; может быть {@code null}
     * @param apiBaseUrl базовый URL API; может быть {@code null}
     * @return результат выполнения кейса
     */
    public RunnerOutcome runCase(ParsedTestCase tc, String startUrl, String apiBaseUrl) {
        return runCase(tc, startUrl, apiBaseUrl, 0);
    }

    /**
     * Выполняет кейс, начиная с указанного шага.
     * <p>
     * Кейс с типом {@code null} или {@code INVALID} не запускается: возвращается
     * статус {@code SKIPPED} с признаком {@code skipCase = true}. Тип, который не входит
     * в {@code UI}, {@code API}, {@code E2E}, {@code NEGATIVE} и {@code SECURITY},
     * приводит к предупреждению в логе, но кейс всё равно выполняется. Правила допустимых
     * операций для такого типа задаёт {@link HybridCaseRunner}.
     *
     * @param tc         разобранный тест-кейс
     * @param startUrl   стартовый URL для UI-шагов; может быть {@code null}
     * @param apiBaseUrl базовый URL API; может быть {@code null}
     * @param startStep  индекс первого шага (с 0); передаётся в {@link HybridCaseRunner#run}
     * @return результат выполнения кейса
     */
    public RunnerOutcome runCase(ParsedTestCase tc, String startUrl, String apiBaseUrl,
                                 int startStep) {
        if (tc.type() == null || "INVALID".equalsIgnoreCase(tc.type())) {
            return new RunnerOutcome(new ExecutionOutcome(
                    tc.id(), tc.sourceFilename(), "SKIPPED",
                    "Не указан тип кейса (UI/API/E2E/NEGATIVE/SECURITY)", List.of()),
                    List.of(), true, 0);
        }
        String type = tc.type().trim().toUpperCase();
        if (!"UI".equals(type) && !"API".equals(type)
                && !"E2E".equals(type) && !"NEGATIVE".equals(type) && !"SECURITY".equals(type)) {
            log.warn("TC {}: неизвестный тип '{}', трактую как UI", tc.id(), tc.type());
        }
        HybridCaseRunner.Result r = hybridRunner.run(tc, startUrl, apiBaseUrl, startStep);
        return new RunnerOutcome(r.outcome(), r.clarifications(), false, r.stoppedAtStepIndex());
    }
}
