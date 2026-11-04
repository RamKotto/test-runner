package application.exception;

/**
 * Проверка шага не выполнена по существу: запрос выполнен и данные для проверки есть, но результат
 * не соответствует ожиданию.
 * <p>
 * Исполнитель переводит шаг в ошибку теста (статус {@code FAILED}) и продолжает прогон. Запрос уточнения
 * у пользователя при этом не создаётся. В отличие от {@link application.service.UiExecutor.LocatorNotResolved},
 * который означает нехватку данных и порождает уточнение.
 */
public class AssertionFailure extends RuntimeException {

    /**
     * Создаёт исключение с сообщением о невыполненной проверке.
     *
     * @param message описание несоответствия
     */
    public AssertionFailure(String message) {
        super(message);
    }

    /**
     * Создаёт исключение с сообщением и причиной.
     *
     * @param message описание несоответствия
     * @param cause   исходное исключение
     */
    public AssertionFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
