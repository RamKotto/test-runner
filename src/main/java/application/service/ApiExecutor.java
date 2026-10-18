package application.service;

import application.dto.Dtos.StepEnrichment;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;

@Slf4j
@Service
public class ApiExecutor {

    public StepEnrichment executeStep(int stepIndex,
                                      StepPlanner.Plan plan,
                                      String apiBaseUrl,
                                      Map<String, String> headers,
                                      Map<String, String> pathParams) {
        String method = plan.method() == null ? "GET" : plan.method().toUpperCase();
        String path = plan.path();

        // Пытаемся вычислить полный URL
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

        String detail = "apiCall → `" + method + " " + fullUrl + "` → HTTP " + actual;
        log.info("API-шаг {}: {}", stepIndex + 1, detail);
        return new StepEnrichment(stepIndex, "apiCall", detail);
    }

    /**
     * Собирает полный URL:
     * - apiBaseUrl + path (если apiBaseUrl — база, path — путь)
     * - path как полный URL (если LLM вернул полный URL)
     * - apiBaseUrl уже содержит path (если LLM вернул полный URL в value)
     */
    private String buildFullUrl(String apiBaseUrl, String path) {
        // Если path — полный URL, используем его как есть
        if (path != null && (path.startsWith("http://") || path.startsWith("https://"))) {
            return path;
        }
        // Если apiBaseUrl — полный URL на конкретный ресурс (содержит путь)
        // и path пустой — используем apiBaseUrl
        if ((path == null || path.isBlank()) && apiBaseUrl != null
                && (apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://"))) {
            return apiBaseUrl;
        }
        // Стандартная склейка
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
