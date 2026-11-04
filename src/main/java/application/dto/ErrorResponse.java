package application.dto;

import java.time.Instant;

/**
 * Тело ответа об ошибке, которое возвращает {@link application.exception.GlobalExceptionHandler}.
 *
 * @param timestamp момент формирования ответа
 * @param status    HTTP-код ответа числом, например {@code 400}
 * @param message   текст ошибки для пользователя или текст исключения
 */
public record ErrorResponse(Instant timestamp, int status, String message) {
}
