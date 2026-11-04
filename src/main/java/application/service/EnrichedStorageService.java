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

/**
 * Хранилище результатов прогона: обогащённых кейсов и индекса.
 * <p>
 * Место в конвейере: {@code RunController} сохраняет Markdown-файлы через {@link #save},
 * {@code ResultFileController} отдаёт их через {@link #load}. Файлы лежат в каталоге
 * {@code app.storage.directory}; имя файла должно соответствовать {@link #SAFE_NAME},
 * поэтому путь к файлу не выходит за пределы каталога.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichedStorageService {

    /**
     * Безопасное имя файла результата: латинские буквы, цифры, «_» и «-», расширение {@code .md}.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_\\-]+\\.md$");

    /**
     * Настройки приложения: каталоги хранения, базовый URL для ссылок на файлы.
     */
    private final AppProperties props;

    /**
     * Создаёт каталоги хранения результатов и скачанных кейсов при старте приложения.
     *
     * @throws StorageException каталоги создать не удалось
     */
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

    /**
     * Сохраняет файл результата и возвращает ссылку на него.
     * <p>
     * Содержимое сначала пишется во временный каталог {@code .staging-*} внутри каталога
     * хранения, затем перемещается в целевой файл с заменой: атомарно, если файловая
     * система это поддерживает, иначе обычным перемещением. Временный каталог удаляется
     * в любом случае. Ссылка имеет вид {@code app.storage.base-url}{@code /api/results/<filename>}.
     *
     * @param filename имя файла, например {@code TC-1.md}; должно соответствовать {@link #SAFE_NAME}
     * @param content  текст в кодировке UTF-8
     * @return сохранённый файл с путём и ссылкой для скачивания
     * @throws IllegalArgumentException имя файла некорректно
     * @throws StorageException         не удалось создать временный каталог или записать файл
     */
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

    /**
     * Возвращает ресурс сохранённого файла для отдачи клиенту.
     * <p>
     * Путь нормализуется, и результат должен оставаться внутри каталога хранения; иначе
     * запрос отклоняется. Проверка по {@link #SAFE_NAME} выполняется первой.
     *
     * @param filename имя файла результата
     * @return ресурс файла
     * @throws IllegalArgumentException имя некорректно, путь выходит за каталог или файл не найден
     * @throws StorageException         путь нельзя преобразовать в URL
     */
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

    /**
     * Перемещает файл с заменой назначения, по возможности атомарно.
     * Если файловая система не поддерживает атомарное перемещение, выполняется обычное
     * перемещение с заменой.
     *
     * @param src исходный файл
     * @param dst целевой путь
     * @throws IOException перемещение не удалось
     */
    private void moveAtomicallyOrFallback(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Удаляет каталог вместе с содержимым. Ошибки удаления игнорируются: временный
     * каталог не должен ломать ответ клиенту.
     *
     * @param dir каталог для удаления
     */
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

    /**
     * Результат сохранения файла.
     *
     * @param filename    имя файла
     * @param path        путь к сохранённому файлу
     * @param downloadUrl ссылка для скачивания через API результатов
     */
    public record SavedFile(String filename, Path path, String downloadUrl) {
    }
}
