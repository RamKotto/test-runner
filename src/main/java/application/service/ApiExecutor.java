package application.service;

import application.dto.Dtos.StepEnrichment;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class ApiExecutor {

    private final ObjectMapper mapper = new ObjectMapper();

    public record ApiStepResult(StepEnrichment enrichment,
                                String responseBody,
                                Map<String, String> responseHeaders) {
    }

    public ApiStepResult executeStep(int stepIndex,
                                     StepPlanner.Plan plan,
                                     String apiBaseUrl,
                                     Map<String, String> headers,
                                     Map<String, String> pathParams,
                                     String lastResponseBody,
                                     Map<String, String> lastResponseHeaders) {

        String op = plan.operation() == null ? "" : plan.operation().toLowerCase();

        // assertBody — проверка подстроки в уже полученном теле ответа
        if ("assertbody".equals(op)) {
            String expected = plan.value();
            if (expected == null || expected.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("assertBody: не указано значение для проверки");
            }
            if (lastResponseBody == null) {
                throw new UiExecutor.LocatorNotResolved(
                        "assertBody: нет тела предыдущего ответа — сначала выполните apiCall");
            }
            if (!lastResponseBody.contains(expected)) {
                throw new UiExecutor.LocatorNotResolved(
                        "assertBody: тело ответа не содержит `" + expected + "`");
            }
            StepEnrichment e = new StepEnrichment(stepIndex, "assertBody",
                    "assertBody → тело ответа содержит `" + expected + "`");
            log.info("API-шаг {}: {}", stepIndex + 1, e.detail());
            return new ApiStepResult(e, lastResponseBody, lastResponseHeaders);
        }

        // assertHeader — проверка заголовка последнего ответа
        if ("assertheader".equals(op)) {
            String name = plan.locator();
            String expected = plan.value();
            if (name == null || name.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("assertHeader: не указано имя заголовка");
            }
            if (expected == null || expected.isBlank()) {
                throw new UiExecutor.LocatorNotResolved("assertHeader: не указано ожидаемое значение");
            }
            if (lastResponseHeaders == null || lastResponseHeaders.isEmpty()) {
                throw new UiExecutor.LocatorNotResolved(
                        "assertHeader: нет заголовков предыдущего ответа — сначала выполните apiCall");
            }
            String actual = findHeaderIgnoreCase(lastResponseHeaders, name);
            if (actual == null) {
                throw new UiExecutor.LocatorNotResolved(
                        "assertHeader: заголовок `" + name + "` отсутствует в ответе");
            }
            if (!actual.toLowerCase().contains(expected.toLowerCase())) {
                throw new UiExecutor.LocatorNotResolved(
                        "assertHeader: `" + name + "` = `" + actual
                                + "`, ожидалось вхождение `" + expected + "`");
            }
            StepEnrichment e = new StepEnrichment(stepIndex, "assertHeader",
                    "assertHeader → `" + name + "` содержит `" + expected + "`");
            log.info("API-шаг {}: {}", stepIndex + 1, e.detail());
            return new ApiStepResult(e, lastResponseBody, lastResponseHeaders);
        }

        // parseJson — проверка, что тело последнего ответа является валидным JSON
        if ("parsejson".equals(op)) {
            if (lastResponseBody == null || lastResponseBody.isBlank()) {
                throw new UiExecutor.LocatorNotResolved(
                        "parseJson: нет тела предыдущего ответа — сначала выполните apiCall");
            }
            try {
                mapper.readTree(lastResponseBody);
            } catch (Exception e) {
                throw new UiExecutor.LocatorNotResolved(
                        "parseJson: тело ответа не является валидным JSON: " + e.getMessage());
            }
            StepEnrichment e = new StepEnrichment(stepIndex, "parseJson",
                    "parseJson → тело ответа успешно разобрано как JSON");
            log.info("API-шаг {}: {}", stepIndex + 1, e.detail());
            return new ApiStepResult(e, lastResponseBody, lastResponseHeaders);
        }

        // apiCall — отправка запроса
        String method = plan.method() == null ? "" : plan.method().trim().toUpperCase();
        if (method.isEmpty()) method = "GET";
        String path = plan.path();

        String fullUrl = buildFullUrl(apiBaseUrl, path);
        if (fullUrl == null || fullUrl.isBlank()) {
            throw new UiExecutor.LocatorNotResolved("не указан apiBaseUrl и path для API-кейса");
        }

        URI uri = URI.create(fullUrl);
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

        Response response;
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
        } catch (UiExecutor.LocatorNotResolved e) {
            throw e;
        } catch (Exception e) {
            log.warn("Ошибка вызова API {} {}: {}", method, fullUrl, e.getMessage());
            throw new UiExecutor.LocatorNotResolved("ошибка вызова API: " + e.getMessage());
        }

        int actual = response.statusCode();
        Integer expected = plan.expectedStatus();
        if (expected != null && actual != expected) {
            throw new UiExecutor.LocatorNotResolved(
                    "API " + method + " " + fullUrl + " вернул " + actual
                            + ", ожидалось " + expected);
        }

        String body = response.getBody() == null ? "" : response.getBody().asString();
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.getHeaders().asList().forEach(h ->
                responseHeaders.merge(h.getName(), h.getValue(), (a, b) -> a + ", " + b));

        String detail = "apiCall → `" + method + " " + fullUrl + "` → HTTP " + actual;
        log.info("API-шаг {}: {}", stepIndex + 1, detail);
        return new ApiStepResult(
                new StepEnrichment(stepIndex, "apiCall", detail),
                body,
                responseHeaders);
    }

    private String findHeaderIgnoreCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private String buildFullUrl(String apiBaseUrl, String path) {
        if (path != null && (path.startsWith("http://") || path.startsWith("https://"))) {
            return path;
        }
        if ((path == null || path.isBlank()) && apiBaseUrl != null
                && (apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://"))) {
            return apiBaseUrl;
        }
        if (apiBaseUrl != null && path != null) {
            if (apiBaseUrl.endsWith("/") && path.startsWith("/")) {
                return apiBaseUrl.substring(0, apiBaseUrl.length() - 1) + path;
            }
            if (!apiBaseUrl.endsWith("/") && !path.startsWith("/")) {
                return apiBaseUrl + "/" + path;
            }
            return apiBaseUrl + path;
        }
        return null;
    }
}
