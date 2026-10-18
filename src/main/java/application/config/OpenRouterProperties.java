package application.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "openrouter")
public class OpenRouterProperties {

    @NotBlank
    private String baseUrl = "https://openrouter.ai/api/v1";
    @NotBlank
    private String apiKey;
    @NotBlank
    private String model = "openrouter/free";
    @Min(1)
    private int maxOutputTokens = 4000;
    @Min(1)
    private int timeoutSeconds = 120;
    @Min(1)
    private int connectTimeoutSeconds = 30;
    private String referer = "http://localhost:8083";
    private String title = "test-runner";
}
