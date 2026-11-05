package application.service;

import application.config.AppProperties;
import application.repository.ArtifactRepository;
import application.repository.ArtifactRepository.StoredArtifact;
import application.service.EnrichedStorageService.NamedBytes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты {@link EnrichedStorageService} после перехода на БД: сборка ZIP-архива прогона из артефактов
 * (через мок {@link ArtifactRepository}), дедупликация имён, проверка UUID и формирование ссылок.
 */
class EnrichedStorageServiceTest {

    private final ArtifactRepository artifacts = mock(ArtifactRepository.class);

    /**
     * Создаёт сервис с моком репозитория и базовым URL для ссылок.
     *
     * @return сервис хранения обогащённых файлов
     */
    private EnrichedStorageService newService() {
        AppProperties props = new AppProperties();
        props.getStorage().setBaseUrl("http://test-runner:8083");
        return new EnrichedStorageService(artifacts, props);
    }

    /**
     * Создаёт артефакт для ответа репозитория.
     *
     * @param kind     вид артефакта
     * @param filename имя файла
     * @param text     содержимое
     * @return артефакт
     */
    private static StoredArtifact artifact(String kind, String filename, String text) {
        return new StoredArtifact(UUID.randomUUID(), kind, filename, EnrichedStorageService.TEXT_MARKDOWN,
                text.getBytes(StandardCharsets.UTF_8), Instant.now());
    }

    /**
     * Читает ZIP-архив и возвращает содержимое файлов по имени записи.
     *
     * @param archive байты архива
     * @return карта «имя файла → текст» в порядке записей
     * @throws IOException архив не удалось прочитать
     */
    private static Map<String, String> readArchive(byte[] archive) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /**
     * Архив содержит обогащённые кейсы и индекс прогона с их текстом.
     */
    @Test
    void buildRunArchive_containsCasesAndIndexOfRun() throws IOException {
        EnrichedStorageService service = newService();
        UUID runId = UUID.randomUUID();
        when(artifacts.findRunFiles(runId, EnrichedStorageService.KIND_CASE)).thenReturn(List.of(
                artifact(EnrichedStorageService.KIND_CASE, "TC-UI-001.md", "# TC-UI-001"),
                artifact(EnrichedStorageService.KIND_CASE, "TC-API-002.md", "# TC-API-002")));
        when(artifacts.findRunFiles(runId, EnrichedStorageService.KIND_INDEX)).thenReturn(List.of(
                artifact(EnrichedStorageService.KIND_INDEX, "_enriched_index_" + runId + ".md", "- [TC-UI-001](./TC-UI-001.md)")));

        Map<String, String> entries = readArchive(service.buildRunArchive(runId));

        assertThat(entries).containsOnlyKeys("TC-UI-001.md", "TC-API-002.md", "_enriched_index_" + runId + ".md");
        assertThat(entries.get("TC-UI-001.md")).isEqualTo("# TC-UI-001");
    }

    /**
     * Если имя встречается дважды, в архив попадает только первое вхождение: кейс идёт раньше индекса.
     */
    @Test
    void buildRunArchive_dropsDuplicateNames() throws IOException {
        EnrichedStorageService service = newService();
        UUID runId = UUID.randomUUID();
        when(artifacts.findRunFiles(runId, EnrichedStorageService.KIND_CASE)).thenReturn(List.of(
                artifact(EnrichedStorageService.KIND_CASE, "same.md", "из кейса")));
        when(artifacts.findRunFiles(runId, EnrichedStorageService.KIND_INDEX)).thenReturn(List.of(
                artifact(EnrichedStorageService.KIND_INDEX, "same.md", "из индекса")));

        Map<String, String> entries = readArchive(service.buildRunArchive(runId));

        assertThat(entries).hasSize(1);
        assertThat(entries.get("same.md")).isEqualTo("из кейса");
    }

    /**
     * Прогон без файлов не даёт архив: возвращается ошибка «не найдены».
     */
    @Test
    void buildRunArchive_unknownRun_throwsIllegalArgument() {
        EnrichedStorageService service = newService();
        UUID runId = UUID.randomUUID();

        assertThatThrownBy(() -> service.buildRunArchive(runId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдены");
    }

    /**
     * Строки, которые не являются каноническим UUID, отклоняются до обращения к БД.
     */
    @Test
    void parseUuid_acceptsOnlyCanonicalUuid() {
        String uuid = UUID.randomUUID().toString();

        assertThat(EnrichedStorageService.parseUuid(uuid, "x")).isEqualTo(UUID.fromString(uuid));
        assertThat(EnrichedStorageService.parseUuid(uuid.toUpperCase(), "x")).isEqualTo(UUID.fromString(uuid));
        for (String bad : List.of("../../etc", "12345", "", "{" + uuid + "}", uuid + "0")) {
            assertThatThrownBy(() -> EnrichedStorageService.parseUuid(bad, "Файл не найден"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Файл не найден");
        }
        assertThatThrownBy(() -> EnrichedStorageService.parseUuid(null, "Файл не найден"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Ссылки на файл и архив формируются от базового URL по контракту.
     */
    @Test
    void urls_followContract() {
        EnrichedStorageService service = newService();
        UUID id = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        assertThat(service.fileUrl(id)).isEqualTo("http://test-runner:8083/api/files/" + id);
        assertThat(service.archiveUrl(runId)).isEqualTo("http://test-runner:8083/api/files/runs/" + runId + "/archive");
    }

    /**
     * Сборка ZIP из байтов: содержимое сохраняется, повторяющиеся имена отбрасываются.
     */
    @Test
    void zip_roundTripsContentAndKeepsFirstName() throws IOException {
        byte[] archive = EnrichedStorageService.zip(List.of(
                new NamedBytes("a.md", "первый".getBytes(StandardCharsets.UTF_8)),
                new NamedBytes("b.md", "второй".getBytes(StandardCharsets.UTF_8)),
                new NamedBytes("a.md", "повтор".getBytes(StandardCharsets.UTF_8))));

        Map<String, String> entries = readArchive(archive);

        assertThat(entries).containsExactly(
                Map.entry("a.md", "первый"),
                Map.entry("b.md", "второй"));
    }
}
