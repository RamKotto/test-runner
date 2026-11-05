package application.service;

import application.config.AppProperties;
import application.dto.Dtos.StepEnrichment;
import application.exception.AssertionFailure;
import application.exception.TargetNotAllowedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Исполнитель API-шагов тест-кейса на базе RestAssured.
 * <p>
 * Место в конвейере: {@link HybridCaseRunner} передаёт сюда шаги, которые
 * {@link StepPlanner} пометил как API-операции. Поддерживаются HTTP-вызов
 * {@code apiCall}, проверки тела ({@code assertBody}, {@code assertBodyAbsent}),
 * заголовков ({@code assertHeader}, {@code assertHeaderAbsent}), статуса
 * ({@code assertStatus}), разбор JSON ({@code parseJson}), а также снимок и
 * сравнение JSON-значения между шагами ({@code snapshotApiValue},
 * {@code assertApiValueEqualsSnapshot}).
 * <p>
 * Каждый шаг возвращает {@link ApiStepResult}: в нём enrichment для отчёта и
 * состояние последнего ответа, которое следующий шаг получает как входные данные.
 * Провал проверки или сетевой сбой — {@link AssertionFailure}; некорректный или
 * неполный план — {@link UiExecutor.LocatorNotResolved}; хост вне allowlist —
 * {@link TargetNotAllowedException}.
 * <p>
 * Все HTTP-вызовы проходят проверку {@link TargetUrlGuard}.
 */
@Slf4j
@Service
public class ApiExecutor {

    /**
     * Маппер для разбора и форматирования JSON тел запросов и ответов.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Настройки приложения; из них берётся лимит длины тела в enrichment.
     */
    private final AppProperties appProps;

    /**
     * Проверка allowlist-а хостов перед каждым HTTP-вызовом.
     */
    private final TargetUrlGuard targetUrlGuard;

    /**
     * Создаёт исполнитель API-шагов.
     *
     * @param appProps       настройки приложения
     * @param targetUrlGuard защита от SSRF, применяется к каждому apiCall
     */
    public ApiExecutor(AppProperties appProps, TargetUrlGuard targetUrlGuard) {
        this.appProps = appProps;
        this.targetUrlGuard = targetUrlGuard;
    }

    /**
     * Результат выполнения одного API-шага.
     *
     * @param enrichment         запись для отчёта; тело ответа в ней обрезается
     *                           по {@code app.enrichment-body-limit}
     * @param responseBody       тело последнего ответа, которое увидит следующий шаг
     * @param responseHeaders    заголовки последнего ответа; {@code null}, если шаг
     *                           их не возвращает (см. {@link #assertApiValueEqualsSnapshot})
     * @param responseStatusCode статус последнего ответа; {@code null}, если шаг
     *                           его не возвращает
     */
    public record ApiStepResult(StepEnrichment enrichment,
                                String responseBody,
                                Map<String, String> responseHeaders,
                                Integer responseStatusCode) {
    }

    /**
     * Результат операции {@code snapshotApiValue}.
     *
     * @param enrichment запись для отчёта
     * @param key        имя слота снимка: {@code plan.snapshotKey()} без пробелов по краям
     * @param value      каноническая JSON-строка выбранного значения
     */
    public record SnapshotResult(StepEnrichment enrichment, String key, String value) {
    }

    /**
     * Выполняет API-шаг, который не является снимком или сравнением снимков.
     * <p>
     * Операция {@code plan.operation()} сравнивается без учёта регистра. Любая
     * неизвестная операция, включая пустую, трактуется как {@code apiCall}.
     * Операции {@code snapshotApiValue} и {@code assertApiValueEqualsSnapshot}
     * здесь не поддерживаются: вызывающий код обязан направлять их в
     * соответствующие методы, иначе выбрасывается {@link IllegalStateException}.
     * <p>
     * Для {@code apiCall} полный URL строится из {@code apiBaseUrl} и
     * {@code plan.path()} (см. {@link #buildFullUrl}), затем проверяется
     * {@link TargetUrlGuard}. Хост вне allowlist даёт {@link TargetNotAllowedException}
     * (провал шага, а не запрос уточнения). Редиректы не отслеживаются
     * ({@code follow(false)}), чтобы 3xx на внутренний адрес не обходил проверку
     * allowlist. Заголовки собираются так: сначала {@code headers} кейса, затем
     * заголовки плана, которые перекрывают одноимённые (имя сравнивается с учётом
     * регистра; {@link HybridCaseRunner} передаёт пустую карту {@code headers}).
     * Тело отправляется только
     * если оно непустое и не равно {@code "{}"}; при отсутствии Content-Type
     * ставится {@code application/json}.
     * <p>
     * Повторы выполняются только для GET: до 3 попыток при ответе 5xx
     * или сетевом {@link IOException}, с паузой {@code 500 мс × номер попытки}.
     * Поддерживаемые методы: GET, HEAD, OPTIONS, POST, PUT, DELETE, PATCH (через RestAssured).
      * HEAD, OPTIONS, POST, PUT, DELETE и PATCH выполняются один раз.
      * Любой другой метод даёт {@link UiExecutor.LocatorNotResolved} («неподдерживаемый HTTP-метод»)
     * без отправки запроса. Если ожидаемый статус задан в плане
     * и не совпал, шаг завершается провалом. Для {@code assertStatus} допустимы значения
     * {@code NNN} и {@code Nxx}; любое другое значение даёт {@link AssertionFailure} с понятным текстом,
     * которое {@link HybridCaseRunner} переводит в провал шага.
     *
     * @param stepIndex           индекс шага, начиная с 0 (в логах выводится +1)
     * @param plan                план шага от LLM
     * @param apiBaseUrl          базовый URL API; нужен для относительного пути
     * @param headers             заголовки, общие для кейса; может быть {@code null}
     * @param pathParams          параметры подстановки в путь RestAssured; может быть {@code null}
     * @param lastResponseBody    тело предыдущего ответа; может быть {@code null}
     * @param lastResponseHeaders заголовки предыдущего ответа; может быть {@code null}
     * @param lastStatusCode      статус предыдущего ответа; может быть {@code null}
     * @return результат шага с enrichment и состоянием последнего ответа
     * @throws AssertionFailure              проверка не прошла, либо вызов API завершился ошибкой
     *                                       или не получил ответа
     * @throws TargetNotAllowedException     хост URL не входит в {@code app.target.allowed-hosts}
     * @throws UiExecutor.LocatorNotResolved план неполон или некорректен (нет значения,
     *                                       некорректного URL, неподдерживаемого метода),
     *                                       нужно уточнение
     * @throws IllegalStateException         переданы операции снимков
     */
    public ApiStepResult executeStep(int stepIndex,
                                     StepPlanner.Plan plan,
                                     String apiBaseUrl,
                                     Map<String, String> headers,
                                     Map<String, String> pathParams,
                                     String lastResponseBody,
                                     Map<String, String> lastResponseHeaders,
                                     Integer lastStatusCode) {

        String op = plan.operation() == null ? "" : plan.operation().toLowerCase();

        if ("snapshotapivalue".equals(op)) {
            throw new IllegalStateException(
                    "snapshotApiValue обрабатывается отдельным методом snapshotApiValue(...)");
        }

        if ("assertapivalueequalssnapshot".equals(op)) {
            throw new IllegalStateException(
                    "assertApiValueEqualsSnapshot обрабатывается отдельным методом");
        }

        if ("assertbody".equals(op)) {
            String expected = plan.value();
            if (expected == null || expected.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("assertBody: не указано значение для проверки");
            }
            if (lastResponseBody == null) {
                throw new AssertionFailure("assertBody: нет тела предыдущего ответа"
                        + " (предыдущий apiCall пропущен или не выполнен)");
            }
            int idx = lastResponseBody.indexOf(expected);
            if (idx < 0) {
                throw new AssertionFailure("assertBody: тело не содержит `" + expected + "`");
            }
            String ctx = contextAround(lastResponseBody, idx, expected.length(), 120);
            String detail = "assertBody → найдено `" + expected + "` на позиции " + idx
                    + "\n   • context:\n       "
                    + ctx.replace("\n", "\n       ");
            log.info("API-шаг {}: {}", stepIndex + 1, detail);
            return new ApiStepResult(
                    StepEnrichment.api(stepIndex, "assertBody", detail,
                            null, null, truncateSample(lastResponseBody)),
                    lastResponseBody, lastResponseHeaders, lastStatusCode);
        }

        if ("assertbodyabsent".equals(op)) {
            String forbidden = plan.value();
            if (forbidden == null || forbidden.isBlank()) {
                throw new UiExecutor.LocatorNotResolved(
                        "assertBodyAbsent: не указано значение для проверки отсутствия");
            }
            if (lastResponseBody == null) {
                throw new AssertionFailure("assertBodyAbsent: нет тела предыдущего ответа"
                        + " (предыдущий apiCall пропущен или не выполнен)");
            }
            int idx = lastResponseBody.indexOf(forbidden);
            if (idx >= 0) {
                String ctx = contextAround(lastResponseBody, idx, forbidden.length(), 120);
                throw new AssertionFailure("assertBodyAbsent: тело содержит `" + forbidden
                        + "`, а не должно. Context:\n" + ctx);
            }
            String detail = "assertBodyAbsent → `" + forbidden + "` отсутствует в теле"
                    + " (длина тела: " + lastResponseBody.length() + " символов)";
            log.info("API-шаг {}: {}", stepIndex + 1, detail);
            return new ApiStepResult(
                    StepEnrichment.api(stepIndex, "assertBodyAbsent", detail,
                            null, null, truncateSample(lastResponseBody)),
                    lastResponseBody, lastResponseHeaders, lastStatusCode);
        }

        if ("assertheader".equals(op)) {
            String name = plan.locator();
            String expected = plan.value();
            if (name == null || name.isBlank() || expected == null || expected.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("assertHeader: нужны имя и значение");
            }
            if (lastResponseHeaders == null || lastResponseHeaders.isEmpty()) {
                throw new AssertionFailure("assertHeader: нет заголовков предыдущего ответа"
                        + " (предыдущий apiCall пропущен или не выполнен)");
            }
            String actual = findHeaderIgnoreCase(lastResponseHeaders, name);
            StringBuilder detail = new StringBuilder("assertHeader\n");
            detail.append("   • checked `").append(name).append("` → ")
                    .append(actual == null ? "❌ отсутствует" : "`" + actual + "`")
                    .append(" (expected `").append(expected).append("`)\n");
            detail.append("   • all response headers:\n");
            for (var h : lastResponseHeaders.entrySet()) {
                detail.append("     - ").append(h.getKey()).append(": ")
                        .append(h.getValue()).append("\n");
            }
            if (actual == null) {
                throw new AssertionFailure("assertHeader: заголовок `" + name + "` отсутствует");
            }
            if (!actual.toLowerCase().contains(expected.toLowerCase())) {
                throw new AssertionFailure("assertHeader: `" + name + "` = `" + actual
                        + "`, ожидалось `" + expected + "`");
            }
            log.info("API-шаг {}: assertHeader `{}` ok", stepIndex + 1, name);
            return new ApiStepResult(
                    StepEnrichment.api(stepIndex, "assertHeader", detail.toString(),
                            null, null, null),
                    lastResponseBody, lastResponseHeaders, lastStatusCode);
        }

        if ("assertheaderabsent".equals(op)) {
            String name = plan.locator();
            if (name == null || name.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("assertHeaderAbsent: не указано имя заголовка");
            }
            if (lastResponseHeaders == null) {
                throw new AssertionFailure("assertHeaderAbsent: нет заголовков предыдущего ответа"
                        + " (предыдущий apiCall пропущен или не выполнен)");
            }
            String actual = findHeaderIgnoreCase(lastResponseHeaders, name);
            StringBuilder detail = new StringBuilder("assertHeaderAbsent\n");
            detail.append("   • checked `").append(name).append("` → ")
                    .append(actual == null ? "✅ отсутствует" : "`" + actual + "`")
                    .append("\n");
            if (actual != null) {
                String expected = plan.value();
                if (expected != null && !expected.isBlank()
                        && !actual.toLowerCase().contains(expected.toLowerCase())) {
                    detail.append("   • но значение `").append(actual)
                            .append("` не содержит `").append(expected).append("` — ок\n");
                    log.info("API-шаг {}: assertHeaderAbsent `{}` ok (значение не совпало)",
                            stepIndex + 1, name);
                    return new ApiStepResult(
                            StepEnrichment.api(stepIndex, "assertHeaderAbsent", detail.toString(),
                                    null, null, null),
                            lastResponseBody, lastResponseHeaders, lastStatusCode);
                }
                throw new AssertionFailure("assertHeaderAbsent: заголовок `" + name
                        + "` присутствует (`" + actual + "`), а не должен");
            }
            log.info("API-шаг {}: assertHeaderAbsent `{}` ok", stepIndex + 1, name);
            return new ApiStepResult(
                    StepEnrichment.api(stepIndex, "assertHeaderAbsent", detail.toString(),
                            null, null, null),
                    lastResponseBody, lastResponseHeaders, lastStatusCode);
        }

        if ("assertstatus".equals(op)) {
            String expected = plan.value() == null ? null : plan.value().trim();
            if (expected == null || expected.isEmpty()) {
                throw new UiExecutor.LocatorNotResolved("assertStatus: не указано ожидаемое значение");
            }
            // Проверка до Integer.parseInt: иначе нечисловое значение дало бы NumberFormatException
            // и непонятное сообщение «Необработанная ошибка».
            if (!expected.matches("\\d{3}|[1-5]xx")) {
                throw new AssertionFailure("assertStatus: некорректное ожидаемое значение `" + expected
                        + "`, ожидается код из 3 цифр (например 200) или класс ответа 1xx–5xx (например 4xx)");
            }
            if (lastStatusCode == null) {
                throw new AssertionFailure("assertStatus: нет статуса предыдущего ответа"
                        + " (предыдущий apiCall пропущен или не выполнен)");
            }
            boolean matched;
            if (expected.endsWith("xx")) {
                int prefix = Integer.parseInt(expected.substring(0, 1));
                matched = (lastStatusCode / 100) == prefix;
            } else {
                matched = lastStatusCode == Integer.parseInt(expected);
            }
            StringBuilder detail = new StringBuilder("assertStatus → HTTP ")
                    .append(lastStatusCode).append(" vs expected `").append(expected)
                    .append("` ").append(matched ? "✅" : "❌");
            if (lastResponseBody != null && !lastResponseBody.isBlank()) {
                detail.append("\n   • response sample:\n");
                for (String line : beautifyJsonIfPossible(lastResponseBody).split("\\R", -1)) {
                    detail.append("       ").append(line).append("\n");
                }
            }
            if (!matched) {
                throw new AssertionFailure("assertStatus: статус " + lastStatusCode
                        + ", ожидалось " + expected);
            }
            log.info("API-шаг {}: assertStatus {}", stepIndex + 1, lastStatusCode);
            return new ApiStepResult(
                    StepEnrichment.api(stepIndex, "assertStatus", detail.toString(),
                            null, null, truncateSample(lastResponseBody)),
                    lastResponseBody, lastResponseHeaders, lastStatusCode);
        }

        if ("parsejson".equals(op)) {
            if (lastResponseBody == null || lastResponseBody.isBlank()) {
                throw new AssertionFailure("parseJson: нет тела предыдущего ответа"
                        + " (предыдущий apiCall пропущен или не выполнен)");
            }
            try {
                var node = mapper.readTree(lastResponseBody);
                StringBuilder detail = new StringBuilder("parseJson → тело разобрано как JSON");
                if (node != null && node.isObject()) {
                    List<String> keys = new ArrayList<>();
                    node.fieldNames().forEachRemaining(keys::add);
                    detail.append("\n   • top-level keys: ").append(keys);
                } else if (node != null) {
                    detail.append("\n   • root type: ").append(node.getNodeType());
                }
                log.info("API-шаг {}: parseJson ok", stepIndex + 1);
                return new ApiStepResult(
                        StepEnrichment.api(stepIndex, "parseJson", detail.toString(),
                                null, null, null),
                        lastResponseBody, lastResponseHeaders, lastStatusCode);
            } catch (Exception e) {
                throw new AssertionFailure("parseJson: невалидный JSON: " + e.getMessage());
            }
        }

        String method = plan.method() == null ? "" : plan.method().trim().toUpperCase();
        if (method.isEmpty()) method = "GET";
        String path = plan.path();

        String fullUrl = buildFullUrl(apiBaseUrl, path);

        URI uri;
        try {
            uri = URI.create(fullUrl);
        } catch (IllegalArgumentException e) {
            throw new UiExecutor.LocatorNotResolved("некорректный URL API: " + fullUrl);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new UiExecutor.LocatorNotResolved(
                    "некорректный абсолютный URL API (нужен http(s)://host/...): `"
                            + fullUrl + "`. Проверьте apiBaseUrl в RunRequest или path в плане.");
        }

        if (!targetUrlGuard.isAllowed(fullUrl)) {
            throw new TargetNotAllowedException("Хост не разрешён для API: " + host
                    + ". Добавьте его в app.target.allowed-hosts");
        }

        String baseUri = scheme + "://" + host
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String uriPath = uri.getRawPath();
        if (uriPath == null || uriPath.isEmpty()) uriPath = "/";
        if (uri.getRawQuery() != null) uriPath += "?" + uri.getRawQuery();

        Map<String, String> effectiveHeaders = new LinkedHashMap<>();
        if (headers != null) effectiveHeaders.putAll(headers);
        Map<String, String> planHeaders = parseHeaders(plan.requestHeaders());
        effectiveHeaders.putAll(planHeaders);
        String bodyJson = plan.bodyJson();
        boolean hasBody = bodyJson != null && !bodyJson.isBlank() && !"{}".equals(bodyJson);
        if (hasBody && effectiveHeaders.keySet().stream()
                .noneMatch(k -> k.equalsIgnoreCase("content-type"))) {
            effectiveHeaders.put("Content-Type", "application/json");
        }

        RequestSpecification req = RestAssured.given().baseUri(baseUri).relaxedHTTPSValidation();
        req.redirects().follow(false);
        effectiveHeaders.forEach(req::header);
        if (pathParams != null && !pathParams.isEmpty()) req.pathParams(pathParams);
        if (hasBody) req.body(bodyJson);

        int attempts = "GET".equals(method) ? 3 : 1;
        Response response = null;
        Exception lastEx = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                response = switch (method) {
                    case "GET" -> req.get(uriPath);
                    case "HEAD" -> req.head(uriPath);
                    case "OPTIONS" -> req.options(uriPath);
                    case "POST" -> req.post(uriPath);
                    case "PUT" -> req.put(uriPath);
                    case "DELETE" -> req.delete(uriPath);
                    case "PATCH" -> req.patch(uriPath);
                    default -> throw new UiExecutor.LocatorNotResolved(
                            "неподдерживаемый HTTP-метод: " + method);
                };
                int sc = response.statusCode();
                if (sc < 500 || attempt == attempts) break;
                log.warn("API-шаг {}: {} {} → HTTP {} (attempt {}/{}), повтор…",
                        stepIndex + 1, method, fullUrl, sc, attempt, attempts);
                sleepQuietly(500L * attempt);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (e instanceof IOException || cause instanceof IOException) {
                    lastEx = e;
                    if (attempt == attempts) break;
                    log.warn("API-шаг {}: {} {} → {} (attempt {}/{}), повтор…",
                            stepIndex + 1, method, fullUrl, e.getMessage(), attempt, attempts);
                    sleepQuietly(500L * attempt);
                } else {
                    if (e instanceof UiExecutor.LocatorNotResolved lr) throw lr;
                    throw new AssertionFailure("ошибка вызова API: " + e.getMessage(), e);
                }
            }
        }

        if (response == null) {
            throw new AssertionFailure("API " + method + " " + fullUrl
                    + " не отвечает: " + (lastEx == null ? "unknown" : lastEx.getMessage()),
                    lastEx);
        }

        int actual = response.statusCode();
        Integer expected = plan.expectedStatus();
        if (expected != null && actual != expected) {
            throw new AssertionFailure("API " + method + " " + fullUrl + " вернул " + actual
                    + ", ожидалось " + expected);
        }

        String body = response.getBody() == null ? "" : response.getBody().asString();
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.getHeaders().asList().forEach(h ->
                responseHeaders.merge(h.getName(), h.getValue(), (a, b) -> a + ", " + b));

        String curl = buildCurl(method, fullUrl, effectiveHeaders, hasBody ? bodyJson : null);
        String detail = buildApiCallDetail(method, fullUrl, actual, effectiveHeaders,
                hasBody ? bodyJson : null, responseHeaders, body);
        log.info("API-шаг {}: {} {} → HTTP {}", stepIndex + 1, method, fullUrl, actual);

        return new ApiStepResult(
                StepEnrichment.api(stepIndex, "apiCall", detail, curl,
                        hasBody ? bodyJson : null, truncateSample(body)),
                body, responseHeaders, actual);
    }

    /**
     * Снимает JSON-значение из тела предыдущего ответа в именованный слот снимка.
     * <p>
     * Путь {@code plan.jsonPath()} приводится к JSON Pointer через
     * {@link #toJsonPointer(String)}; «/», «$» и пустая строка снимают весь ответ. Значение сохраняется в компактной
     * канонической форме (см. {@link #canonicalize(JsonNode)}), чтобы
     * последующее сравнение было побайтовым. Тело ответа не изменяется.
     *
     * @param stepIndex          индекс шага, начиная с 0
     * @param plan               план шага; нужны {@code snapshotKey} и {@code jsonPath}
     * @param lastResponseBody   тело предыдущего ответа
     * @return результат со слотом снимка и каноническим значением
     * @throws AssertionFailure              тело отсутствует, не является JSON или путь не найден
     * @throws UiExecutor.LocatorNotResolved не указаны ключ снимка или путь
     */
    public SnapshotResult snapshotApiValue(int stepIndex, StepPlanner.Plan plan,
                                           String lastResponseBody) {
        String key = requireSnapshotKey(plan.snapshotKey());
        String path = plan.jsonPath();
        if (path == null) {
            throw new UiExecutor.LocatorNotResolved(
                    "snapshotApiValue: не указан jsonPath (напр. `/hourly/temperature_2m` или `$.hourly.temperature_2m`; `/` или пустая строка — весь ответ)");
        }
        if (lastResponseBody == null || lastResponseBody.isBlank()) {
            throw new AssertionFailure("snapshotApiValue: нет тела предыдущего ответа"
                    + " (предыдущий apiCall пропущен или не выполнен)");
        }
        JsonNode root;
        try {
            root = mapper.readTree(lastResponseBody);
        } catch (Exception e) {
            throw new AssertionFailure("snapshotApiValue: невалидный JSON: " + e.getMessage());
        }
        String pointer = toJsonPointer(path);
        JsonNode slice = pointer.isEmpty() ? root : root.at(pointer);
        if (slice.isMissingNode()) {
            throw new AssertionFailure("snapshotApiValue: jsonPath `" + path
                    + "` (→ `" + pointer + "`) не найден в теле ответа");
        }
        String canonical = canonicalize(slice);
        String preview = truncate(canonical, 240);
        String detail = "snapshotApiValue → snapshot[" + key + "] ← `" + path
                + "`" + pointerNote(path, pointer)
                + "\n   • length: " + canonical.length() + " chars\n"
                + "   • preview: " + preview;
        log.info("API-шаг {}: snapshotApiValue key={} path={} pointer={} len={}",
                stepIndex + 1, key, path, pointer, canonical.length());
        return new SnapshotResult(
                StepEnrichment.api(stepIndex, "snapshotApiValue", detail, null, null, null),
                key, canonical);
    }

    /**
     * Сравнивает JSON-значение из тела текущего ответа со снимком того же ключа.
     * <p>
     * Текущий срез по {@code plan.jsonPath()} канонизируется и сравнивается с
     * {@code snapshots.get(ключ плана без пробелов по краям)} строгим равенством строк. При
     * расхождении в исключении отчёта не остаётся: первое различие
     * ({@link #firstDifference}) попадает только в локальное описание, которое
     * не возвращается вызывающему коду.
     * <p>
     * Возвращаемый результат содержит {@code null} вместо заголовков и статуса,
     * сохраняя тело. {@link HybridCaseRunner} эти поля не использует для данной
     * операции и оставляет предыдущее состояние без изменений, поэтому последующие
     * {@code assertHeader} и {@code assertStatus} продолжают работать с ответом apiCall.
     *
     * @param stepIndex        индекс шага, начиная с 0
     * @param plan             план шага; нужны {@code snapshotKey} и {@code jsonPath}
     * @param lastResponseBody тело текущего ответа
     * @param snapshots        набор снимков кейса по ключам; может быть {@code null}
     * @return результат проверки с enrichment и телом ответа
     * @throws AssertionFailure              снимка нет, тело не JSON, путь не найден или значения различаются
     * @throws UiExecutor.LocatorNotResolved не указаны ключ снимка или путь
     */
    public ApiStepResult assertApiValueEqualsSnapshot(int stepIndex, StepPlanner.Plan plan,
                                                      String lastResponseBody,
                                                      Map<String, String> snapshots) {
        String key = requireSnapshotKey(plan.snapshotKey());
        String path = plan.jsonPath();
        if (path == null) {
            throw new UiExecutor.LocatorNotResolved(
                    "assertApiValueEqualsSnapshot: не указан jsonPath");
        }
        if (lastResponseBody == null) {
            throw new AssertionFailure("assertApiValueEqualsSnapshot: нет тела предыдущего ответа");
        }
        String reference = snapshots == null ? null : snapshots.get(key);
        if (reference == null) {
            throw new AssertionFailure("assertApiValueEqualsSnapshot: нет снимка с ключом `"
                    + key + "` (snapshotApiValue на шаге-эталоне не выполнялся?)");
        }
        JsonNode root;
        try {
            root = mapper.readTree(lastResponseBody);
        } catch (Exception e) {
            throw new AssertionFailure("assertApiValueEqualsSnapshot: невалидный JSON");
        }
        String pointer = toJsonPointer(path);
        JsonNode actualSlice = pointer.isEmpty() ? root : root.at(pointer);
        if (actualSlice.isMissingNode()) {
            throw new AssertionFailure("assertApiValueEqualsSnapshot: jsonPath `" + path
                    + "` (→ `" + pointer + "`) отсутствует в текущем ответе");
        }
        String actual = canonicalize(actualSlice);
        boolean equals = reference.equals(actual);

        StringBuilder detail = new StringBuilder("assertApiValueEqualsSnapshot")
                .append("\n   • snapshotKey: `").append(key).append("`")
                .append("\n   • jsonPath: `").append(path).append("`")
                .append(pointerNote(path, pointer))
                .append("\n   • expected length: ").append(reference.length())
                .append("\n   • actual length: ").append(actual.length())
                .append("\n   • ").append(equals ? "✅ equal" : "❌ differ");

        if (!equals) {
            String diff = firstDifference(reference, actual, 120);
            detail.append("\n   • first difference:\n       ")
                    .append(diff.replace("\n", "\n       "));
            throw new AssertionFailure("assertApiValueEqualsSnapshot: значения по `" + path
                    + "` (key=`" + key + "`) различаются");
        }
        log.info("API-шаг {}: assertApiValueEqualsSnapshot key={} ok", stepIndex + 1, key);
        return new ApiStepResult(
                StepEnrichment.api(stepIndex, "assertApiValueEqualsSnapshot",
                        detail.toString(), null, null, truncateSample(lastResponseBody)),
                lastResponseBody, null, null);
    }

    /**
     * Приводит путь к JSON Pointer (RFC 6901).
     * <p>
     * Принимает JSON Pointer как есть ({@code /hourly/temperature_2m}) и путь
     * в стиле JSONPath с корнем {@code $} ({@code $.hourly.temperature_2m}).
     * Для JSONPath точки заменяются на «/», поэтому ключи, содержащие точку,
     * и индексы вида {@code [0]} не адресуются. Путь без {@code $} и без ведущего «/»
     * (например, {@code hourly.temperature_2m}) получает только ведущий «/»;
     * точки в нём не заменяются. Экранирование {@code ~0} и {@code ~1} не выполняется.
     * <p>
     * Корень документа: пустая строка, {@code /} и {@code $} дают пустой указатель {@code ""},
     * который адресует весь документ (RFC 6901). Снимок такого пути содержит весь ответ
     * в канонической форме (см. {@link #canonicalize(JsonNode)}). Пустой указатель
     * не передаётся в {@code JsonNode.at}: вызывающий код берёт корневой узел напрямую.
     * Путь «/» не трактуется как ключ с пустым именем, поэтому не падает с «не найден».
     * <p>
     * Вызывающий код отсекает {@code null} раньше (отсутствующий jsonPath — ошибка плана),
     * поэтому здесь {@code null} приводится к корню только для полноты.
     *
     * @param raw путь из плана; может быть {@code null}
     * @return JSON Pointer (RFC 6901): пустая строка для корня документа, иначе начинается с {@code /}
     */
    private static String toJsonPointer(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("$")) {
            s = s.substring(1);
            if (s.startsWith(".")) s = s.substring(1);
            s = s.replace('.', '/');
        }
        if (s.isEmpty() || s.equals("/")) return "";
        if (!s.startsWith("/")) s = "/" + s;
        return s;
    }

    /**
     * Формирует пояснение в отчёте о том, как путь из плана соотнесён с JSON Pointer.
     *
     * @param path    путь из плана
     * @param pointer результат {@link #toJsonPointer(String)}
     * @return пустая строка, если путь совпал с указателем; пояснение для корня или указатель в скобках
     */
    private static String pointerNote(String path, String pointer) {
        if (pointer.isEmpty()) return " (→ корень документа)";
        return path.equals(pointer) ? "" : " (→ `" + pointer + "`)";
    }

    /**
     * Сериализует JSON-узел в компактную строку, которая используется как снимок.
     * Порядок полей сохраняется так, как он пришёл в ответе; порядок и формат
     * не нормализуются. Java-значение {@code null} даёт строку {@code "null"};
     * отсутствующие узлы (MissingNode) сюда не передаются, их отсекает вызывающий код.
     *
     * @param node узел JSON; может быть {@code null}
     * @return компактная JSON-строка
     */
    private static String canonicalize(JsonNode node) {
        if (node == null) return "null";
        return node.toString();
    }

    /**
     * Находит позицию первого расхождения двух строк и возвращает фрагменты вокруг неё.
     * <p>
     * Окно вокруг позиции расхождения составляет примерно {@code radius} символов:
     * {@code radius / 2} до и после позиции. Если одна строка является префиксом
     * другой, позиция равна длине более короткой строки.
     *
     * @param a      первая строка (эталон)
     * @param b      вторая строка (текущее значение)
     * @param radius полный размер окна для вывода
     * @return многострочное описание: эталон, факт и смещение
     */
    private static String firstDifference(String a, String b, int radius) {
        int i = 0, n = Math.min(a.length(), b.length());
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        int from = Math.max(0, i - radius / 2);
        int toA = Math.min(a.length(), i + radius / 2);
        int toB = Math.min(b.length(), i + radius / 2);
        return "expected: …" + a.substring(from, toA) + "…\n"
                + "actual:   …" + b.substring(from, toB) + "…\n"
                + "offset:   " + i;
    }

    /**
     * Проверяет и нормализует ключ снимка: непустой ключ возвращается обрезанным.
     *
     * @param k ключ из плана; может быть {@code null}
     * @return ключ без пробелов по краям
     * @throws UiExecutor.LocatorNotResolved ключ отсутствует или пуст
     */
    private static String requireSnapshotKey(String k) {
        if (k == null || k.isBlank()) {
            throw new UiExecutor.LocatorNotResolved(
                    "snapshotKey обязателен для операций snapshot/compare");
        }
        return k.trim();
    }

    /**
     * Разбирает заголовки плана, заданные по одной паре {@code Name: value} в строке.
     * <p>
     * Строки разделяются любым переводом строки. Разбиение идёт по первому двоеточию;
     * строки без двоеточия или с двоеточием в начале пропускаются. Имя и значение
     * обрезаются. Повторяющееся имя перезаписывает значение, но сохраняет позицию.
     *
     * @param raw текст заголовков; может быть {@code null} или пустым
     * @return изменяемая карта заголовков в порядке первого появления; никогда не {@code null}
     */
    private Map<String, String> parseHeaders(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String k = line.substring(0, colon).trim();
            String v = line.substring(colon + 1).trim();
            if (!k.isEmpty()) out.put(k, v);
        }
        return out;
    }

    /**
     * Формирует curl-команду, которой можно воспроизвести запрос (только для отчёта).
     * Одинарные кавычки в значениях заголовков и в теле экранируются конструкцией {@code '\''};
     * URL и имена заголовков не экранируются.
     * Тело добавляется, только если оно непустое и не равно {@code "{}"}.
     *
     * @param method  HTTP-метод
     * @param url     полный URL запроса
     * @param headers заголовки запроса; может быть {@code null}
     * @param body    тело запроса или {@code null}
     * @return многострочная команда, строки которой разделены обратным слешем и переводом строки
     */
    private String buildCurl(String method, String url, Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder("curl -X ").append(method).append(" '").append(url).append("'");
        if (headers != null) {
            for (var h : headers.entrySet()) {
                sb.append(" \\\n  -H '").append(h.getKey()).append(": ")
                        .append(h.getValue().replace("'", "'\\''")).append("'");
            }
        }
        if (body != null && !body.isBlank() && !"{}".equals(body)) {
            sb.append(" \\\n  -d '").append(body.replace("'", "'\\''")).append("'");
        }
        return sb.toString();
    }

    /**
     * Собирает многострочный отчёт по выполненному {@code apiCall}: запрос,
     * заголовки ответа и тело ответа. Тело запроса и тело ответа выводятся
     * в JSON-формате с отступами, если это возможно (см. {@link #beautifyJsonIfPossible}).
     * Пустое тело ответа в отчёт не включается.
     *
     * @param method          HTTP-метод
     * @param fullUrl         полный URL запроса
     * @param status          HTTP-статус ответа
     * @param reqHeaders      заголовки запроса; могут быть {@code null}
     * @param reqBody         тело запроса или {@code null}
     * @param responseHeaders заголовки ответа; могут быть {@code null}
     * @param body            тело ответа; может быть {@code null} или пустым
     * @return текст отчёта
     */
    private String buildApiCallDetail(String method, String fullUrl, int status,
                                      Map<String, String> reqHeaders, String reqBody,
                                      Map<String, String> responseHeaders, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("apiCall → `").append(method).append(" ").append(fullUrl)
                .append("` → HTTP ").append(status).append("\n");

        sb.append("\n   • request headers:\n");
        if (reqHeaders == null || reqHeaders.isEmpty()) {
            sb.append("     (none)\n");
        } else {
            reqHeaders.forEach((k, v) -> sb.append("     - ").append(k).append(": ").append(v).append("\n"));
        }
        if (reqBody != null && !reqBody.isBlank() && !"{}".equals(reqBody)) {
            sb.append("\n   • request body:\n");
            for (String line : beautifyJsonIfPossible(reqBody).split("\\R", -1)) {
                sb.append("       ").append(line).append("\n");
            }
        }

        sb.append("\n   • response headers:\n");
        if (responseHeaders == null || responseHeaders.isEmpty()) {
            sb.append("     (none)\n");
        } else {
            responseHeaders.forEach((k, v) -> sb.append("     - ").append(k).append(": ").append(v).append("\n"));
        }

        if (body == null || body.isBlank()) {
            return sb.toString();
        }
        sb.append("\n   • response body:\n");
        String pretty = beautifyJsonIfPossible(body);
        for (String line : pretty.split("\\R", -1)) {
            sb.append("       ").append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * Форматирует строку как JSON с отступами; если строка не является JSON, возвращает её без изменений.
     *
     * @param body исходная строка; может быть {@code null}
     * @return отформатированный JSON либо исходная строка
     */
    private String beautifyJsonIfPossible(String body) {
        try {
            var node = mapper.readTree(body);
            if (node == null) return body;
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return body;
        }
    }

    /**
     * Обрезает тело ответа для enrichment согласно {@code app.enrichment-body-limit}.
     * <p>
     * Лимит {@code 0} или отрицательный отключает обрезку. Обрезка применяется
     * только к отчёту: тело, которое получают следующие шаги, остаётся полным. Планировщик
     * может обрезать тело в своём промпте независимо от этого лимита (см. {@link StepPlanner#planStep}).
     *
     * @param body тело ответа; может быть {@code null}
     * @return исходное тело, если оно не превышает лимит, иначе усечённая строка с пометкой;
     *         {@code null}, если тело {@code null}
     */
    private String truncateSample(String body) {
        if (body == null) return null;
        int limit = appProps.getEnrichmentBodyLimit();
        if (limit <= 0 || body.length() <= limit) return body;
        return body.substring(0, limit) + "\n… (truncated at " + limit + " chars)";
    }

    /**
     * Обрезает строку до {@code max} символов и добавляет «…», если она длиннее.
     *
     * @param s   исходная строка; может быть {@code null}
     * @param max максимальная длина без многоточия
     * @return обрезанная строка, пустая строка для {@code null}
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Возвращает фрагмент тела вокруг найденного совпадения для отчёта.
     * Вокруг совпадения берётся {@code radius} символов с каждой стороны. Если фрагмент
     * не доходит до края тела, с этой стороны ставится «…».
     *
     * @param body       полный текст
     * @param matchStart позиция начала совпадения
     * @param matchLen   длина совпадения
     * @param radius     количество символов контекста с каждой стороны
     * @return фрагмент с многоточиями по обрезанным краям
     */
    private String contextAround(String body, int matchStart, int matchLen, int radius) {
        int from = Math.max(0, matchStart - radius);
        int to = Math.min(body.length(), matchStart + matchLen + radius);
        String prefix = from > 0 ? "…" : "";
        String suffix = to < body.length() ? "…" : "";
        return prefix + body.substring(from, to) + suffix;
    }

    /**
     * Ищет значение заголовка по имени без учёта регистра.
     *
     * @param headers карта заголовков; может быть {@code null}
     * @param name    имя заголовка
     * @return значение первого совпавшего заголовка или {@code null}, если такого нет
     */
    private String findHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Строит полный URL вызова API.
     * <p>
     * Абсолютный путь (начинается с {@code http://} или {@code https://}, регистрозависимо)
     * используется как есть.
     * Относительный путь присоединяется к {@code apiBaseUrl}: завершающий «/» базы
     * убирается, а отсутствующий «/» пути добавляется. {@code null}-путь даёт
     * только базу. Для относительного пути {@code apiBaseUrl} обязан начинаться с
     * {@code http://} или {@code https://} (проверка регистрозависимая).
     *
     * @param apiBaseUrl базовый URL API; может быть {@code null}
     * @param path       путь или абсолютный URL из плана; может быть {@code null}
     * @return полный URL
     * @throws UiExecutor.LocatorNotResolved путь относительный, а {@code apiBaseUrl} не задан или некорректен
     */
    private String buildFullUrl(String apiBaseUrl, String path) {
        if (path != null && (path.startsWith("http://") || path.startsWith("https://"))) {
            return path;
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()
                || !(apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://"))) {
            throw new UiExecutor.LocatorNotResolved(
                    "не задан корректный apiBaseUrl (нужен http(s)://host/...), "
                            + "а path=`" + path + "` относительный. "
                            + "Укажите apiBaseUrl в RunRequest или ответьте на уточнение "
                            + "с topicKey=api_base_url.");
        }
        String base = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        String p = path == null ? "" : (path.startsWith("/") ? path : "/" + path);
        return base + p;
    }

    /**
     * Приостанавливает поток на заданное время. Если поток прерван, флаг прерывания
     * восстанавливается, а исключение не пробрасывается: вызывающий код продолжает
     * следующую попытку.
     *
     * @param ms длительность паузы в миллисекундах
     */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
