package application.service;

import application.config.AppProperties;
import application.exception.StorageException;
import application.repository.ArtifactRepository;
import application.repository.ArtifactRepository.StoredArtifact;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Обогащённые файлы прогона в БД: ссылки на артефакты и ZIP-архив прогона.
 * <p>
 * Место в конвейере: {@code RunController} формирует файлы обогащённых кейсов и индекс и передаёт их в
 * {@link RunSessionService#commit}, который записывает их через {@link ArtifactRepository} в одной транзакции с сессией;
 * {@code FileController} отдаёт архив
 * через {@link #buildRunArchive(UUID)}. Диск не используется.
 */
@Service
@RequiredArgsConstructor
public class EnrichedStorageService {

    /**
     * Вид артефакта: обогащённый тест-кейс.
     */
    public static final String KIND_CASE = "enriched_case";

    /**
     * Вид артефакта: индекс обогащённых кейсов прогона.
     */
    public static final String KIND_INDEX = "enriched_index";

    /**
     * MIME-тип Markdown-файлов.
     */
    public static final String TEXT_MARKDOWN = "text/markdown; charset=UTF-8";

    /**
     * Канонический UUID: 8-4-4-4-12 шестнадцатеричных символов.
     */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Файл для архива: имя записи и содержимое.
     *
     * @param name    имя записи в архиве
     * @param content байты файла
     */
    public record NamedBytes(String name, byte[] content) {
    }

    private final ArtifactRepository artifacts;
    private final AppProperties props;

    /**
     * Проверяет, что строка является UUID, и возвращает его.
     *
     * @param value        проверяемая строка; может быть {@code null}
     * @param errorMessage сообщение об ошибке, если строка некорректна
     * @return разобранный UUID
     * @throws IllegalArgumentException строка не является UUID в каноническом виде
     */
    public static UUID parseUuid(String value, String errorMessage) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return UUID.fromString(value);
    }

    /**
     * Возвращает ссылку для скачивания артефакта по UUID.
     *
     * @param id UUID артефакта
     * @return адрес вида {@code <baseUrl>/api/files/<uuid>}
     */
    public String fileUrl(UUID id) {
        return props.getStorage().getBaseUrl() + "/api/files/" + id;
    }

    /**
     * Возвращает ссылку на ZIP-архив обогащённых файлов прогона.
     *
     * @param runId идентификатор прогона
     * @return адрес вида {@code <baseUrl>/api/files/runs/<taskId>/archive}
     */
    public String archiveUrl(UUID runId) {
        return props.getStorage().getBaseUrl() + "/api/files/runs/" + runId + "/archive";
    }

    /**
     * Собирает ZIP-архив из артефактов прогона: обогащённые кейсы и индекс, без дубликатов имён.
     *
     * @param runId идентификатор прогона
     * @return байты ZIP-архива
     * @throws IllegalArgumentException файлов прогона нет
     * @throws StorageException         архив не удалось собрать
     */
    public byte[] buildRunArchive(UUID runId) {
        List<NamedBytes> entries = new ArrayList<>();
        for (String kind : List.of(KIND_CASE, KIND_INDEX)) {
            for (StoredArtifact a : artifacts.findRunFiles(runId, kind)) {
                entries.add(new NamedBytes(a.filename(), a.content()));
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Обогащённые кейсы для прогона не найдены: " + runId);
        }
        return zip(entries);
    }

    /**
     * Оставляет первое вхождение каждого имени файла.
     *
     * @param entries файлы в порядке приоритета
     * @return файлы без повторов имён
     */
    public static List<NamedBytes> uniqueByName(List<NamedBytes> entries) {
        Map<String, NamedBytes> byName = new LinkedHashMap<>();
        for (NamedBytes e : entries) {
            byName.putIfAbsent(e.name(), e);
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Собирает ZIP-архив в памяти. Имена без повторов; файлы лежат в корне архива, без каталогов,
     * потому что индекс ссылается на кейсы относительными путями {@code ./<файл>}.
     *
     * @param entries файлы архива
     * @return байты архива
     * @throws StorageException запись архива не удалась
     */
    public static byte[] zip(List<NamedBytes> entries) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (NamedBytes e : uniqueByName(entries)) {
                zip.putNextEntry(new ZipEntry(e.name()));
                zip.write(e.content());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new StorageException("Не удалось собрать архив прогона", e);
        }
        return buffer.toByteArray();
    }
}
