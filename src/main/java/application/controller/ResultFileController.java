package application.controller;

import application.service.EnrichedStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-контроллер выдачи обогащённых Markdown-файлов из хранилища {@link EnrichedStorageService}.
 * <p>
 * Эндпоинт: {@code GET /api/results/{filename}}. Файл отдаётся как вложение с типом
 * {@code text/markdown; charset=UTF-8}.
 */
@RestController
@RequestMapping("/api/results")
@RequiredArgsConstructor
public class ResultFileController {

    private final EnrichedStorageService storage;

    /**
     * Возвращает файл результата как вложение.
     * <p>
     * Заголовок {@code Content-Length} выставляется, если размер файла определён. Имя файла проверяет
     * {@link EnrichedStorageService#load(String)}: допустимы латинские буквы, цифры, {@code _} и {@code -}
     * и расширение {@code .md}. Ошибки проверки имени и отсутствие файла возвращаются как HTTP 400
     * через {@link application.exception.GlobalExceptionHandler}.
     *
     * @param filename имя файла в хранилище, например {@code TC-UI-001.md}
     * @return ответ 200 с телом файла и заголовками {@code Content-Type} и {@code Content-Disposition}
     * @throws IllegalArgumentException если имя некорректно или файл не найден (HTTP 400)
     */
    @GetMapping("/{filename}")
    public ResponseEntity<Resource> download(@PathVariable String filename) {
        Resource resource = storage.load(filename);
        long length;
        try {
            length = resource.contentLength();
        } catch (Exception e) {
            length = -1;
        }
        var builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resource.getFilename() + "\"");
        if (length >= 0) builder.contentLength(length);
        return builder.body(resource);
    }
}
