package application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Конфигурация HTTP-клиентов {@link RestClient}, которые создаются как бины контекста.
 * <p>
 * Определены два клиента: {@code openRouterRestClient} для обращений к LLM и
 * {@code fileFetchRestClient} для скачивания тест-кейсов. Их таймауты берутся из
 * {@link OpenRouterProperties} и {@link AppProperties}.
 */
@Configuration
public class HttpConfig {

    /**
     * Создаёт клиент OpenRouter с базовым URL, таймаутами и заголовками из {@link OpenRouterProperties}.
     * <p>
     * Каждый запрос получает заголовки {@code Authorization: Bearer <apiKey>}, {@code HTTP-Referer} и
     * {@code X-Title}. Значения фиксируются при создании бина.
     *
     * @param props настройки клиента OpenRouter
     * @return настроенный {@link RestClient} для вызовов {@code /chat/completions}
     */
    @Bean
    public RestClient openRouterRestClient(OpenRouterProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.getTimeoutSeconds()));
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .defaultHeader("HTTP-Referer", props.getReferer())
                .defaultHeader("X-Title", props.getTitle())
                .build();
    }

    /**
     * Создаёт клиент для скачивания тест-кейсов без базового URL и без заголовков.
     * <p>
     * Таймауты берутся из {@code app.download}. Клиент использует стандартную обработку редиректов
     * {@link SimpleClientHttpRequestFactory}, которая для GET следует за редиректами. Поэтому проверка
     * allowlist, выполняемая в {@link application.service.TestCaseFetcher}, относится только к
     * исходному URL.
     *
     * @param appProps настройки приложения, из которых читаются таймауты скачивания
     * @return {@link RestClient} для GET-запросов файлов тест-кейсов
     */
    @Bean
    public RestClient fileFetchRestClient(AppProperties appProps) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(appProps.getDownload().getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(appProps.getDownload().getReadTimeoutSeconds()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
