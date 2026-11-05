package application.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки приложения с префиксом {@code app} (application.yml и переменные окружения).
 * <p>
 * Корневой объект объединяет секции: ссылки на артефакты ({@link Storage}), allowlist целевых
 * хостов ({@link Target}), скачивание тест-кейсов ({@link Download}), параметры браузера
 * ({@link Playwright}), системный промпт планировщика
 * ({@link Prompts}) и параметры планировщика ({@link Planner}). Аннотации Bean Validation
 * проверяются при старте приложения.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /**
     * Ссылки на артефакты в БД: базовый URL для адресов {@code <baseUrl>/api/files/<uuid>} и архива прогона.
     */
    @Valid
    private Storage storage = new Storage();

    /**
     * Allowlist хостов, на которые разрешено обращаться при исполнении кейсов.
     */
    @Valid
    private Target target = new Target();

    /**
     * Скачивание тест-кейсов: таймауты и allowlist хостов-источников.
     */
    @Valid
    private Download download = new Download();

    /**
     * Параметры браузера Playwright, используемого для UI-шагов.
     */
    @Valid
    private Playwright playwright = new Playwright();

    /**
     * Системные промпты, передаваемые в LLM.
     */
    @Valid
    private Prompts prompts = new Prompts();

    /**
     * Параметры планировщика шагов.
     */
    @Valid
    private Planner planner = new Planner();

    /**
     * Максимальная длина тела ответа API в обогащении шага, в символах; значение {@code 0} означает
     * отсутствие ограничения. Параметр не влияет на полный ответ, который получает планировщик.
     */
    private int enrichmentBodyLimit = 0;

    /**
     * Настройки ссылок на артефакты, которые хранятся в таблице {@code qa.artifacts}.
     */
    @Data
    public static class Storage {

        /**
         * Базовый URL сервиса, из которого формируются ссылки вида {@code <baseUrl>/api/files/<uuid>}
         * и архив прогона. Переменная окружения: {@code APP_STORAGE_BASE_URL}.
         */
        @NotBlank
        private String baseUrl = "http://localhost:8083";
    }

    /**
     * Allowlist хостов для исполнения кейсов: {@code startUrl} (браузер), {@code apiBaseUrl} и
     * абсолютные URL из планов LLM (RestAssured). Сравнение точное, без учёта регистра; порт не
     * проверяется. Проверку выполняет {@link application.service.TargetUrlGuard}.
     */
    @Data
    public static class Target {

        /**
         * Допустимые имена хостов. По умолчанию {@code localhost}. Переменная окружения:
         * {@code APP_TARGET_ALLOWED_HOSTS} (несколько значений через запятую).
         */
        private List<String> allowedHosts = new ArrayList<>(List.of("localhost"));
    }

    /**
     * Скачивание тест-кейсов из Markdown-источника (исходный файл, {@code _index.md} и файлы из него).
     */
    @Data
    public static class Download {

        /**
         * Таймаут установки соединения, в секундах (не меньше 1).
         */
        @Min(1)
        private int connectTimeoutSeconds = 10;

        /**
         * Таймаут чтения ответа, в секундах (не меньше 1).
         */
        @Min(1)
        private int readTimeoutSeconds = 300;

        /**
         * Хосты, с которых разрешено скачивать тест-кейсы. По умолчанию {@code localhost}; если список
         * пуст, сервис скачивания тоже использует {@code localhost}. Переменная окружения:
         * {@code APP_DOWNLOAD_ALLOWED_HOSTS}.
         */
        private List<String> allowedHosts = new ArrayList<>(List.of("localhost"));
    }

    /**
     * Настройки браузера Playwright (Chromium), который запускается при старте приложения.
     */
    @Data
    public static class Playwright {

        /**
         * Запускать браузер без окна. По умолчанию {@code true}.
         */
        private boolean headless = true;

        /**
         * Таймаут навигации страницы, в миллисекундах (не меньше 1000).
         */
        @Min(1000)
        private int navigationTimeoutMs = 30000;

        /**
         * Таймаут действий и ожидания элементов, в миллисекундах (не меньше 1000).
         */
        @Min(1000)
        private int actionTimeoutMs = 10000;
    }

    /**
     * Системные промпты LLM.
     */
    @Data
    public static class Prompts {

        /**
         * Системный промпт планировщика, который переводит текст шага в операции исполнения.
         * Задаётся ключом {@code app.prompts.plan-step} в application.yml и обязателен.
         */
        @NotBlank
        private String planStep;
    }

    /**
     * Параметры планировщика шагов.
     */
    @Data
    public static class Planner {

        /**
         * Лимит размера входа модели, в символах (не меньше 0). В текущей версии значение не
         * применяется: планировщик не проверяет длину промпта и не возвращает ошибку переполнения.
         */
        @Min(0)
        private int maxInputChars = 300_000;
    }
}
