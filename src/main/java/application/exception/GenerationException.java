package application.exception;

/**
 * Ошибка планирования шага: LLM недоступна или вернула пустой или невалидный ответ, либо план не
 * соответствует типу кейса.
 * <p>
 * {@link application.service.HybridCaseRunner} перехватывает исключение и фиксирует шаг как ошибку
 * планирования. {@link GlobalExceptionHandler} отображает его в HTTP 502, если оно дошло до контроллера.
 */
public class GenerationException extends RuntimeException {

    /**
     * Создаёт исключение с сообщением.
     *
     * @param message описание ошибки планирования
     */
    public GenerationException(String message) {
        super(message);
    }

    /**
     * Создаёт исключение с сообщением и причиной.
     *
     * @param message описание ошибки планирования
     * @param cause   исходное исключение
     */
    public GenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
