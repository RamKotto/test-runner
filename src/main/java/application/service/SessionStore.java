package application.service;

import application.config.AppProperties;
import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.ParsedTestCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SessionStore {

    /**
     * FIX 5: добавлен currentStepIndex — чтобы после /answers продолжать
     * кейс с того же шага, а не с нуля (иначе один и тот же вопрос задаётся дважды).
     */
    public record Session(
            List<ParsedTestCase> testCases,
            String startUrl,
            String apiBaseUrl,
            int currentTestCaseIndex,
            int currentStepIndex,
            Instant createdAt,
            List<AnswerRequest.Answer> answers
    ) {
    }

    private final AppProperties props;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public void put(String taskId, Session session) {
        cleanup();
        if (sessions.size() >= props.getSessions().getMaxCount()) {
            sessions.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().createdAt()))
                    .ifPresent(e -> sessions.remove(e.getKey()));
        }
        sessions.put(taskId, new Session(
                session.testCases(), session.startUrl(), session.apiBaseUrl(),
                session.currentTestCaseIndex(), session.currentStepIndex(),
                Instant.now(), dedupe(session.answers())));
    }

    public Session get(String taskId) {
        cleanup();
        return sessions.get(taskId);
    }

    public void remove(String taskId) {
        sessions.remove(taskId);
    }

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

    private void cleanup() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(props.getSessions().getTtlMinutes()));
        sessions.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(threshold));
    }
}
