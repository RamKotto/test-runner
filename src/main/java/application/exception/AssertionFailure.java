package application.exception;

/**
 * Маркер "тест не прошёл по факту": запрос выполнен, данные для проверки есть,
 * но результат не соответствует ожиданию.
 * <p>
 * В отличие от {@link application.service.UiExecutor.LocatorNotResolved},
 * это НЕ повод запрашивать уточнение у пользователя — это результат теста (FAILED).
 */
public class AssertionFailure extends RuntimeException {
    public AssertionFailure(String message) {
        super(message);
    }

    public AssertionFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
