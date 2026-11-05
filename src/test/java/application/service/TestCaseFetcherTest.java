package application.service;

import application.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Тесты {@link TestCaseFetcher}: конфигурация allowlist хостов не падает на {@code null}-элементах,
 * а запрещённый хост отклоняется до сетевого запроса.
 */
class TestCaseFetcherTest {

    /**
     * Список хостов с {@code null} и пустым элементом не даёт NPE при создании fetcher.
     */
    @Test
    void constructor_toleratesNullAndBlankHostEntries() {
        AppProperties props = new AppProperties();
        props.getDownload().setAllowedHosts(new ArrayList<>(Arrays.asList(null, " ", "Docs.Example.COM")));

        assertThatCode(() -> new TestCaseFetcher(mock(RestClient.class), mock(TestCaseParser.class), props))
                .doesNotThrowAnyException();
    }

    /**
     * Хост, которого нет в allowlist, отклоняется до запроса; после фильтрации null-элементов
     * allowlist из одного валидного хоста не пуст и не возвращается к localhost.
     */
    @Test
    void fetchAll_rejectsHostOutsideAllowlist_beforeAnyRequest() {
        AppProperties props = new AppProperties();
        props.getDownload().setAllowedHosts(new ArrayList<>(Arrays.asList(null, "docs.example.com")));
        TestCaseFetcher fetcher = new TestCaseFetcher(mock(RestClient.class), mock(TestCaseParser.class), props);

        assertThatThrownBy(() -> fetcher.fetchAll("http://localhost/spec/x.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Хост не разрешён");
    }

    /**
     * Пустой список (после фильтрации) по-прежнему означает разрешённый localhost.
     */
    @Test
    void emptyAllowlistFallsBackToLocalhost() {
        AppProperties props = new AppProperties();
        props.getDownload().setAllowedHosts(new ArrayList<>(Arrays.asList((String) null)));
        TestCaseFetcher fetcher = new TestCaseFetcher(mock(RestClient.class), mock(TestCaseParser.class), props);

        assertThatThrownBy(() -> fetcher.fetchAll("http://example.com/spec/x.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }
}
