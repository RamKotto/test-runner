package application.service;

import application.config.AppProperties;
import application.exception.StorageException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichedStorageService {

    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_\\-]+\\.md$");

    private final AppProperties props;

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(props.getStorage().getDirectory());
            Files.createDirectories(props.getDownload().getDirectory());
            log.info("Обогащённые кейсы: {}", props.getStorage().getDirectory().toAbsolutePath());
            log.info("Скачанные кейсы: {}", props.getDownload().getDirectory().toAbsolutePath());
        } catch (IOException e) {
            throw new StorageException("Не удалось создать директории", e);
        }
    }

    public SavedFile save(String filename, String content) {
        if (filename == null || !SAFE_NAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Некорректное имя файла: " + filename);
        }
        Path targetDir = props.getStorage().getDirectory();
        Path staging;
        try {
            staging = Files.createTempDirectory(targetDir, ".staging-");
        } catch (IOException e) {
            throw new StorageException("Не удалось создать staging", e);
        }
        try {
            Path staged = staging.resolve(filename);
            Files.writeString(staged, content);
            Path target = targetDir.resolve(filename);
            moveAtomicallyOrFallback(staged, target);
            String downloadUrl = props.getStorage().getBaseUrl() + "/api/results/" + filename;
            return new SavedFile(filename, target, downloadUrl);
        } catch (IOException e) {
            throw new StorageException("Не удалось сохранить " + filename, e);
        } finally {
            deleteRecursively(staging);
        }
    }

    public Resource load(String filename) {
        if (filename == null || !SAFE_NAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Некорректное имя файла");
        }
        Path target = props.getStorage().getDirectory().resolve(filename).normalize();
        if (!target.startsWith(props.getStorage().getDirectory())) {
            throw new IllegalArgumentException("Некорректный путь");
        }
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Файл не найден: " + filename);
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            throw new StorageException("Некорректный путь", e);
        }
    }

    private void moveAtomicallyOrFallback(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    public record SavedFile(String filename, Path path, String downloadUrl) {
    }
}
