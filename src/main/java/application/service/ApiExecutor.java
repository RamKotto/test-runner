package application.service;

import application.dto.Dtos.StepEnrichment;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class ApiExecutor {

    public StepEnrichment executeStep(int stepIndex,
                                      StepPlanner.Plan plan,
                                      String apiBaseUrl,
                                      Map<String, String> headers,
                                      Map<String, String> pathParams) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new UiExecutor.LocatorNotResolved("не указан apiBaseUrl для API-кейса");
        }
        String method = plan.method() == null ? "GET" : plan.method().toUpperCase();
        String path = plan.path();
        if (path == null || path.isBlank()) {
            throw new UiExecutor.LocatorNotResolved("LLM не вернула path для API-запроса");
        }

        String fullUrl = apiBaseUrl.endsWith("/") && path.startsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) + path
                : apiBaseUrl + path;

        RequestSpecification req = RestAssured.given().baseUri(apiBaseUrl).relaxedHTTPSValidation();
        if (headers != null) headers.forEach(req::header);
        if (pathParams != null) req.pathParams(pathParams);
        if (plan.bodyJson() != null && !plan.bodyJson().isBlank() && !"{}".equals(plan.bodyJson())) {
            req.contentType("application/json").body(plan.bodyJson());
        }

        Response response;
        try {
            response = switch (method) {
                case "GET" -> req.get(path);
                case "POST" -> req.post(path);
                case "PUT" -> req.put(path);
                case "DELETE" -> req.delete(path);
                case "PATCH" -> req.patch(path);
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
                    "API " + method + " " + path + " вернул " + actual
                            + ", ожидалось " + expected);
        }

        String detail = "apiCall → `" + method + " " + fullUrl + "` → HTTP " + actual;
        log.info("API-шаг {}: {}", stepIndex + 1, detail);
        return new StepEnrichment(stepIndex, "apiCall", detail);
    }
}
