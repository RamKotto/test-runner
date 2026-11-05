package application.service;

import application.dto.Dtos.AnswerRequest.Answer;
import application.dto.Dtos.AnswerView;
import application.dto.Dtos.Clarification;
import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.RunResponse;
import application.dto.Dtos.RunSessionView;
import application.dto.Dtos.RunState;
import application.exception.ConflictException;
import application.repository.ArtifactRepository;
import application.repository.ArtifactRepository.NewArtifact;
import application.repository.ClarificationSessionRepository;
import application.repository.ClarificationSessionRepository.SessionRow;
import application.repository.RunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Сессии прогонов уточнений в БД: чтение состояния, дедупликация ответов и атомарная фиксация результата.
 * <p>
 * Место в конвейере: {@code RunController} читает сессию через {@link #requireActive}, формирует
 * новый ответ и фиксирует его через {@link #commit}. Фиксация выполняется в одной транзакции: версия
 * сессии (оптимистичная блокировка), новые ответы, файлы прогона и запись в {@code execution_runs}.
 * Если версия изменилась параллельным запросом, транзакция откатывается и возвращается 409.
 */
@Service
@RequiredArgsConstructor
public class RunSessionService {

    /**
     * Сообщение для отсутствующей или истёкшей сессии. Фронтенд определяет истечение по подстроке «истекл».
     */
    public static final String SESSION_NOT_FOUND = "Сессия не найдена или истекла";

    /**
     * Сообщение при конфликте версий сессии.
     */
    public static final String CONFLICT_MESSAGE = "Сессия уже изменена в другом окне. Обновите страницу.";

    private static final TypeReference<List<Clarification>> CLARIFICATIONS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> DRAFT_TYPE = new TypeReference<>() {
    };

    /**
     * Фиксируемый результат одного запроса: всё, что должно записаться в одной транзакции.
     *
     * @param taskId          идентификатор прогона
     * @param create          {@code true}, если сессия создаётся (первый запуск прогона)
     * @param expectedVersion версия, которую читал запрос (для проверки при обновлении)
     * @param version         новая версия сессии
     * @param round           номер раунда, которым записываются новые ответы
     * @param status          статус прогона
     * @param state           новое состояние прогона
     * @param clarifications  уточнения, на которые нужно ответить
     * @param newAnswers      новые непустые ответы пользователя (пусто при создании)
     * @param files           файлы прогона: обогащённые кейсы, индекс и скачанные файлы
     * @param outcomes        итоги всех кейсов прогона (для таблицы {@code execution_runs})
     */
    public record Commit(UUID taskId, boolean create, int expectedVersion, int version, int round,
                         String status, RunState state, List<Clarification> clarifications,
                         List<Answer> newAnswers, List<NewArtifact> files, List<ExecutionOutcome> outcomes) {
    }

    private final ClarificationSessionRepository sessions;
    private final ArtifactRepository artifacts;
    private final RunRepository runRepository;
    private final ObjectMapper mapper;

    /**
     * Возвращает действующую сессию прогона.
     *
     * @param taskId идентификатор прогона
     * @return строка сессии
     * @throws IllegalArgumentException сессии нет или она истекла (HTTP 400)
     */
    public SessionRow requireActive(UUID taskId) {
        return sessions.findActive(taskId).orElseThrow(() -> new IllegalArgumentException(SESSION_NOT_FOUND));
    }

    /**
     * Читает внутреннее состояние прогона из JSON.
     *
     * @param json JSON из столбца {@code state}
     * @return состояние прогона
     * @throws IllegalStateException JSON не удалось разобрать
     */
    public RunState readState(String json) {
        try {
            return mapper.readValue(json, RunState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось прочитать состояние прогона", e);
        }
    }

    /**
     * Возвращает историю ответов сессии в виде, пригодном для передачи планировщику.
     *
     * @param taskId идентификатор прогона
     * @return все ответы сессии в порядке раундов (дубли ещё не схлопнуты)
     */
    public List<Answer> historyAnswers(UUID taskId) {
        return sessions.findAnswers(taskId).stream()
                .map(a -> new Answer(a.question(), a.answer(), a.topicKey(), a.testCaseId()))
                .toList();
    }

    /**
     * Формирует ответ {@code GET /api/run/{taskId}}: состояние сессии плюс поля последнего ответа прогона.
     *
     * @param row строка сессии
     * @return представление сессии
     */
    public RunSessionView view(SessionRow row) {
        RunState state = readState(row.stateJson());
        RunResponse last = state.lastResponse();
        List<AnswerView> answers = sessions.findAnswers(row.taskId()).stream()
                .map(a -> new AnswerView(a.round(), a.testCaseId(), a.question(), a.topicKey(),
                        a.answer(), a.answeredAt()))
                .toList();
        return new RunSessionView(
                row.taskId().toString(),
                row.status(),
                row.version(),
                row.round(),
                last == null ? List.of() : last.outcomes(),
                readJson(row.clarificationsJson(), CLARIFICATIONS_TYPE, List.of()),
                answers,
                readJson(row.draftJson(), DRAFT_TYPE, Map.of()),
                row.expiresAt(),
                last == null ? null : last.enrichedIndexUrl(),
                last == null ? null : last.archiveUrl());
    }

    /**
     * Перезаписывает черновики сессии целиком. Версия не меняется.
     *
     * @param taskId идентификатор прогона
     * @param draft  новые черновики
     * @throws IllegalArgumentException сессии нет или она истекла (HTTP 400)
     */
    public void updateDraft(UUID taskId, Map<String, String> draft) {
        if (sessions.updateDraft(taskId, toJson(draft == null ? Map.of() : draft)) == 0) {
            throw new IllegalArgumentException(SESSION_NOT_FOUND);
        }
    }

    /**
     * Фиксирует результат запроса в одной транзакции.
     * <p>
     * При создании сессия вставляется; при продолжении версия обновляется условно: если версия в БД
     * уже не равна {@code expectedVersion}, бросается {@link ConflictException}, и транзакция откатывается
     * вместе с ответами и файлами.
     *
     * @param c результат запроса
     * @throws ConflictException версия сессии изменена параллельным запросом (HTTP 409)
     */
    @Transactional
    public void commit(Commit c) {
        String stateJson = toJson(c.state());
        String clarificationsJson = toJson(c.clarifications());
        if (c.create()) {
            sessions.insert(c.taskId(), c.status(), stateJson, clarificationsJson, c.version(), c.round());
        } else if (sessions.updateIfVersion(c.taskId(), c.expectedVersion(), c.status(), stateJson,
                clarificationsJson, c.version(), c.round()) == 0) {
            throw new ConflictException(CONFLICT_MESSAGE);
        }
        for (Answer a : c.newAnswers()) {
            sessions.insertAnswer(c.taskId(), c.round(), a);
        }
        artifacts.replaceRunFiles(c.taskId(), c.files());

        RunState state = c.state();
        String sourceLabel = state.sourceUrl() != null ? state.sourceUrl() : "md-to-test:" + state.sourceRunId();
        runRepository.replaceRun(c.taskId(), c.status(), sourceLabel, state.startUrl(), state.apiBaseUrl(),
                c.outcomes(), c.clarifications());
    }

    /**
     * Оставляет непустые ответы: {@code answer} не {@code null} и не пуст после удаления пробелов.
     *
     * @param answers ответы из запроса; может быть {@code null}
     * @return список непустых ответов; пустой, если входа нет
     */
    public static List<Answer> nonEmpty(List<Answer> answers) {
        if (answers == null) return List.of();
        return answers.stream()
                .filter(a -> a != null && a.answer() != null && !a.answer().isBlank())
                .toList();
    }

    /**
     * Схлопывает дубли по паре «тема + вопрос». Ключ нечувствителен к регистру и пробелам по краям.
     * Побеждает последний ответ; позиция пары соответствует её первому вхождению.
     *
     * @param answers ответы в порядке поступления; может быть {@code null}
     * @return список без дублей; пустой, если входа нет
     */
    public static List<Answer> dedupe(List<Answer> answers) {
        if (answers == null || answers.isEmpty()) return List.of();
        Map<String, Answer> byKey = new LinkedHashMap<>();
        for (Answer a : answers) {
            byKey.put(dedupeKey(a), a);
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Формирует ключ дедупликации из темы и вопроса.
     *
     * @param a ответ
     * @return ключ «тема, вопрос» в нижнем регистре
     */
    static String dedupeKey(Answer a) {
        return normalize(a.topicKey()) + "\u0000" + normalize(a.question());
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать данные сессии", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось прочитать данные сессии", e);
        }
    }
}
