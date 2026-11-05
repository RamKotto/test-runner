package application.controller;

import application.repository.ArtifactRepository;
import application.repository.ArtifactRepository.StoredArtifact;
import application.service.EnrichedStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Выдача артефактов из БД: {@code GET /api/files/{id}} и архив прогона {@code GET /api/files/runs/{taskId}/archive}.
 * <p>
 * Некорректный или неизвестный UUID отвечает HTTP 400 с сообщением «Файл не найден»
 * ({@link application.exception.GlobalExceptionHandler}).
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final ArtifactRepository artifacts;
    private final EnrichedStorageService enriched;

    /**
     * Возвращает артефакт по UUID как вложение.
     *
     * @param id UUID артефакта
     * @return ответ 200 с телом файла, {@code Content-Type} из артефакта и {@code Content-Disposition}
     * @throws IllegalArgumentException UUID некорректен, файла нет или срок истёк (HTTP 400)
     */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        UUID uuid = EnrichedStorageService.parseUuid(id, "Файл не найден");
        StoredArtifact artifact = artifacts.findById(uuid)
                .orElseThrow(() -> new IllegalArgumentException("Файл не найден"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.filename(), StandardCharsets.UTF_8).build().toString())
                .contentLength(artifact.content().length)
                .body(artifact.content());
    }

    /**
     * Возвращает ZIP-архив обогащённых кейсов и индекса одного прогона.
     *
     * @param taskId идентификатор прогона (UUID)
     * @return ответ 200 с телом {@code application/zip}
     * @throws IllegalArgumentException taskId некорректен либо файлов прогона нет (HTTP 400)
     */
    @GetMapping("/runs/{taskId}/archive")
    public ResponseEntity<byte[]> runArchive(@PathVariable String taskId) {
        UUID runId = EnrichedStorageService.parseUuid(taskId, "Обогащённые кейсы для прогона не найдены");
        byte[] archive = enriched.buildRunArchive(runId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("enriched-" + runId + ".zip").build().toString())
                .contentLength(archive.length)
                .body(archive);
    }
}
