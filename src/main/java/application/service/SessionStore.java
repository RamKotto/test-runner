package application.service;

import application.config.AppProperties;
import application.dto.Dtos.ParsedTestCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SessionStore {

    public record Session(
            List<ParsedTestCase> testCases,
            String startUrl,
            String apiBaseUrl,
            int currentTestCaseIndex,
            Instant createdAt
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
                session.currentTestCaseIndex(), Instant.now()));
    }

    public Session get(String taskId) {
        cleanup();
        return sessions.get(taskId);
    }

    public void remove(String taskId) {
        sessions.remove(taskId);
    }

    private void cleanup() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(props.getSessions().getTtlMinutes()));
        sessions.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(threshold));
    }
}
