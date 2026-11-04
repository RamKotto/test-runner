package application.service;

import application.config.AppProperties;
import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.ParsedTestCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory хранилище сессий прогонов, остановленных на уточнении.
 * <p>
 * Место в конвейере: когда кейс возвращает {@code NEEDS_CLARIFICATION}, контроллер
 * сохраняет состояние прогона здесь, а при ответе пользователя забирает его и продолжает
 * выполнение. Сессии живут {@code app.sessions.ttl-minutes} минут, их число ограничено
 * {@code app.sessions.max-count}. Истёкшие сессии удаляются при каждом обращении.
 */
@Service
@RequiredArgsConstructor
public class SessionStore {

    /**
     * Состояние прогона, остановленного на уточнении.
     * <ul>
     *   <li>currentTestCaseIndex — абсолютный индекс кейса, на котором остановились.
     *       При ответе этот кейс перезапускается ЦЕЛИКОМ с шага 0.</li>
     *   <li>completedOutcomes — результаты кейсов, полностью завершённых ДО этого индекса
     *       (PASSED/FAILED/SKIPPED). Они не перезапускаются, а переносятся в ответ.</li>
     *   <li>answers — накопленные ответы пользователя; передаются всем перезапускаемым кейсам.</li>
     *   <li>sourceUrl, startUrl, apiBaseUrl — параметры прогона для записи в БД при ответе.</li>
     * </ul>
     *
     * @param testCases            все кейсы прогона в порядке выполнения
     * @param sourceUrl            исходный URL, по которому скачаны кейсы
     * @param startUrl             стартовый URL для UI-шагов
     * @param apiBaseUrl           базовый URL API
     * @param currentTestCaseIndex абсолютный индекс кейса, на котором остановлено выполнение
     * @param createdAt            время последнего сохранения сессии (основа для TTL)
     * @param answers              ответы пользователя без дублей по теме и вопросу
     * @param completedOutcomes    итоги кейсов, которые не нужно выполнять повторно
     */
    public record Session(
            List<ParsedTestCase> testCases,
            String sourceUrl,
            String startUrl,
            String apiBaseUrl,
            int currentTestCaseIndex,
            Instant createdAt,
            List<AnswerRequest.Answer> answers,
            List<ExecutionOutcome> completedOutcomes
    ) {
    }

    /**
     * Настройки TTL и лимита количества сессий.
     */
    private final AppProperties props;

    /**
     * Сессии по идентификатору задачи. Потокобезопасна за счёт {@link ConcurrentHashMap},
     * но проверка лимита и вставка в {@link #put} не атомарны.
     */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Сохраняет сессию под идентификатором задачи.
     * <p>
     * Перед записью удаляются просроченные сессии. Если сессия для {@code taskId} новая и
     * достигнут {@code app.sessions.max-count}, вытесняется сессия с самым старым
     * {@code createdAt}. Сохраняется копия: {@code createdAt} становится текущим временем
     * (TTL продлевается), ответы дедуплицируются, список итогов копируется неизменяемо.
     *
     * @param taskId  идентификатор задачи прогона
     * @param session сессия для сохранения
     */
    public void put(String taskId, Session session) {
        cleanup();
        if (!sessions.containsKey(taskId) && sessions.size() >= props.getSessions().getMaxCount()) {
            sessions.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().createdAt()))
                    .ifPresent(e -> sessions.remove(e.getKey()));
        }
        sessions.put(taskId, new Session(
                session.testCases(), session.sourceUrl(), session.startUrl(), session.apiBaseUrl(),
                session.currentTestCaseIndex(), Instant.now(),
                dedupe(session.answers()), copyOutcomes(session.completedOutcomes())));
    }

    /**
     * Возвращает сессию по идентификатору задачи. Просроченные сессии удаляются до поиска.
     *
     * @param taskId идентификатор задачи
     * @return сессия либо {@code null}, если её нет или срок жизни истёк
     */
    public Session get(String taskId) {
        cleanup();
        return sessions.get(taskId);
    }

    /**
     * Удаляет сессию, если она есть.
     *
     * @param taskId идентификатор задачи
     */
    public void remove(String taskId) {
        sessions.remove(taskId);
    }

    /**
     * Возвращает неизменяемую копию списка итогов.
     *
     * @param outcomes итоги кейсов; может быть {@code null}
     * @return неизменяемый список; пустой для {@code null} или пустого входа
     */
    private static List<ExecutionOutcome> copyOutcomes(List<ExecutionOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return List.of();
        return List.copyOf(outcomes);
    }

    /**
     * Оставляет по одному ответу на пару «тема + вопрос».
     * <p>
     * Ключ формируется из темы и вопроса в нижнем регистре. Для повторной пары сохраняется
     * последний ответ, а позиция остаётся от первого вхождения.
     *
     * @param answers ответы пользователя; может быть {@code null}
     * @return список без дублей; пустой для {@code null} или пустого входа
     */
    private static List<AnswerRequest.Answer> dedupe(List<AnswerRequest.Answer> answers) {
        if (answers == null || answers.isEmpty()) return List.of();
        Map<String, AnswerRequest.Answer> byKey = new LinkedHashMap<>();
        for (AnswerRequest.Answer a : answers) {
            String key = (a.topicKey() == null ? "" : a.topicKey().toLowerCase())
                    + "|" + (a.question() == null ? "" : a.question().toLowerCase());
            byKey.put(key, a);
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Удаляет сессии, чей {@code createdAt} старше {@code app.sessions.ttl-minutes}.
     */
    private void cleanup() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(props.getSessions().getTtlMinutes()));
        sessions.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(threshold));
    }
}
