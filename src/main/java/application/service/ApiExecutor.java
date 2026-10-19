package application.service;

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
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class ApiExecutor {

    private final ObjectMapper mapper = new ObjectMapper();

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
            if (!lastResponseBody.contains(expected)) {
                throw new AssertionFailure("assertBody: тело не содержит `" + expected + "`");
            }
            StepEnrichment e = new StepEnrichment(stepIndex, "assertBody",
                    "assertBody → тело содержит `" + expected + "`");
            log.info("API-шаг {}: {}", stepIndex + 1, e.detail());
            return new ApiStepResult(e, lastResponseBody, lastResponseHeaders, lastStatusCode);
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
            if (actual == null) {
                throw new AssertionFailure("assertHeader: заголовок `" + name + "` отсутствует");
            }
            if (!actual.toLowerCase().contains(expected.toLowerCase())) {
                throw new AssertionFailure("assertHeader: `" + name + "` = `" + actual
                        + "`, ожидалось `" + expected + "`");
            }
            StepEnrichment e = new StepEnrichment(stepIndex, "assertHeader",
                    "assertHeader → `" + name + "` содержит `" + expected + "`");
            log.info("API-шаг {}: {}", stepIndex + 1, e.detail());
            return new ApiStepResult(e, lastResponseBody, lastResponseHeaders, lastStatusCode);
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

            if (!matched) {
                throw new AssertionFailure("assertStatus: статус " + lastStatusCode
                        + ", ожидалось " + expected);
            }
            StepEnrichment e = new StepEnrichment(stepIndex, "assertStatus",
                    "assertStatus → HTTP " + lastStatusCode + " соответствует `" + expected + "`");
            log.info("API-шаг {}: {}", stepIndex + 1, e.detail());
            return new ApiStepResult(e, lastResponseBody, lastResponseHeaders, lastStatusCode);
        }

        if ("parsejson".equals(op)) {
            if (lastResponseBody == null || lastResponseBody.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("parseJson: нет тела");
            }
            try {
                mapper.readTree(lastResponseBody);
            } catch (Exception e) {
                throw new AssertionFailure("parseJson: невалидный JSON: " + e.getMessage());
            }
            StepEnrichment e = new StepEnrichment(stepIndex, "parseJson",
                    "parseJson → тело разобрано как JSON");
            log.info("API-шаг {}: {}", stepIndex + 1, e.detail());
            return new ApiStepResult(e, lastResponseBody, lastResponseHeaders, lastStatusCode);
        }

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

        RequestSpecification req = RestAssured.given().baseUri(baseUri).relaxedHTTPSValidation();
        if (headers != null) headers.forEach(req::header);
        if (pathParams != null) req.pathParams(pathParams);
        if (plan.bodyJson() != null && !plan.bodyJson().isBlank() && !"{}".equals(plan.bodyJson())) {
            req.contentType("application/json").body(plan.bodyJson());
        }

        // Ретраим 5xx и сетевые ошибки только для идемпотентных методов
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
                // RestAssured заворачивает сетевые ошибки, поэтому проверяем через instanceof
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
            if (actual >= 500 && actual <= 599) {
                // Сервер недоступен — это FAILED, а не повод для уточнения.
                throw new AssertionFailure("API " + method + " " + fullUrl + " вернул " + actual
                        + " (сервер недоступен), ожидалось " + expected);
            }
            throw new AssertionFailure("API " + method + " " + fullUrl + " вернул " + actual
                    + ", ожидалось " + expected);
        }

        String body = response.getBody() == null ? "" : response.getBody().asString();
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.getHeaders().asList().forEach(h ->
                responseHeaders.merge(h.getName(), h.getValue(), (a, b) -> a + ", " + b));

        String detail = buildApiCallDetail(method, fullUrl, actual, responseHeaders, body);
        log.info("API-шаг {}: {}", stepIndex + 1, detail);
        return new ApiStepResult(new StepEnrichment(stepIndex, "apiCall", detail),
                body, responseHeaders, actual);
    }

    private String buildApiCallDetail(String method, String fullUrl, int status,
                                      Map<String, String> responseHeaders, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("apiCall → `").append(method).append(" ").append(fullUrl)
                .append("` → HTTP ").append(status);
        String ct = findHeaderIgnoreCase(responseHeaders, "content-type");
        String cl = findHeaderIgnoreCase(responseHeaders, "content-length");
        if (ct != null) sb.append("\n   • Content-Type: ").append(ct);
        if (cl != null) sb.append("\n   • Content-Length: ").append(cl);
        if (body != null && !body.isBlank()) {
            String trimmed = body.length() > 200 ? body.substring(0, 200) + "…" : body;
            sb.append("\n   • sample body: ").append(trimmed.replace("\n", "\\n"));
        }
        return sb.toString();
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
