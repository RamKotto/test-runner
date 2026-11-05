package application.service;

import application.dto.Dtos.AnswerRequest.Answer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты чистых функций {@link RunSessionService}: дедупликация ответов по паре «тема + вопрос»
 * и отбор непустых ответов. Транзакции и БД здесь не проверяются.
 */
class RunSessionServiceTest {

    /**
     * Повтор пары «тема + вопрос» схлопывается: остаётся последний ответ, позиция — первого вхождения.
     */
    @Test
    void dedupe_keepsLastAnswerPerTopicAndQuestion() {
        List<Answer> merged = RunSessionService.dedupe(List.of(
                new Answer("Какой токен?", "старый", "auth_token", "TC-1"),
                new Answer("Сколько строк?", "3", "expected_value", "TC-2"),
                new Answer("  какой ТОКЕН? ", "новый", "AUTH_TOKEN", "TC-1")));

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).answer()).isEqualTo("новый");
        assertThat(merged.get(1).answer()).isEqualTo("3");
    }

    /**
     * Одинаковый вопрос с другой темой — это разные ответы.
     */
    @Test
    void dedupe_differentTopicKeepsBothAnswers() {
        List<Answer> merged = RunSessionService.dedupe(List.of(
                new Answer("Введите значение", "а", "topic_a", null),
                new Answer("Введите значение", "б", "topic_b", null)));

        assertThat(merged).hasSize(2);
    }

    /**
     * Пустой или null-вход даёт пустой список.
     */
    @Test
    void dedupe_nullOrEmptyGivesEmpty() {
        assertThat(RunSessionService.dedupe(null)).isEmpty();
        assertThat(RunSessionService.dedupe(List.of())).isEmpty();
    }

    /**
     * Пустые и пробельные ответы отбрасываются, непустые сохраняют порядок.
     */
    @Test
    void nonEmpty_dropsBlankAnswers() {
        List<Answer> kept = RunSessionService.nonEmpty(List.of(
                new Answer("q1", "", "t1", null),
                new Answer("q2", "   ", "t2", null),
                new Answer("q3", "ответ", "t3", null)));

        assertThat(kept).extracting(Answer::question).containsExactly("q3");
        assertThat(RunSessionService.nonEmpty(null)).isEmpty();
    }
}
