package application.repository;

import application.dto.Dtos.AnswerRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий сессий уточнений прогона в {@code qa.clarification_sessions} (service = {@code run})
 * и истории ответов в {@code qa.clarification_answers}.
 * <p>
 * История ответов только дополняется. Каждое изменение сессии продлевает {@code expires_at} на 30 дней.
 * Методы только выполняют SQL; транзакции задаёт {@code RunSessionService}.
 */
@Repository
@RequiredArgsConstructor
public class ClarificationSessionRepository {

    /**
     * Строка сессии. JSON-столбцы читаются и передаются текстом.
     *
     * @param taskId             идентификатор прогона
     * @param status             {@code NEEDS_CLARIFICATION} или {@code DONE}
     * @param stateJson          внутреннее состояние прогона (JSON)
     * @param clarificationsJson уточнения последнего ответа (JSON-массив)
     * @param draftJson          черновики ответов (JSON-объект)
     * @param version            версия сессии
     * @param round              номер последнего раунда ответов
     * @param expiresAt          время истечения
     */
    public record SessionRow(UUID taskId, String status, String stateJson, String clarificationsJson,
                             String draftJson, int version, int round, Instant expiresAt) {
    }

    /**
     * Строка истории ответов.
     *
     * @param round      номер раунда
     * @param testCaseId идентификатор тест-кейса; может быть {@code null}
     * @param question   текст уточнения
     * @param topicKey   ключ темы; может быть {@code null}
     * @param answer     текст ответа
     * @param answeredAt время ответа
     */
    public record AnswerRow(int round, String testCaseId, String question, String topicKey,
                            String answer, Instant answeredAt) {
    }

    private static final RowMapper<SessionRow> SESSION_ROW = (rs, rowNum) -> new SessionRow(
            rs.getObject("task_id", UUID.class),
            rs.getString("status"),
            rs.getString("state"),
            rs.getString("clarifications"),
            rs.getString("draft"),
            rs.getInt("version"),
            rs.getInt("round"),
            rs.getTimestamp("expires_at").toInstant());

    private static final RowMapper<AnswerRow> ANSWER_ROW = (rs, rowNum) -> new AnswerRow(
            rs.getInt("round"),
            rs.getString("test_case_id"),
            rs.getString("question"),
            rs.getString("topic_key"),
            rs.getString("answer"),
            rs.getTimestamp("answered_at").toInstant());

    private final JdbcTemplate jdbc;

    /**
     * Ищет действующую (не истёкшую) сессию прогона.
     *
     * @param taskId идентификатор прогона
     * @return сессия либо пусто
     */
    public Optional<SessionRow> findActive(UUID taskId) {
        return jdbc.query("""
                SELECT task_id, status, state::text AS state, clarifications::text AS clarifications,
                       draft::text AS draft, version, round, expires_at
                FROM qa.clarification_sessions
                WHERE task_id = ? AND service = 'run' AND expires_at > now()
                """, SESSION_ROW, taskId).stream().findFirst();
    }

    /**
     * Создаёт сессию прогона.
     *
     * @param taskId             идентификатор прогона
     * @param status             статус
     * @param stateJson          состояние (JSON)
     * @param clarificationsJson уточнения (JSON)
     * @param version            начальная версия
     * @param round              начальный раунд
     */
    public void insert(UUID taskId, String status, String stateJson, String clarificationsJson,
                       int version, int round) {
        jdbc.update("""
                INSERT INTO qa.clarification_sessions
                    (task_id, service, status, state, clarifications, draft, version, round,
                     created_at, updated_at, expires_at)
                VALUES (?, 'run', ?, ?::jsonb, ?::jsonb, '{}'::jsonb, ?, ?,
                        now(), now(), now() + interval '30 days')
                """, taskId, status, stateJson, clarificationsJson, version, round);
    }

    /**
     * Обновляет сессию, только если её версия всё ещё равна ожидаемой (оптимистичная блокировка).
     *
     * @param taskId             идентификатор прогона
     * @param expectedVersion    версия, которую читал вызывающий код
     * @param status             новый статус
     * @param stateJson          новое состояние (JSON)
     * @param clarificationsJson новые уточнения (JSON)
     * @param version            новая версия
     * @param round              новый раунд
     * @return число обновлённых строк: 0 — версия уже изменилась
     */
    public int updateIfVersion(UUID taskId, int expectedVersion, String status, String stateJson,
                               String clarificationsJson, int version, int round) {
        return jdbc.update("""
                UPDATE qa.clarification_sessions
                SET status = ?, state = ?::jsonb, clarifications = ?::jsonb, version = ?, round = ?,
                    updated_at = now(), expires_at = now() + interval '30 days'
                WHERE task_id = ? AND service = 'run' AND version = ?
                """, status, stateJson, clarificationsJson, version, round, taskId, expectedVersion);
    }

    /**
     * Дописывает один ответ пользователя в историю.
     *
     * @param taskId идентификатор прогона
     * @param round  номер раунда
     * @param answer ответ (не пустой)
     */
    public void insertAnswer(UUID taskId, int round, AnswerRequest.Answer answer) {
        jdbc.update("""
                INSERT INTO qa.clarification_answers (task_id, round, test_case_id, question, topic_key, answer)
                VALUES (?, ?, ?, ?, ?, ?)
                """, taskId, round, answer.testCaseId(),
                answer.question() == null ? "" : answer.question(),
                answer.topicKey(), answer.answer());
    }

    /**
     * Возвращает всю историю ответов сессии в порядке раундов.
     *
     * @param taskId идентификатор прогона
     * @return строки истории
     */
    public List<AnswerRow> findAnswers(UUID taskId) {
        return jdbc.query("""
                SELECT round, test_case_id, question, topic_key, answer, answered_at
                FROM qa.clarification_answers
                WHERE task_id = ?
                ORDER BY round, id
                """, ANSWER_ROW, taskId);
    }

    /**
     * Перезаписывает черновики сессии и продлевает срок. Версия не меняется.
     *
     * @param taskId    идентификатор прогона
     * @param draftJson черновики (JSON)
     * @return число обновлённых строк: 0 — сессии нет или она истекла
     */
    public int updateDraft(UUID taskId, String draftJson) {
        return jdbc.update("""
                UPDATE qa.clarification_sessions
                SET draft = ?::jsonb, updated_at = now(), expires_at = now() + interval '30 days'
                WHERE task_id = ? AND service = 'run' AND expires_at > now()
                """, draftJson, taskId);
    }

    /**
     * Удаляет сессии прогона, срок которых истёк. История ответов удаляется каскадно.
     *
     * @return число удалённых сессий
     */
    public int deleteExpired() {
        return jdbc.update("DELETE FROM qa.clarification_sessions WHERE service = 'run' AND expires_at < now()");
    }
}
