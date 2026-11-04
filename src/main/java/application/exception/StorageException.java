package application.exception;

/**
 * Ошибка файлового хранилища: создание каталогов, запись или чтение обогащённых файлов.
 * <p>
 * {@link GlobalExceptionHandler} отображает исключение в HTTP 500.
 */
public class StorageException extends RuntimeException {

    /**
     * Создаёт исключение с сообщением.
     *
     * @param message описание ошибки хранилища
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * Создаёт исключение с сообщением и причиной.
     *
     * @param message описание ошибки хранилища
     * @param cause   исходное исключение ввода-вывода
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
