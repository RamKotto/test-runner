package application.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий бизнес-артефактов в таблице {@code qa.artifacts}: обогащённые кейсы и индексы прогонов.
 * <p>
 * Внутри одного прогона файл с тем же {@code filename} и {@code kind} заменяется: старая строка
 * удаляется, новая вставляется в одной транзакции. Все файлы набора пишутся в одной транзакции
 * ({@link #replaceRunFiles}). Срок хранения строк — 30 дней ({@code expires_at}); владелец
 * строк — сервис {@code test-runner}.
 */
@Repository
@RequiredArgsConstructor
public class ArtifactRepository {

    /**
     * Значение {@code owner_service} для артефактов этого сервиса.
     */
    public static final String OWNER = "test-runner";

    /**
     * Новый артефакт для записи. Идентификатор создаётся заранее, чтобы ссылка на файл была известна
     * до коммита транзакции.
     *
     * @param id          UUID артефакта (он же часть ссылки {@code /api/files/{id}})
     * @param kind        вид артефакта: {@code enriched_case}, {@code enriched_index}, {@code test_case}, {@code index}
     * @param filename    имя файла
     * @param contentType MIME-тип содержимого
     * @param content     байты файла
     */
    public record NewArtifact(UUID id, String kind, String filename, String contentType, byte[] content) {

        /**
         * Создаёт новый артефакт с новым UUID.
         *
         * @param kind        вид артефакта
         * @param filename    имя файла
         * @param contentType MIME-тип содержимого
         * @param content     байты файла
         * @return артефакт, готовый к записи
         */
        public static NewArtifact of(String kind, String filename, String contentType, byte[] content) {
            return new NewArtifact(UUID.randomUUID(), kind, filename, contentType, content);
        }
    }

    /**
     * Сохранённый артефакт, прочитанный из БД.
     *
     * @param id          UUID артефакта
     * @param kind        вид артефакта
     * @param filename    имя файла
     * @param contentType MIME-тип содержимого
     * @param content     байты файла
     * @param createdAt   время создания
     */
    public record StoredArtifact(UUID id, String kind, String filename, String contentType,
                                 byte[] content, Instant createdAt) {

        /**
         * Возвращает содержимое как текст в UTF-8.
         *
         * @return текст файла
         */
        public String contentAsText() {
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    /**
     * Отображение строки {@code qa.artifacts} в {@link StoredArtifact}.
     */
    private static final RowMapper<StoredArtifact> ROW = (rs, rowNum) -> new StoredArtifact(
            rs.getObject("id", UUID.class),
            rs.getString("kind"),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getBytes("content"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    /**
     * Записывает набор файлов одного прогона в одной транзакции.
     * <p>
     * Для каждого файла сначала удаляется строка с тем же прогоном, видом и именем, затем вставляется новая.
     * Если в наборе два файла с одинаковыми видом и именем, побеждает последний.
     *
     * @param runId идентификатор прогона (taskId)
     * @param files файлы набора
     */
    @Transactional
    public void replaceRunFiles(UUID runId, List<NewArtifact> files) {
        for (NewArtifact f : files) {
            jdbc.update("DELETE FROM qa.artifacts WHERE run_id = ? AND kind = ? AND filename = ?",
                    runId, f.kind(), f.filename());
            jdbc.update("""
                    INSERT INTO qa.artifacts
                        (id, kind, owner_service, run_id, filename, content_type, content, size_bytes, sha256, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now() + interval '30 days')
                    """,
                    f.id(), f.kind(), OWNER, runId, f.filename(), f.contentType(),
                    f.content(), (long) f.content().length, sha256(f.content()));
        }
    }

    /**
     * Ищет действующий артефакт по UUID.
     *
     * @param id UUID артефакта
     * @return артефакт либо пусто, если его нет или срок истёк
     */
    public Optional<StoredArtifact> findById(UUID id) {
        return jdbc.query("""
                SELECT id, kind, filename, content_type, content, created_at
                FROM qa.artifacts
                WHERE id = ? AND expires_at > now()
                """, ROW, id).stream().findFirst();
    }

    /**
     * Возвращает действующие артефакты прогона заданного вида в порядке создания.
     *
     * @param runId идентификатор прогона (taskId)
     * @param kind  вид артефакта
     * @return список артефактов; пустой, если ничего не найдено
     */
    public List<StoredArtifact> findRunFiles(UUID runId, String kind) {
        return jdbc.query("""
                SELECT id, kind, filename, content_type, content, created_at
                FROM qa.artifacts
                WHERE run_id = ? AND kind = ? AND expires_at > now()
                ORDER BY created_at, id
                """, ROW, runId, kind);
    }

    /**
     * Удаляет артефакты этого сервиса, срок которых истёк.
     *
     * @return число удалённых строк
     */
    @Transactional
    public int deleteExpired() {
        return jdbc.update("DELETE FROM qa.artifacts WHERE owner_service = ? AND expires_at < now()", OWNER);
    }

    /**
     * Вычисляет SHA-256 содержимого в шестнадцатеричном виде.
     *
     * @param bytes содержимое
     * @return хеш в нижнем регистре
     * @throws IllegalStateException алгоритм SHA-256 недоступен в среде выполнения
     */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
