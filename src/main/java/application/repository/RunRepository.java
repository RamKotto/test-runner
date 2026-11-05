package application.repository;

import application.dto.Dtos.Clarification;
import application.dto.Dtos.ExecutionOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Репозиторий записи результатов исполнения в схему {@code qa}: таблицы {@code execution_runs},
 * {@code case_executions} и {@code execution_clarifications}.
 * <p>
 * Прогон сохраняется полным снимком: строка прогона создаётся или обновляется, а строки кейсов и
 * уточнений этого прогона удаляются и вставляются заново. Ошибки БД не перехватываются и доходят до
 * вызывающего кода.
 */
@Repository
@RequiredArgsConstructor
public class RunRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /**
     * Полностью перезаписывает результаты прогона в одной транзакции.
     * <p>
     * Порядок действий: upsert строки в {@code qa.execution_runs} по {@code runId}; удаление строк уточнений
     * и кейсов этого прогона; вставка строки на каждый результат кейса; вставка строки на каждое уточнение.
     * Подстановки для {@code null}: статус прогона — {@code NEEDS_CLARIFICATION}; идентификатор кейса —
     * {@code UNKNOWN}; статус кейса — {@code FAILED}; текст вопроса — пустая строка; список
     * обогащений сохраняется как JSON-массив, при {@code null} — {@code []}.
     *
     * @param runId          идентификатор прогона (taskId)
     * @param status         статус прогона: {@code DONE} или {@code NEEDS_CLARIFICATION}
     * @param sourceUrl      метка источника: значение {@code sourceUrl} или {@code md-to-test:<sourceRunId>}
     * @param startUrl       стартовый URL веб-приложения; может быть {@code null}
     * @param apiBaseUrl     базовый URL API; может быть {@code null}
     * @param outcomes       итоги кейсов прогона
     * @param clarifications уточнения прогона; при {@code null} уточнения не записываются
     * @throws IllegalStateException если обогащения не удалось сериализовать в JSON
     */
    @Transactional
    public void replaceRun(UUID runId, String status, String sourceUrl, String startUrl,
                           String apiBaseUrl, List<ExecutionOutcome> outcomes,
                           List<Clarification> clarifications) {
        jdbc.update("""
                INSERT INTO qa.execution_runs (id, status, source_url, start_url, api_base_url, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    source_url = EXCLUDED.source_url,
                    start_url = EXCLUDED.start_url,
                    api_base_url = EXCLUDED.api_base_url,
                    updated_at = now()
                """, runId, nonNull(status, "NEEDS_CLARIFICATION"), sourceUrl, startUrl, apiBaseUrl);

        jdbc.update("DELETE FROM qa.execution_clarifications WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM qa.case_executions WHERE run_id = ?", runId);

        for (ExecutionOutcome o : outcomes) {
            jdbc.update("""
                    INSERT INTO qa.case_executions
                        (run_id, test_case_id, filename, status, failure_message, enrichments)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    runId,
                    nonNull(o.testCaseId(), "UNKNOWN"),
                    o.filename(),
                    nonNull(o.status(), "FAILED"),
                    o.failureMessage(),
                    toJson(o.enrichments()));
        }

        if (clarifications == null) return;
        for (Clarification c : clarifications) {
            jdbc.update("""
                    INSERT INTO qa.execution_clarifications
                        (run_id, test_case_id, step_index, question, reason, category, topic_key)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    runId,
                    nonNull(c.testCaseId(), "UNKNOWN"),
                    c.stepIndex(),
                    nonNull(c.question(), ""),
                    c.reason(),
                    c.category(),
                    c.topicKey());
        }
    }

    /**
     * Сериализует значение в JSON-строку. {@code null} заменяется пустым списком.
     *
     * @param value значение для сериализации
     * @return JSON-представление значения
     * @throws IllegalStateException если Jackson не смог сериализовать значение
     */
    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать enrichments в JSON", e);
        }
    }

    /**
     * Возвращает значение или запасной вариант, если значение равно {@code null}.
     *
     * @param value    проверяемое значение
     * @param fallback значение, которое возвращается вместо {@code null}
     * @return {@code value}, если он не {@code null}, иначе {@code fallback}
     */
    private static String nonNull(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
