package application.exception;

/**
 * Ошибка получения тест-кейсов из источника: сетевой сбой, отсутствующий или пустой файл, неверный
 * индекс {@code _index.md}, невозможность разобрать файл.
 * <p>
 * {@link GlobalExceptionHandler} отображает исключение в HTTP 502.
 */
public class FetchException extends RuntimeException {

    /**
     * Создаёт исключение с сообщением.
     *
     * @param message описание ошибки получения
     */
    public FetchException(String message) {
        super(message);
    }

    /**
     * Создаёт исключение с сообщением и причиной.
     *
     * @param message описание ошибки получения
     * @param cause   исходное исключение
     */
    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
