package application.exception;

/**
 * Конфликт версий сессии: клиент передал {@code expectedVersion}, который не совпадает с текущей версией,
 * либо версия изменилась параллельным запросом.
 * <p>
 * {@link GlobalExceptionHandler} отображает исключение в HTTP 409.
 */
public class ConflictException extends RuntimeException {

    /**
     * Создаёт исключение с сообщением для пользователя.
     *
     * @param message текст ошибки
     */
    public ConflictException(String message) {
        super(message);
    }
}
