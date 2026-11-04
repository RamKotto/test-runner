package application.service;

import application.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Защита от SSRF при исполнении тест-кейсов.
 * <p>
 * Исполнители {@link ApiExecutor} и {@link UiExecutor} обращаются к сети только
 * по URL, хост которых входит в список {@code app.target.allowed-hosts}.
 * Разрешены лишь схемы {@code http} и {@code https}; хост сравнивается точно,
 * без учёта регистра, порт и путь на результат проверки не влияют.
 * <p>
 * Экземпляр неизменяем после создания и потокобезопасен.
 */
@Slf4j
@Service
public class TargetUrlGuard {

    /**
     * Нормализованный (trim, нижний регистр) allowlist хостов.
     * Пустой набор означает, что все хосты запрещены.
     */
    private final Set<String> allowedHosts;

    /**
     * Строит allowlist из {@code app.target.allowed-hosts}.
     * <p>
     * Null-значения и пустые строки отбрасываются; остальные элементы обрезаются
     * и приводятся к нижнему регистру ({@link Locale#ROOT}). Если список не задан
     * (null), allowlist становится пустым и все цели отклоняются.
     *
     * @param props конфигурация приложения; из неё читается список допустимых хостов
     */
    public TargetUrlGuard(AppProperties props) {
        var cfg = props.getTarget().getAllowedHosts();
        this.allowedHosts = cfg == null ? Set.of() : cfg.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        log.info("TargetUrlGuard: allowedHosts = {}", allowedHosts);
    }

    /**
     * Проверяет, можно ли обращаться к указанному URL.
     * <p>
     * URL допустим, если {@link #hostOf(String)} возвращает хост и этот хост
     * присутствует в allowlist. Для userinfo-трюков вида
     * {@code http://localhost@evil.com/} учитывается реальный хост ({@code evil.com}).
     *
     * @param url проверяемый URL; может быть null или пустым
     * @return {@code true}, если url — абсолютный http(s)-URL с хостом из allowlist;
     * {@code false} во всех остальных случаях, включая null и невалидный URL
     */
    public boolean isAllowed(String url) {
        String host = hostOf(url);
        return host != null && allowedHosts.contains(host);
    }

    /**
     * Извлекает хост из абсолютного http(s)-URL.
     * <p>
     * Строка предварительно обрезается пробелами. Схема должна быть {@code http}
     * или {@code https} (без учёта регистра). Относительные URL, URL без схемы,
     * URL с иной схемой и строки, которые не разбираются как {@link URI},
     * дают {@code null}.
     *
     * @param url URL для разбора; может быть null или пустым
     * @return хост в нижнем регистре либо {@code null}, если url не является
     * абсолютным http(s)-URL с хостом
     */
    public String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return null;
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
