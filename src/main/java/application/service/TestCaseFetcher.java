package application.service;

import application.config.AppProperties;
import application.dto.Dtos.ParsedTestCase;
import application.exception.FetchException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TestCaseFetcher {

    private final RestClient http;
    private final TestCaseParser parser;
    private final AppProperties appProps;
    private final Set<String> allowedHosts;

    private static final Pattern INDEX_PATTERN =
            Pattern.compile("^(.*?)__?index(?:_\\d+)?\\.md$");

    private static final int MAX_VERSION_PROBE = 50;

    public TestCaseFetcher(RestClient fileFetchRestClient,
                           TestCaseParser parser,
                           AppProperties appProps) {
        this.http = fileFetchRestClient;
        this.parser = parser;
        this.appProps = appProps;
        var cfg = appProps.getDownload().getAllowedHosts();
        this.allowedHosts = (cfg == null || cfg.isEmpty())
                ? Set.of("localhost")
                : cfg.stream().map(s -> s.toLowerCase().trim()).collect(Collectors.toUnmodifiableSet());
        log.info("TestCaseFetcher: allowedHosts = {}", allowedHosts);
    }

    public List<ParsedTestCase> fetchAll(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("sourceUrl обязателен");
        }
        String trimmed = sourceUrl.trim();

        if (trimmed.endsWith(".md")) {
            log.info("Скачиваем индекс напрямую: {}", trimmed);
            String indexMd = getWithVersionFallback(trimmed);
            String baseDirUrl = trimmed.substring(0, trimmed.lastIndexOf('/') + 1);
            String indexFilename = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            String prefix = extractPrefix(indexFilename);
            if (!parser.isIndexMarkdown(indexMd)) {
                return List.of(parseSingle(trimmed, indexMd));
            }
            return fetchFromIndex(indexMd, baseDirUrl, prefix);
        }

        String baseDirUrl = normalizeDirUrl(trimmed);
        String indexUrl = baseDirUrl + "_index.md";
        try {
            String indexMd = getWithVersionFallback(indexUrl);
            if (!parser.isIndexMarkdown(indexMd)) {
                return List.of(parseSingle(indexUrl, indexMd));
            }
            return fetchFromIndex(indexMd, baseDirUrl, "");
        } catch (Exception e) {
            log.warn("Не удалось скачать _index.md ({}): {}", indexUrl, e.getMessage());
            throw new FetchException("Не удалось получить _index.md по " + indexUrl, e);
        }
    }

    private String extractPrefix(String indexFilename) {
        if (indexFilename == null || indexFilename.isEmpty()) return "";
        Matcher m = INDEX_PATTERN.matcher(indexFilename);
        if (m.matches()) {
            String prefix = m.group(1);
            if (prefix.isEmpty()) return "";
            return prefix.endsWith("_") ? prefix : prefix + "_";
        }
        return "";
    }

    private List<ParsedTestCase> fetchFromIndex(String indexMd, String baseDirUrl, String prefix) {
        List<TestCaseParser.IndexEntry> entries = parser.parseIndex(indexMd);
        if (entries.isEmpty()) {
            throw new FetchException("_index.md не содержит ссылок на тест-кейсы: " + baseDirUrl);
        }
        List<ParsedTestCase> result = new ArrayList<>();
        for (var e : entries) {
            String relFilename = parser.toSafeFilename(e.relativePath());
            if (relFilename == null) {
                log.warn("Пропускаю некорректное имя файла из index: {}", e.relativePath());
                continue;
            }
            String actualFilename = relFilename;
            if (!prefix.isEmpty() && !relFilename.startsWith(prefix)) {
                actualFilename = prefix + relFilename;
            }
            String fileUrl = baseDirUrl + actualFilename;
            try {
                // FIX (index desync): если файл X.md не найден, пробуем X_N.md
                // и берём максимальный N. Это защита от рассинхрона индекса
                // первого агента, где _index.md ссылается на «чистое» имя,
                // а реальный файл сохранён как X_2.md / X_3.md.
                String md = getWithVersionFallback(fileUrl);
                String effectiveFilename = lastResolvedFilename.get();
                result.add(parser.parse(md, effectiveFilename));
                saveCopy(effectiveFilename, md);
            } catch (Exception ex) {
                log.warn("Не удалось скачать {}: {}", fileUrl, ex.getMessage());
            }
        }
        if (result.isEmpty()) {
            throw new FetchException("Не удалось скачать ни одного тест-кейса из " + baseDirUrl);
        }
        log.info("Скачано тест-кейсов: {}", result.size());
        return result;
    }

    /**
     * ThreadLocal: последнее фактически скачанное имя (с суффиксом версии).
     */
    private final ThreadLocal<String> lastResolvedFilename = ThreadLocal.withInitial(() -> null);

    private ParsedTestCase parseSingle(String url, String md) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        String safe = parser.toSafeFilename(name);
        if (safe == null) safe = "testcase.md";
        saveCopy(safe, md);
        return parser.parse(md, safe);
    }

    /**
     * FIX (index desync): если url не найден (404/пустой), пробуем версии
     * base_2.md, base_3.md, … base_MAX.md и берём максимальную.
     * Так мы не зависим от того, что _index.md ссылается на «каноническое» имя.
     */
    private String getWithVersionFallback(String url) {
        lastResolvedFilename.set(url.substring(url.lastIndexOf('/') + 1));
        // 1) пробуем исходный URL
        try {
            String body = get(url);
            if (body != null && !body.isBlank()) {
                return body;
            }
        } catch (FetchException e) {
            // 404/ошибка — переходим к версионному поиску
            log.debug("Прямое скачивание {} не удалось: {}", url, e.getMessage());
        }

        // 2) пробуем _2, _3, … до MAX_VERSION_PROBE
        int dot = url.lastIndexOf('.');
        if (dot < 0) {
            throw new FetchException("Некорректный URL без расширения: " + url);
        }
        String base = url.substring(0, dot);
        String ext = url.substring(dot);
        String bestBody = null;
        String bestFilename = null;
        for (int i = 2; i <= MAX_VERSION_PROBE; i++) {
            String candidate = base + "_" + i + ext;
            try {
                String body = get(candidate);
                if (body != null && !body.isBlank()) {
                    bestBody = body;
                    bestFilename = candidate.substring(candidate.lastIndexOf('/') + 1);
                    // не прерываемся — ищем максимальную версию
                }
            } catch (Exception ignored) {
            }
        }
        if (bestBody == null) {
            throw new FetchException("Файл не найден ни в одной версии: " + url);
        }
        lastResolvedFilename.set(bestFilename);
        log.warn("Файл {} не найден, использована версия {} (индекс устарел)",
                url.substring(url.lastIndexOf('/') + 1), bestFilename);
        return bestBody;
    }

    private String get(String url) {
        URI uri = validate(url);
        try {
            var body = http.get().uri(uri).retrieve().body(String.class);
            if (body == null || body.isBlank()) throw new FetchException("Пустой ответ: " + url);
            return body;
        } catch (FetchException e) {
            throw e;
        } catch (Exception e) {
            throw new FetchException("Ошибка скачивания " + url + ": " + e.getMessage(), e);
        }
    }

    private URI validate(String raw) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный URL: " + raw);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("URL должен быть http(s): " + raw);
        }
        String host = uri.getHost();
        if (host == null || !allowedHosts.contains(host.toLowerCase())) {
            throw new IllegalArgumentException("Хост не разрешён: " + host + ". Разрешены: " + allowedHosts);
        }
        return uri;
    }

    private String normalizeDirUrl(String url) {
        String trimmed = url.trim();
        if (trimmed.endsWith(".md")) {
            int slash = trimmed.lastIndexOf('/');
            return slash >= 0 ? trimmed.substring(0, slash + 1) : trimmed;
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private void saveCopy(String filename, String content) {
        try {
            Path dir = appProps.getDownload().getDirectory();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(filename), content);
        } catch (Exception e) {
            log.warn("Не удалось сохранить копию {}: {}", filename, e.getMessage());
        }
    }
}
