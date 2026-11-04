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

/**
 * Скачивание тест-кейсов по HTTP(S) с поддержкой индекса {@code _index.md} и версий файлов.
 * <p>
 * Место в конвейере: {@code RunController} вызывает {@link #fetchAll(String)} и получает
 * список {@link ParsedTestCase}, который передаётся в {@link TestCaseRunnerService}.
 * Каждый скачанный файл сохраняется копией в {@code app.download.directory}. Хосты
 * ограничены списком {@code app.download.allowed-hosts}.
 */
@Slf4j
@Service
public class TestCaseFetcher {

    /**
     * REST-клиент для загрузки файлов.
     */
    private final RestClient http;

    /**
     * Парсер индекса и файлов кейсов.
     */
    private final TestCaseParser parser;

    /**
     * Настройки приложения; из них берутся каталог загрузок.
     */
    private final AppProperties appProps;

    /**
     * Разрешённые хосты в нижнем регистре; заполняется из {@code app.download.allowed-hosts}.
     */
    private final Set<String> allowedHosts;

    /**
     * Выделяет префикс из имени индекса: {@code X__index.md}, {@code X_index.md} или
     * {@code X_index_N.md}. Префикс затем добавляется к именам файлов, на которые
     * ссылается индекс.
     */
    private static final Pattern INDEX_PATTERN =
            Pattern.compile("^(.*?)__?index(?:_\\d+)?\\.md$");

    /**
     * Максимальный номер версии, который проверяется при переборе {@code X_N.ext}.
     */
    private static final int MAX_VERSION_PROBE = 50;

    /**
     * Результат скачивания: фактическое имя файла (с суффиксом версии) и содержимое.
     *
     * @param filename имя файла, под которым он был найден
     * @param body     текст файла
     */
    private record DownloadedFile(String filename, String body) {
    }

    /**
     * Создаёт fetcher и читает список разрешённых хостов.
     * <p>
     * Хосты приводятся к нижнему регистру и обрезаются. Если список не задан или пуст,
     * разрешается только {@code localhost}. Это отличается от {@link TargetUrlGuard},
     * где пустой список запрещает все хосты.
     *
     * @param fileFetchRestClient REST-клиент для скачивания файлов
     * @param parser              парсер индекса и кейсов
     * @param appProps            настройки приложения
     */
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

    /**
     * Скачивает и разбирает все тест-кейсы по исходному URL.
     * <p>
     * Если URL оканчивается на {@code .md}, файл скачивается напрямую. Если это индекс,
     * его ссылки раскрываются, иначе файл разбирается как один кейс. Во всех остальных
     * случаях URL трактуется как каталог: из него скачивается {@code _index.md}. Если
     * индекса нет (документ не является индексом), каталог даёт один кейс из этого файла.
     *
     * @param sourceUrl URL каталога или файла; не должен быть пустым
     * @return список разобранных кейсов в порядке индекса
     * @throws IllegalArgumentException {@code sourceUrl} пуст, либо одиночный файл, переданный
     *                                  напрямую, не удалось разобрать или URL запрещён
     * @throws FetchException           индекс не скачан, пуст, либо не скачан или не разобран
     *                                  хотя бы один файл из индекса (см. {@link #fetchFromIndex})
     */
    public List<ParsedTestCase> fetchAll(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("sourceUrl обязателен");
        }
        String trimmed = sourceUrl.trim();

        if (trimmed.endsWith(".md")) {
            log.info("Скачиваем индекс напрямую: {}", trimmed);
            String indexMd = getWithVersionFallback(trimmed).body();
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
        String indexMd;
        try {
            indexMd = getWithVersionFallback(indexUrl).body();
        } catch (Exception e) {
            log.warn("Не удалось скачать _index.md ({}): {}", indexUrl, e.getMessage());
            throw new FetchException("Не удалось получить _index.md по " + indexUrl + ": " + e.getMessage(), e);
        }
        if (!parser.isIndexMarkdown(indexMd)) {
            try {
                return List.of(parseSingle(indexUrl, indexMd));
            } catch (Exception e) {
                throw new FetchException("Не удалось разобрать " + indexUrl + ": " + e.getMessage(), e);
            }
        }
        return fetchFromIndex(indexMd, baseDirUrl, "");
    }

    /**
     * Извлекает префикс имён кейсов из имени индекса.
     *
     * @param indexFilename имя файла индекса без пути
     * @return префикс с завершающим «_» (например, {@code X_}) либо пустая строка,
     *         если имя не совпадает с {@link #INDEX_PATTERN} или префикс пуст
     */
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

    /**
     * Скачивает все файлы, на которые ссылается индекс, и разбирает их.
     * <p>
     * Для каждой ссылки проверяется безопасность имени ({@link TestCaseParser#toSafeFilename}).
     * Если задан {@code prefix} и имя его не содержит, префикс добавляется. Файл
     * загружается с перебором версий ({@link #getWithVersionFallback}), разбирается и
     * сохраняется копией. Если хоть один файл не скачан или не разобран, бросается
     * {@link FetchException} со списком проблем, и неполный набор не возвращается.
     * Копии файлов, которые уже были скачаны, при этом остаются в каталоге загрузок.
     *
     * @param indexMd    текст индекса
     * @param baseDirUrl URL каталога, относительно которого заданы ссылки
     * @param prefix     префикс имён файлов или пустая строка
     * @return все разобранные кейсы в порядке индекса
     * @throws FetchException индекс пуст (нет ссылок) или хотя бы один файл не скачан или не разобран
     */
    private List<ParsedTestCase> fetchFromIndex(String indexMd, String baseDirUrl, String prefix) {
        List<TestCaseParser.IndexEntry> entries = parser.parseIndex(indexMd);
        if (entries.isEmpty()) {
            throw new FetchException("_index.md не содержит ссылок на тест-кейсы: " + baseDirUrl);
        }
        List<ParsedTestCase> result = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (var e : entries) {
            String relFilename = parser.toSafeFilename(e.relativePath());
            if (relFilename == null) {
                problems.add("некорректное имя файла в _index.md: `" + e.relativePath() + "`");
                continue;
            }
            String actualFilename = relFilename;
            if (!prefix.isEmpty() && !relFilename.startsWith(prefix)) {
                actualFilename = prefix + relFilename;
            }
            String fileUrl = baseDirUrl + actualFilename;
            try {
                DownloadedFile file = getWithVersionFallback(fileUrl);
                result.add(parser.parse(file.body(), file.filename()));
                saveCopy(file.filename(), file.body());
            } catch (Exception ex) {
                log.warn("Не удалось скачать или разобрать {}: {}", fileUrl, ex.getMessage());
                problems.add(fileUrl + ": " + ex.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new FetchException("Не удалось скачать или разобрать " + problems.size()
                    + " из " + entries.size() + " тест-кейсов (" + baseDirUrl + "). "
                    + "Прогон остановлен, чтобы не выполнить неполный набор:\n - "
                    + String.join("\n - ", problems));
        }
        log.info("Скачано тест-кейсов: {}", result.size());
        return result;
    }

    /**
     * Разбирает один файл, переданный как самостоятельный кейс, и сохраняет его копию.
     * Если имя файла из URL небезопасно, копия сохраняется как {@code testcase.md}.
     *
     * @param url URL файла, из которого берётся имя
     * @param md  текст файла
     * @return разобранный кейс
     * @throws IllegalArgumentException файл не удалось разобрать
     */
    private ParsedTestCase parseSingle(String url, String md) {
        String name = fileName(url);
        String safe = parser.toSafeFilename(name);
        if (safe == null) safe = "testcase.md";
        saveCopy(safe, md);
        return parser.parse(md, safe);
    }

    /**
     * Скачивает файл, а при его отсутствии ищет версию {@code base_N.ext}.
     * <p>
     * Сначала пробуется сам URL. Ошибки {@link IllegalArgumentException} (невалидный URL или
     * запрещённый хост) не перебираются. Для остальных ошибок перебираются
     * {@code base_2.ext}, {@code base_3.ext} и т. д. до первой недоступной версии, и
     * возвращается последняя найденная. Номер версии не превышает {@link #MAX_VERSION_PROBE}.
     * Это защищает от рассинхрона: индекс ссылается на {@code X.md}, а файл сохранён как
     * {@code X_2.md}. Пустой ответ считается отсутствием файла. Если версия найдена,
     * пишется предупреждение.
     *
     * @param url URL файла
     * @return найденное содержимое и фактическое имя файла
     * @throws IllegalArgumentException URL невалиден или хост запрещён
     * @throws FetchException           URL без расширения, либо не найдена ни одна версия
     */
    private DownloadedFile getWithVersionFallback(String url) {
        String originalName = fileName(url);
        Exception originalFailure;
        try {
            return new DownloadedFile(originalName, get(url));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            originalFailure = e;
        }

        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        if (dot < 0 || dot < slash) {
            throw new FetchException("Некорректный URL без расширения: " + url, originalFailure);
        }
        String base = url.substring(0, dot);
        String ext = url.substring(dot);
        String bestBody = null;
        String bestName = null;
        for (int i = 2; i <= MAX_VERSION_PROBE; i++) {
            String candidate = base + "_" + i + ext;
            try {
                bestBody = get(candidate);
                bestName = fileName(candidate);
            } catch (Exception e) {
                log.debug("Версия {} недоступна, останавливаем перебор: {}", candidate, e.getMessage());
                break;
            }
        }
        if (bestBody == null) {
            throw new FetchException("Файл не найден ни в одной версии: " + url
                    + " (ошибка: " + originalFailure.getMessage() + ")", originalFailure);
        }
        log.warn("Файл {} не найден, использована версия {} (индекс устарел)", originalName, bestName);
        return new DownloadedFile(bestName, bestBody);
    }

    /**
     * Скачивает текст по URL после проверки адреса.
     *
     * @param url URL файла
     * @return непустой текст ответа
     * @throws IllegalArgumentException URL не прошёл {@link #validate(String)}
     * @throws FetchException           ответ пуст либо загрузка завершилась ошибкой
     */
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

    /**
     * Проверяет URL перед загрузкой: он должен быть абсолютным http(s)-адресом, и его хост
     * должен входить в {@link #allowedHosts} (сравнение без учёта регистра, порт не учитывается).
     *
     * @param raw URL в исходном виде
     * @return разобранный {@link URI}
     * @throws IllegalArgumentException URL синтаксически некорректен, схема не http(s) или хост не разрешён
     */
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

    /**
     * Возвращает часть URL после последнего «/». Если в URL есть query, он входит в результат.
     *
     * @param url URL
     * @return имя файла из URL
     */
    private static String fileName(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /**
     * Приводит URL к виду каталога с завершающим «/».
     * Для URL, оканчивающегося на {@code .md}, возвращается каталог этого файла.
     *
     * @param url URL каталога или файла
     * @return URL каталога
     */
    private String normalizeDirUrl(String url) {
        String trimmed = url.trim();
        if (trimmed.endsWith(".md")) {
            int slash = trimmed.lastIndexOf('/');
            return slash >= 0 ? trimmed.substring(0, slash + 1) : trimmed;
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    /**
     * Сохраняет копию скачанного файла в каталог загрузок {@code app.download.directory}.
     * Ошибка записи логируется и не прерывает прогон.
     *
     * @param filename имя файла в каталоге загрузок
     * @param content  содержимое файла
     */
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
