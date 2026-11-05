package application.exception;

import application.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

/**
 * Глобальный обработчик исключений REST-контроллеров.
 * <p>
 * Преобразует исключения в {@link ErrorResponse} и HTTP-статус. Сопоставление:
 * {@link IllegalArgumentException} → 400; {@link FetchException} и {@link GenerationException} → 502;
 * {@link StorageException} → 500 с префиксом сообщения (ошибка сборки архива); ошибки Spring MVC: 405, 415, 404 и 400
 * (неверный запрос, тело, параметры); любое другое исключение → 500 с общим текстом.
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
     * Обрабатывает конфликт версий сессии уточнений.
     *
     * @param e исключение конфликта
     * @return ответ с HTTP 409
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
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
     * Обрабатывает ошибки сборки архива прогона (хранение только в БД).
     * <p>
     * Ответ 500 с сообщением {@code Ошибка хранилища: <текст>}; событие пишется в лог уровня ERROR со стеком.
     *
     * @param e ошибка сборки ZIP-архива прогона
     * @return ответ с HTTP 500
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> storage(StorageException e) {
        log.error("Storage error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Ошибка хранилища: " + e.getMessage());
    }

    /**
     * Обрабатывает неподдерживаемый HTTP-метод.
     * <p>
     * RFC 9110 требует заголовок {@code Allow} в ответе 405. Собственный обработчик его не получает от Spring
     * автоматически, поэтому он выставляется из {@link HttpRequestMethodNotSupportedException#getSupportedHttpMethods()}.
     *
     * @param e исключение Spring MVC
     * @return ответ с HTTP 405 и заголовком {@code Allow}
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        var allowed = e.getSupportedHttpMethods();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(h -> {
                    if (allowed != null && !allowed.isEmpty()) {
                        h.setAllow(allowed);
                    }
                })
                .body(new ErrorResponse(Instant.now(), HttpStatus.METHOD_NOT_ALLOWED.value(),
                        "Метод не поддерживается: " + e.getMethod()));
    }

    /**
     * Обрабатывает неподдерживаемый Content-Type запроса.
     *
     * @param e исключение Spring MVC
     * @return ответ с HTTP 415
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> mediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый тип содержимого запроса: " + e.getContentType());
    }

    /**
     * Обрабатывает отсутствующую часть multipart-запроса.
     *
     * @param e исключение Spring MVC
     * @return ответ с HTTP 400
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> missingPart(MissingServletRequestPartException e) {
        return build(HttpStatus.BAD_REQUEST, "Отсутствует часть запроса: " + e.getRequestPartName());
    }

    /**
     * Обрабатывает отсутствующий обязательный параметр запроса.
     *
     * @param e исключение Spring MVC
     * @return ответ с HTTP 400
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST, "Отсутствует обязательный параметр: " + e.getParameterName());
    }

    /**
     * Обрабатывает тело запроса, которое не удалось прочитать (пустое, неверный JSON и т. п.).
     *
     * @param e исключение чтения тела запроса
     * @return ответ с HTTP 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> notReadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "Некорректное тело запроса");
    }

    /**
     * Обрабатывает параметр пути или запроса, который не приводится к нужному типу.
     *
     * @param e исключение преобразования аргумента
     * @return ответ с HTTP 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "Неверное значение параметра: " + e.getName());
    }

    /**
     * Обрабатывает запрос к несуществующему ресурсу (неизвестный путь или файл статики).
     *
     * @param e исключение Spring MVC
     * @return ответ с HTTP 404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "Ресурс не найден: " + e.getResourcePath());
    }

    /**
     * Обрабатывает все прочие исключения как запасной вариант.
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
