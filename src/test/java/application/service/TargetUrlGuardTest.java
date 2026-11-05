package application.service;

import application.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Модульные тесты {@link TargetUrlGuard}: allowlist хостов, нормализация регистра,
 * игнорирование порта, отказ для не-http(s) схем, URL без схемы, пустых значений
 * и userinfo-трюка вида {@code http://localhost@evil.com/}.
 */
class TargetUrlGuardTest {

    /**
     * Создаёт guard с заданным allowlist-ом хостов.
     *
     * @param hosts хосты, которые попадут в {@code app.target.allowed-hosts}
     * @return guard, сконфигурированный только на указанные хосты
     */
    private static TargetUrlGuard guardWithHosts(String... hosts) {
        AppProperties props = new AppProperties();
        props.getTarget().setAllowedHosts(List.of(hosts));
        return new TargetUrlGuard(props);
    }

    /**
     * Дефолтная конфигурация (совпадает с application.yml) разрешает только localhost.
     */
    @Test
    void defaultConfig_allowsLocalhostOnly() {
        TargetUrlGuard guard = new TargetUrlGuard(new AppProperties());

        assertThat(guard.isAllowed("http://localhost:8083/api/health")).isTrue();
        assertThat(guard.isAllowed("http://host.docker.internal:3000/")).isFalse();
    }

    @Test
    void hostDockerInternal_allowedWhenListedExplicitly() {
        TargetUrlGuard guard = guardWithHosts("localhost", "host.docker.internal");

        assertThat(guard.isAllowed("http://host.docker.internal:3000")).isTrue();
        assertThat(guard.isAllowed("http://localhost:3000")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/",
            "https://evil.example.org/x",
            "http://127.0.0.1:8083/",
            "http://localhost.evil.com/",
            "http://evil-localhost/",
            "http://localhost@evil.com/"
    })
    void otherHostsAreRejected(String url) {
        TargetUrlGuard guard = guardWithHosts("localhost");

        assertThat(guard.isAllowed(url)).isFalse();
    }

    @Test
    void hostComparisonIsCaseInsensitive_forUrlAndConfig() {
        TargetUrlGuard guard = guardWithHosts("LocalHost", "  API.Example.COM  ");

        assertThat(guard.isAllowed("http://LOCALHOST:8080/")).isTrue();
        assertThat(guard.isAllowed("http://localhost/")).isTrue();
        assertThat(guard.isAllowed("https://api.example.com/v1")).isTrue();
        assertThat(guard.isAllowed("https://API.EXAMPLE.com/v1")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:1/",
            "http://localhost:8083/api",
            "https://localhost:65535/x",
            "http://localhost/"
    })
    void portDoesNotAffectAllowlistCheck(String url) {
        TargetUrlGuard guard = guardWithHosts("localhost");

        assertThat(guard.isAllowed(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://localhost/file.txt",
            "file:///localhost/etc",
            "localhost:8083/api",
            "localhost/api",
            "//localhost/api",
            "/api/health"
    })
    void nonHttpOrSchemelessUrlsAreRejected(String url) {
        TargetUrlGuard guard = guardWithHosts("localhost");

        assertThat(guard.isAllowed(url)).isFalse();
        assertThat(guard.hostOf(url)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankUrlIsRejected(String url) {
        TargetUrlGuard guard = guardWithHosts("localhost");

        assertThat(guard.isAllowed(url)).isFalse();
        assertThat(guard.hostOf(url)).isNull();
    }

    @Test
    void hostOf_returnsLowerCaseHostWithoutPortOrPath() {
        TargetUrlGuard guard = guardWithHosts("localhost");

        assertThat(guard.hostOf("https://API.Example.com:8443/a?b=1")).isEqualTo("api.example.com");
        assertThat(guard.hostOf("http://localhost")).isEqualTo("localhost");
        assertThat(guard.hostOf("  http://LocalHost:3000/x  ")).isEqualTo("localhost");
    }

    @Test
    void hostOf_returnsRealHostForUserinfoUrl() {
        TargetUrlGuard guard = guardWithHosts("localhost");

        assertThat(guard.hostOf("http://localhost@evil.com/")).isEqualTo("evil.com");
    }
}
