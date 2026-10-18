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

    // Ищет имя _index.md, _index_2.md, __index.md, __index_3.md
    // и захватывает префикс до него
    private static final Pattern INDEX_PATTERN =
            Pattern.compile("^(.*?)__?index(?:_\\d+)?\\.md$");

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

        // Прямой URL на _index.md (или _index_2.md и т.п.)
        if (trimmed.endsWith(".md")) {
            log.info("Скачиваем индекс напрямую: {}", trimmed);
            String indexMd = get(trimmed);

            String baseDirUrl = trimmed.substring(0, trimmed.lastIndexOf('/') + 1);
            String indexFilename = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            String prefix = extractPrefix(indexFilename);

            if (!parser.isIndexMarkdown(indexMd)) {
                // Это не _index.md, а конкретный тест-кейс
                return List.of(parseSingle(trimmed, indexMd));
            }
            return fetchFromIndex(indexMd, baseDirUrl, prefix);
        }

        // URL директории
        String baseDirUrl = normalizeDirUrl(trimmed);
        String indexUrl = baseDirUrl + "_index.md";

        try {
            String indexMd = get(indexUrl);
            if (!parser.isIndexMarkdown(indexMd)) {
                return List.of(parseSingle(indexUrl, indexMd));
            }
            return fetchFromIndex(indexMd, baseDirUrl, "");
        } catch (Exception e) {
            log.warn("Не удалось скачать _index.md ({}): {}", indexUrl, e.getMessage());
            throw new FetchException("Не удалось получить _index.md по " + indexUrl, e);
        }
    }

    /**
     * Извлекает префикс из имени файла индекса.
     * Примеры:
     * "5eb5fb63-..._OPE__index.md"      -> "5eb5fb63-..._OPE_"
     * "5eb5fb63-..._OPE__index_2.md"    -> "5eb5fb63-..._OPE_"
     * "abc_index.md"                    -> "abc_"
     * "_index.md"                       -> ""
     */
    private String extractPrefix(String indexFilename) {
        if (indexFilename == null || indexFilename.isEmpty()) return "";
        Matcher m = INDEX_PATTERN.matcher(indexFilename);
        if (m.matches()) {
            String prefix = m.group(1);
            // Если в конце префикса уже есть _ (например, "abc_"), вернём как есть
            // Если нет — добавим _, чтобы было "abc_" + "TC-..."
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

            // Если в _index.md ссылка уже с префиксом — не дублируем
            String actualFilename = relFilename;
            if (!prefix.isEmpty() && !relFilename.startsWith(prefix)) {
                actualFilename = prefix + relFilename;
            }

            String fileUrl = baseDirUrl + actualFilename;
            try {
                String md = get(fileUrl);
                result.add(parser.parse(md, actualFilename));
                saveCopy(actualFilename, md);
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

    private ParsedTestCase parseSingle(String url, String md) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        String safe = parser.toSafeFilename(name);
        if (safe == null) safe = "testcase.md";
        saveCopy(safe, md);
        return parser.parse(md, safe);
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
