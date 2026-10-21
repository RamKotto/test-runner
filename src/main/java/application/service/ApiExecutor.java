package application.service;

import application.config.AppProperties;
import application.dto.Dtos.StepEnrichment;
import application.exception.AssertionFailure;
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

@Slf4j
@Service
public class ApiExecutor {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AppProperties appProps;

    public ApiExecutor(AppProperties appProps) {
        this.appProps = appProps;
    }

    public record ApiStepResult(StepEnrichment enrichment,
                                String responseBody,
                                Map<String, String> responseHeaders,
                                Integer responseStatusCode) {
    }

    public ApiStepResult executeStep(int stepIndex,
                                     StepPlanner.Plan plan,
                                     String apiBaseUrl,
                                     Map<String, String> headers,
                                     Map<String, String> pathParams,
                                     String lastResponseBody,
                                     Map<String, String> lastResponseHeaders,
                                     Integer lastStatusCode) {

        String op = plan.operation() == null ? "" : plan.operation().toLowerCase();

        if ("assertbody".equals(op)) {
            String expected = plan.value();
            if (expected == null || expected.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("assertBody: не указано значение для проверки");
            }
            if (lastResponseBody == null) {
                throw new UiExecutor.LocatorNotResolved("assertBody: нет тела предыдущего ответа");
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

        if ("assertheader".equals(op)) {
            String name = plan.locator();
            String expected = plan.value();
            if (name == null || name.isBlank() || expected == null || expected.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("assertHeader: нужны имя и значение");
            }
            if (lastResponseHeaders == null || lastResponseHeaders.isEmpty()) {
                throw new UiExecutor.LocatorNotResolved("assertHeader: нет заголовков");
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

        if ("assertstatus".equals(op)) {
            String expected = plan.value();
            if (expected == null || expected.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("assertStatus: не указано ожидаемое значение");
            }
            if (lastStatusCode == null) {
                throw new UiExecutor.LocatorNotResolved("assertStatus: нет статуса предыдущего ответа");
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
                throw new UiExecutor.LocatorNotResolved("parseJson: нет тела");
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

        // ---- apiCall ----
        String method = plan.method() == null ? "" : plan.method().trim().toUpperCase();
        if (method.isEmpty()) method = "GET";
        String path = plan.path();

        String fullUrl = buildFullUrl(apiBaseUrl, path);
        if (fullUrl == null || fullUrl.isBlank()) {
            throw new UiExecutor.LocatorNotResolved("не указан apiBaseUrl и path");
        }
        URI uri;
        try {
            uri = URI.create(fullUrl);
        } catch (IllegalArgumentException e) {
            throw new UiExecutor.LocatorNotResolved("некорректный URL API: " + fullUrl);
        }
        String baseUri = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String uriPath = uri.getRawPath();
        if (uriPath == null || uriPath.isEmpty()) uriPath = "/";
        if (uri.getRawQuery() != null) uriPath += "?" + uri.getRawQuery();

        Map<String, String> effectiveHeaders = new LinkedHashMap<>();
        if (headers != null) effectiveHeaders.putAll(headers);
        // merge requestHeaders из плана (простой парсинг "K: V\nK2: V2")
        Map<String, String> planHeaders = parseHeaders(plan.requestHeaders());
        effectiveHeaders.putAll(planHeaders);
        String bodyJson = plan.bodyJson();
        boolean hasBody = bodyJson != null && !bodyJson.isBlank() && !"{}".equals(bodyJson);
        if (hasBody && effectiveHeaders.keySet().stream()
                .noneMatch(k -> k.equalsIgnoreCase("content-type"))) {
            effectiveHeaders.put("Content-Type", "application/json");
        }

        RequestSpecification req = RestAssured.given().baseUri(baseUri).relaxedHTTPSValidation();
        effectiveHeaders.forEach(req::header);
        if (pathParams != null && !pathParams.isEmpty()) req.pathParams(pathParams);
        if (hasBody) req.body(bodyJson);

        int attempts = ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method))
                ? 3 : 1;
        Response response = null;
        Exception lastEx = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                response = switch (method) {
                    case "GET" -> req.get(uriPath);
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

    private String beautifyJsonIfPossible(String body) {
        try {
            var node = mapper.readTree(body);
            if (node == null) return body;
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return body;
        }
    }

    private String truncateSample(String body) {
        if (body == null) return null;
        int limit = appProps.getEnrichmentBodyLimit();
        if (limit <= 0 || body.length() <= limit) return body;
        return body.substring(0, limit) + "\n… (truncated at " + limit + " chars)";
    }

    private String contextAround(String body, int matchStart, int matchLen, int radius) {
        int from = Math.max(0, matchStart - radius);
        int to = Math.min(body.length(), matchStart + matchLen + radius);
        String prefix = from > 0 ? "…" : "";
        String suffix = to < body.length() ? "…" : "";
        return prefix + body.substring(from, to) + suffix;
    }

    private String findHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private String buildFullUrl(String apiBaseUrl, String path) {
        if (path != null && (path.startsWith("http://") || path.startsWith("https://"))) return path;
        if ((path == null || path.isBlank()) && apiBaseUrl != null
                && (apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://")))
            return apiBaseUrl;
        if (apiBaseUrl != null && path != null) {
            if (apiBaseUrl.endsWith("/") && path.startsWith("/"))
                return apiBaseUrl.substring(0, apiBaseUrl.length() - 1) + path;
            if (!apiBaseUrl.endsWith("/") && !path.startsWith("/")) return apiBaseUrl + "/" + path;
            return apiBaseUrl + path;
        }
        return null;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
