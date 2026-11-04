package application.exception;

import application.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Глобальный обработчик исключений REST-контроллеров.
 * <p>
 * Преобразует исключения в {@link ErrorResponse} и HTTP-статус. Сопоставление:
 * {@link IllegalArgumentException} → 400; {@link FetchException} и {@link GenerationException} → 502;
 * {@link StorageException} → 500 с префиксом сообщения; любое другое исключение → 500 с общим текстом.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Обрабатывает некорректные входные данные и неизвестные ресурсы.
     * <p>
     * Ответ 400 с текстом исключения в {@code message}. Сюда попадают, например, пустой {@code sourceUrl},
     * неизвестная или истёкшая сессия уточнений, неверное имя файла результата и отсутствие файла
     * результата (в последнем случае статус 400, а не 404).
     *
     * @param e исключение с описанием ошибки
     * @return ответ с HTTP 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> bad(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Обрабатывает ошибки скачивания и разбора тест-кейсов.
     * <p>
     * Ответ 502 с текстом исключения; событие пишется в лог уровня WARN.
     *
     * @param e ошибка получения источника тест-кейсов
     * @return ответ с HTTP 502
     */
    @ExceptionHandler(FetchException.class)
    public ResponseEntity<ErrorResponse> fetch(FetchException e) {
        log.warn("Fetch error: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    /**
     * Обрабатывает ошибки планирования шага, в том числе недоступность или неверный ответ LLM.
     * <p>
     * Ответ 502 с текстом исключения; событие пишется в лог уровня WARN.
     *
     * @param e ошибка генерации плана
     * @return ответ с HTTP 502
     */
    @ExceptionHandler(GenerationException.class)
    public ResponseEntity<ErrorResponse> gen(GenerationException e) {
        log.warn("Generation error: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    /**
     * Обрабатывает ошибки файлового хранилища.
     * <p>
     * Ответ 500 с сообщением {@code Ошибка хранилища: <текст>}; событие пишется в лог уровня ERROR со стеком.
     *
     * @param e ошибка чтения или записи хранилища
     * @return ответ с HTTP 500
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> storage(StorageException e) {
        log.error("Storage error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Ошибка хранилища: " + e.getMessage());
    }

    /**
     * Обрабатывает все прочие исключения, включая ошибки разбора тела запроса и ошибки Spring MVC
     * (неизвестный путь, неподдерживаемый метод). Из-за перехвата {@link Exception} такие ошибки
     * возвращаются со статусом 500.
     * <p>
     * Клиенту отдаётся общее сообщение «Внутренняя ошибка сервера»; событие пишется в лог уровня ERROR со стеком.
     *
     * @param e любое необработанное исключение
     * @return ответ с HTTP 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> internal(Exception e) {
        log.error("Unhandled error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера");
    }

    /**
     * Формирует тело ошибки с текущим временем и кодом статуса.
     *
     * @param status  HTTP-статус ответа
     * @param message текст сообщения
     * @return ответ с {@link ErrorResponse} в теле
     */
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), status.value(), message));
    }
}
