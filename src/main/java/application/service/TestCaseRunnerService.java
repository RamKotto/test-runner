package application.service;

import application.dto.Dtos.Clarification;
import application.dto.Dtos.ExecutionOutcome;
import application.dto.Dtos.ParsedTestCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseRunnerService {

    private final HybridCaseRunner hybridRunner;

    public record RunnerOutcome(ExecutionOutcome outcome,
                                List<Clarification> clarifications,
                                boolean skipCase) {
    }

    public RunnerOutcome runCase(ParsedTestCase tc, String startUrl, String apiBaseUrl) {
        if (tc.type() == null || "INVALID".equalsIgnoreCase(tc.type())) {
            return new RunnerOutcome(new ExecutionOutcome(
                    tc.id(), tc.sourceFilename(), "SKIPPED",
                    "Не указан тип кейса (UI/API/E2E/NEGATIVE/SECURITY)", List.of()),
                    List.of(), true);
        }
        String type = tc.type().trim().toUpperCase();
        if (!"UI".equals(type) && !"API".equals(type)
                && !"E2E".equals(type) && !"NEGATIVE".equals(type) && !"SECURITY".equals(type)) {
            log.warn("TC {}: неизвестный тип '{}', трактую как UI", tc.id(), tc.type());
        }
        HybridCaseRunner.Result r = hybridRunner.run(tc, startUrl, apiBaseUrl);
        return new RunnerOutcome(r.outcome(), r.clarifications(), false);
    }
}
