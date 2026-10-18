package application.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @Valid
    private Storage storage = new Storage();
    @Valid
    private Download download = new Download();
    @Valid
    private Playwright playwright = new Playwright();
    @Valid
    private Sessions sessions = new Sessions();
    @Valid
    private Prompts prompts = new Prompts();

    @Data
    public static class Storage {
        private Path directory = Path.of("./data/enriched");
        @NotBlank
        private String baseUrl = "http://localhost:8083";
    }

    @Data
    public static class Download {
        private Path directory = Path.of("./data/downloaded");
        @Min(1)
        private int connectTimeoutSeconds = 10;
        @Min(1)
        private int readTimeoutSeconds = 120;
        private List<String> allowedHosts = new ArrayList<>(List.of("localhost"));
    }

    @Data
    public static class Playwright {
        private boolean headless = true;
        @Min(1000)
        private int navigationTimeoutMs = 30000;
        @Min(1000)
        private int actionTimeoutMs = 10000;
    }

    @Data
    public static class Sessions {
        @Min(1)
        private int ttlMinutes = 180;
        @Min(1)
        private int maxCount = 500;
        @Min(1)
        private int maxMarkdownChars = 1_000_000;
    }

    @Data
    public static class Prompts {
        @NotBlank
        private String planStep;
    }
}
