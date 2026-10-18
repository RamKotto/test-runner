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
import java.util.stream.Collectors;

@Slf4j
@Service
public class TestCaseFetcher {

    private final RestClient http;
    private final TestCaseParser parser;
    private final AppProperties appProps;
    private final Set<String> allowedHosts;

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
        String baseDirUrl = normalizeDirUrl(sourceUrl);
        String indexUrl = baseDirUrl + "_index.md";

        List<TestCaseParser.IndexEntry> entries;
        try {
            String indexMd = get(indexUrl);
            if (!parser.isIndexMarkdown(indexMd)) {
                return List.of(parseSingle(indexUrl, indexMd));
            }
            entries = parser.parseIndex(indexMd);
        } catch (Exception e) {
            log.warn("Не удалось скачать _index.md ({}): {}", indexUrl, e.getMessage());
            throw new FetchException("Не удалось получить _index.md по " + indexUrl, e);
        }

        if (entries.isEmpty()) {
            throw new FetchException("_index.md не содержит ссылок на тест-кейсы: " + indexUrl);
        }

        List<ParsedTestCase> result = new ArrayList<>();
        for (var e : entries) {
            String filename = parser.toSafeFilename(e.relativePath());
            if (filename == null) {
                log.warn("Пропускаю некорректное имя файла из index: {}", e.relativePath());
                continue;
            }
            String fileUrl = baseDirUrl + filename;
            try {
                String md = get(fileUrl);
                result.add(parser.parse(md, filename));
                saveCopy(filename, md);
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
        if (url == null || url.isBlank()) throw new IllegalArgumentException("sourceUrl обязателен");
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
