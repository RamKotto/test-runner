package application.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки клиента OpenRouter с префиксом {@code openrouter}.
 * <p>
 * Значения используются при создании HTTP-клиента в {@link HttpConfig} и при формировании запроса
 * в {@link application.service.OpenRouterClient}. Значения по умолчанию в классе действуют, если
 * ключ не задан в конфигурации; application.yml переопределяет часть из них.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "openrouter")
public class OpenRouterProperties {

    /**
     * Базовый URL OpenRouter API. По умолчанию {@code https://openrouter.ai/api/v1}.
     */
    @NotBlank
    private String baseUrl = "https://openrouter.ai/api/v1";

    /**
     * API-ключ, передаётся в заголовке {@code Authorization: Bearer}. В application.yml берётся из
     * переменной окружения {@code OPENROUTER_API_KEY} и не имеет значения по умолчанию; без действующего
     * ключа OpenRouter отклоняет запросы, и планирование шагов завершается ошибкой.
     */
    @NotBlank
    private String apiKey;

    /**
     * Идентификатор модели. По умолчанию {@code openrouter/free}; application.yml задаёт
     * {@code anthropic/claude-haiku-5.5}.
     */
    @NotBlank
    private String model = "openrouter/free";

    /**
     * Максимальное число токенов ответа модели (поле {@code max_tokens}), не меньше 1. По умолчанию
     * 4000; application.yml задаёт 64000.
     */
    @Min(1)
    private int maxOutputTokens = 4000;

    /**
     * Таймаут чтения ответа модели, в секундах (не меньше 1). По умолчанию 120; application.yml
     * задаёт 2400.
     */
    @Min(1)
    private int timeoutSeconds = 120;

    /**
     * Таймаут установки соединения с OpenRouter, в секундах (не меньше 1). По умолчанию 30.
     */
    @Min(1)
    private int connectTimeoutSeconds = 30;

    /**
     * Значение заголовка {@code HTTP-Referer}, которое OpenRouter использует для идентификации
     * приложения. По умолчанию {@code http://localhost:8083}.
     */
    private String referer = "http://localhost:8083";

    /**
     * Значение заголовка {@code X-Title} с отображаемым именем приложения. По умолчанию
     * {@code test-runner}.
     */
    private String title = "test-runner";
}
