package application.service;

import application.config.AppProperties;
import application.dto.Dtos.ElementInfo;
import application.dto.Dtos.StepEnrichment;
import application.exception.AssertionFailure;
import application.exception.TargetNotAllowedException;
import com.microsoft.playwright.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Исполнитель UI-шагов тест-кейса на базе Playwright (Chromium).
 * <p>
 * Место в конвейере: {@link HybridCaseRunner} открывает браузерную сессию
 * через {@link #newSession(String)} и передаёт сюда UI-шаги, которые
 * {@link StepPlanner} пометил как браузерные. Поддерживаются операции
 * {@code navigate}, {@code click}, {@code fill}, {@code assertText},
 * {@code waitForText}, {@code assertPageText}, а также снимок и сравнение
 * текста элемента ({@code snapshotUiText}, {@code assertUiTextEqualsSnapshot}).
 * <p>
 * Ошибки, которые означают провал теста, выбрасываются как
 * {@link AssertionFailure}; неполный или неоднозначный план — как
 * {@link LocatorNotResolved}, то есть запрос уточнения у пользователя.
 * Переход на хост вне {@code app.target.allowed-hosts} отклоняется через
 * {@link TargetUrlGuard}.
 * <p>
 * Экземпляр создаётся Spring-ом: конструктор запускает Playwright и Chromium,
 * метод {@link #shutdown()} останавливает их при остановке контекста.
 */
@Slf4j
@Service
public class UiExecutor {

    /**
     * Корневой объект Playwright, владеющий драйвером браузера.
     */
    private final Playwright playwright;

    /**
     * Общий экземпляр Chromium. Каждая сессия создаёт из него изолированный контекст.
     */
    private final Browser browser;

    /**
     * Настройки приложения: headless-режим и таймауты Playwright.
     */
    private final AppProperties appProps;

    /**
     * Проверка allowlist-а хостов для стартового URL, навигации и маршрутизации запросов.
     */
    private final TargetUrlGuard targetUrlGuard;

    /**
     * Запускает Playwright и Chromium с настройками {@code app.playwright}.
     *
     * @param appProps       настройки приложения
     * @param targetUrlGuard защита от перехода на хосты вне allowlist-а
     */
    public UiExecutor(AppProperties appProps, TargetUrlGuard targetUrlGuard) {
        this.appProps = appProps;
        this.targetUrlGuard = targetUrlGuard;
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(appProps.getPlaywright().isHeadless()));
        log.info("Playwright запущен (headless={})", appProps.getPlaywright().isHeadless());
    }

    /**
     * Создаёт новую изолированную браузерную сессию и при необходимости открывает стартовый URL.
     * <p>
     * Стартовый URL проверяется через {@link #requireAllowedUiUrl(String, String)} до
     * создания контекста. В контексте установлен маршрутизатор: навигационные запросы
     * (включая редиректы) на хосты вне allowlist-а отменяются, остальные запросы
     * пропускаются. Subresource-запросы не блокируются, иначе страницы перестанут работать.
     * Таймауты действий и навигации берутся из {@code app.playwright}.
     * <p>
     * Если открыть стартовый URL не удалось, контекст закрывается и исключение пробрасывается.
     *
     * @param startUrl стартовый URL; {@code null} или пустая строка означают
     *                 создание сессии без навигации
     * @return открытая сессия; её нужно закрыть через {@link UiSession#close()}
     * @throws AssertionFailure          стартовый URL не является http(s)-адресом либо
     *                                   браузер не смог открыть страницу (в том числе из-за
     *                                   редиректа на запрещённый хост)
     * @throws TargetNotAllowedException стартовый URL указывает на хост вне allowlist-а
     */
    public UiSession newSession(String startUrl) {
        boolean hasStart = startUrl != null && !startUrl.isBlank();
        if (hasStart) {
            requireAllowedUiUrl(startUrl, "стартовый URL");
        }
        BrowserContext ctx = browser.newContext();
        try {
            ctx.route("**/*", route -> {
                Request request = route.request();
                if (request.isNavigationRequest() && !targetUrlGuard.isAllowed(request.url())) {
                    log.warn("UI: навигация на {} заблокирована (хост вне app.target.allowed-hosts)",
                            request.url());
                    route.abort();
                } else {
                    route.resume();
                }
            });
            Page page = ctx.newPage();
            page.setDefaultTimeout(appProps.getPlaywright().getActionTimeoutMs());
            page.setDefaultNavigationTimeout(appProps.getPlaywright().getNavigationTimeoutMs());
            if (hasStart) {
                try {
                    page.navigate(startUrl);
                } catch (PlaywrightException e) {
                    throw new AssertionFailure("Не удалось открыть стартовый URL " + startUrl
                            + " (возможно, перенаправление на хост вне app.target.allowed-hosts): "
                            + truncate(e.getMessage(), 300), e);
                }
            }
            return new UiSession(ctx, page);
        } catch (RuntimeException e) {
            try {
                ctx.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /**
     * Проверяет URL, который будет открыт в браузере: он должен быть абсолютным
     * http(s)-адресом и входить в {@code app.target.allowed-hosts}.
     *
     * @param url  проверяемый URL
     * @param what человеческое название URL для текста ошибки, например «стартовый URL»
     * @throws AssertionFailure          URL не является абсолютным http(s)-адресом
     * @throws TargetNotAllowedException хост URL не входит в allowlist
     */
    private void requireAllowedUiUrl(String url, String what) {
        String host = targetUrlGuard.hostOf(url);
        if (host == null) {
            throw new AssertionFailure("некорректный " + what + " (нужен http(s)://host/...): `"
                    + url + "`");
        }
        if (!targetUrlGuard.isAllowed(url)) {
            throw new TargetNotAllowedException("Хост не разрешён для UI: " + host
                    + ". Добавьте его в app.target.allowed-hosts");
        }
    }

    /**
     * Закрывает браузер и останавливает Playwright при остановке контекста Spring.
     * Ошибка закрытия браузера не мешает остановке Playwright, и наоборот; обе
     * ошибки логируются как предупреждения.
     */
    @PreDestroy
    public void shutdown() {
        try {
            browser.close();
        } catch (Exception e) {
            log.warn("Не удалось закрыть браузер: {}", e.getMessage());
        }
        try {
            playwright.close();
        } catch (Exception e) {
            log.warn("Не удалось остановить Playwright: {}", e.getMessage());
        }
        log.info("Playwright и браузер остановлены");
    }

    /**
     * Строит текстовый снимок DOM страницы для планировщика LLM.
     * <p>
     * Обходится дерево от {@code document.body}. В снимок попадают только
     * «интересные» элементы: интерактивные и структурные теги (ссылки, кнопки,
     * поля ввода, заголовки, таблицы, навигация и т.п.), а также элементы с
     * {@code id}, {@code data-testid}, {@code role}, {@code aria-label} или классом
     * из набора признаков (btn, button, link, input, card, nav, menu, modal, form,
     * code, pre). Каждая строка имеет вид {@code <tag атрибуты> "текст"}, отступ
     * равен глубине вложенности среди включённых элементов. Атрибуты {@code value},
     * {@code checked}, {@code selected} и {@code disabled} выводятся по состоянию
     * DOM, а не по HTML-атрибутам.
     *
     * @param session сессия, страница которой снимается
     * @return текст снимка, строки разделены {@code \n}; пустая строка при ошибке
     *         выполнения JavaScript (ошибка логируется на уровне debug)
     */
    public String snapshotForPlanning(UiSession session) {
        try {
            return (String) session.page().evaluate("""
                        () => {
                            const out = [];
                            const interesting = new Set([
                                'A','BUTTON','INPUT','SELECT','TEXTAREA','FORM','LABEL',
                                'H1','H2','H3','H4','H5','H6','TABLE','THEAD','TBODY','TR','TD','TH',
                                'PRE','CODE','NAV','MAIN','HEADER','FOOTER','SECTION','ARTICLE'
                            ]);
                            const walk = (el, depth) => {
                                if (!el || el.nodeType !== 1) return;
                                const tag = el.tagName;
                                const role = el.getAttribute('role');
                                const testid = el.getAttribute('data-testid')
                                             || el.getAttribute('data-test-id')
                                             || el.getAttribute('data-test');
                                const id = el.id;
                                const cls = (el.className && typeof el.className === 'string')
                                             ? el.className.trim() : '';
                                const ariaLabel = el.getAttribute('aria-label');
                                const ariaChecked = el.getAttribute('aria-checked');
                                const ariaExpanded = el.getAttribute('aria-expanded');
                                const ariaSelected = el.getAttribute('aria-selected');
                                const name = el.getAttribute('name');
                                const type = el.getAttribute('type');
                                const href = tag === 'A' ? el.getAttribute('href') : null;
                                const placeholder = el.getAttribute('placeholder');
                                const value = (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT')
                                             ? (el.value || '') : null;
                                const checked = (tag === 'INPUT' && (type === 'checkbox' || type === 'radio'))
                                             ? el.checked : null;
                                const selected = (tag === 'OPTION') ? el.selected : null;
                                const disabled = el.disabled === true ? true : null;
                                const isInteresting = interesting.has(tag)
                                    || testid || id || role || ariaLabel
                                    || (cls && /btn|button|link|input|card|nav|menu|modal|form|code|pre/i.test(cls));
                                let text = '';
                                if (isInteresting) {
                                    text = (el.innerText || '').trim().replace(/\\s+/g, ' ');
                                }
                                if (isInteresting) {
                                    const attrs = [];
                                    if (id) attrs.push('#' + id);
                                    if (testid) attrs.push('data-testid="' + testid + '"');
                                    if (role) attrs.push('role="' + role + '"');
                                    if (ariaLabel) attrs.push('aria-label="' + ariaLabel + '"');
                                    if (ariaChecked) attrs.push('aria-checked="' + ariaChecked + '"');
                                    if (ariaExpanded) attrs.push('aria-expanded="' + ariaExpanded + '"');
                                    if (ariaSelected) attrs.push('aria-selected="' + ariaSelected + '"');
                                    if (name) attrs.push('name="' + name + '"');
                                    if (type) attrs.push('type="' + type + '"');
                                    if (href) attrs.push('href="' + href + '"');
                                    if (placeholder) attrs.push('placeholder="' + placeholder + '"');
                                    if (value !== null) attrs.push('value="' + String(value) + '"');
                                    if (checked !== null) attrs.push('checked="' + checked + '"');
                                    if (selected !== null) attrs.push('selected="' + selected + '"');
                                    if (disabled !== null) attrs.push('disabled');
                                    if (cls) attrs.push('class="' + cls + '"');
                                    out.push(' '.repeat(depth) + '<' + tag.toLowerCase()
                                        + (attrs.length ? ' ' + attrs.join(' ') : '')
                                        + '> ' + (text ? '"' + text + '"' : ''));
                                }
                                for (const c of el.children) walk(c, depth + (isInteresting ? 1 : 0));
                            };
                            if (document.body) walk(document.body, 0);
                            return out.join('\\n');
                        }
                    """);
        } catch (Exception e) {
            log.debug("snapshotForPlanning failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Выполняет одну UI-операцию шага: {@code navigate}, {@code click}, {@code fill},
     * {@code assertText}, {@code waitForText} или {@code assertPageText}.
     * <p>
     * Поведение по операциям:
     * <ul>
     *   <li>{@code navigate}: переход на {@code plan.value()}, а при его отсутствии
     *       на {@code currentUrl}. URL проверяется по allowlist-у. Ошибка Playwright
     *       здесь не перехватывается.</li>
     *   <li>{@code click}, {@code fill}: локаторы разрешаются строго. Неоднозначный
     *       локатор, то есть матчящий более одного элемента, даёт {@link LocatorNotResolved}.
     *       Ошибка действия над элементом превращается в {@link AssertionFailure}.</li>
     *   <li>{@code assertText}: проверяет, что {@code innerText} каждого найденного
     *       элемента содержит ожидаемый текст. Неоднозначность допускается. Все
     *       несовпадения собираются и выбрасываются одним {@link AssertionFailure}.</li>
     *   <li>{@code waitForText}: ждёт появления текста в {@code document.documentElement}
     *       не дольше 10 секунд. Таймаут не зависит от {@code app.playwright}.</li>
     *   <li>{@code assertPageText}: проверяет текст {@code body} без ожидания.</li>
     * </ul>
     * Любая другая операция, включая {@code null}, отклоняется. Операции
     * {@code snapshotUiText} и {@code assertUiTextEqualsSnapshot} здесь тоже не
     * выполняются: для них есть отдельные методы {@link #snapshotUiText} и
     * {@link #assertUiTextEqualsSnapshot}.
     *
     * @param session     сессия, в которой выполняется шаг
     * @param stepIndex   индекс шага, начиная с 0 (в отчёте выводится +1)
     * @param plan        план шага от LLM
     * @param currentUrl  текущий URL страницы, используется как цель {@code navigate} без значения;
     *                    может быть {@code null}
     * @return enrichment шага с описанием найденных элементов
     * @throws AssertionFailure              проверка не прошла или действие над элементом не удалось
     * @throws LocatorNotResolved            не хватает значения, локатор не найден или неоднозначен
     * @throws TargetNotAllowedException     URL навигации вне allowlist-а
     * @throws UnsupportedUiOperation        операция не относится к UI-исполнителю
     */
    public StepEnrichment executeStep(UiSession session, int stepIndex,
                                      StepPlanner.Plan plan, String currentUrl) {
        Page page = session.page();
        String op = plan.operation() == null ? "" : plan.operation();
        switch (op) {
            case "navigate": {
                String url = plan.value() != null && !plan.value().isBlank()
                        ? plan.value() : currentUrl;
                if (url == null) throw new LocatorNotResolved("не указан URL для навигации");
                requireAllowedUiUrl(url, "URL для навигации");
                page.navigate(url);
                return StepEnrichment.ui(stepIndex, "navigate",
                        "navigate\n   • url: `" + page.url() + "`", List.of());
            }
            case "click": {
                List<ElementInfo> elements = resolveAllElements(page, plan, true);
                StringBuilder detail = new StringBuilder("click\n");
                for (ElementInfo el : elements) {
                    detail.append("   • element #").append(el.index() + 1).append("/")
                            .append(elements.size()).append("\n");
                    try {
                        page.locator(el.locator()).first().click();
                    } catch (Exception e) {
                        throw new AssertionFailure("click #" + (el.index() + 1) + " ("
                                + el.locator() + "): " + e.getMessage());
                    }
                    appendElementDetail(detail, el);
                }
                return StepEnrichment.ui(stepIndex, "click", detail.toString(), elements);
            }
            case "fill": {
                String value = plan.value();
                if (value == null) throw new LocatorNotResolved("не указано значение для fill");
                List<ElementInfo> elements = resolveAllElements(page, plan, true);
                StringBuilder detail = new StringBuilder("fill\n   • value: `")
                        .append(value).append("`\n");
                for (ElementInfo el : elements) {
                    detail.append("   • element #").append(el.index() + 1).append("/")
                            .append(elements.size()).append("\n");
                    try {
                        page.locator(el.locator()).first().fill(value);
                    } catch (Exception e) {
                        throw new AssertionFailure("fill #" + (el.index() + 1) + " ("
                                + el.locator() + "): " + e.getMessage());
                    }
                    appendElementDetail(detail, el);
                }
                return StepEnrichment.ui(stepIndex, "fill", detail.toString(), elements);
            }
            case "assertText": {
                String expected = plan.value();
                if (expected == null) throw new LocatorNotResolved("не указан ожидаемый текст");
                List<ElementInfo> elements = resolveAllElements(page, plan, false);
                StringBuilder detail = new StringBuilder("assertText\n")
                        .append("   • expected: `").append(expected).append("`\n")
                        .append("   • checked ").append(elements.size()).append(" element(s)\n");
                List<String> failures = new ArrayList<>();
                for (ElementInfo el : elements) {
                    String actual;
                    try {
                        actual = page.locator(el.locator()).first().innerText();
                    } catch (Exception e) {
                        failures.add("#" + (el.index() + 1) + " (" + el.locator() + "): " + e.getMessage());
                        continue;
                    }
                    boolean ok = actual != null && actual.contains(expected);
                    detail.append("   • element #").append(el.index() + 1).append("/")
                            .append(elements.size()).append(ok ? " ✅" : " ❌").append("\n");
                    appendElementDetail(detail, el);
                    detail.append("     actual: \"")
                            .append(escapeQuotes(truncateSmart(actual, 300)))
                            .append("\"\n");
                    if (!ok) {
                        failures.add("#" + (el.index() + 1) + " («"
                                + truncateSmart(actual, 120) + "») не содержит `"
                                + expected + "`");
                    }
                }
                if (!failures.isEmpty()) {
                    throw new AssertionFailure("assertText: " + String.join("; ", failures));
                }
                return StepEnrichment.ui(stepIndex, "assertText", detail.toString(), elements);
            }
            case "waitForText": {
                String expected = plan.value();
                if (expected == null || expected.isBlank()) {
                    throw new LocatorNotResolved("не указан текст для waitForText");
                }
                try {
                    page.waitForFunction(
                            "t => document.documentElement && document.documentElement.innerText.includes(t)",
                            expected,
                            new Page.WaitForFunctionOptions().setTimeout(10000));
                } catch (PlaywrightException e) {
                    throw new AssertionFailure(
                            "текст `" + expected + "` не появился на странице за 10 секунд");
                }
                return StepEnrichment.ui(stepIndex, "waitForText",
                        "waitForText\n   • expected: `" + expected + "`", List.of());
            }
            case "assertPageText": {
                String expected = plan.value();
                if (expected == null || expected.isBlank()) {
                    throw new LocatorNotResolved("не указан ожидаемый текст для assertPageText");
                }
                String actual = page.locator("body").innerText();
                if (!actual.contains(expected)) {
                    throw new AssertionFailure("текст в body не содержит `" + expected + "`");
                }
                return StepEnrichment.ui(stepIndex, "assertPageText",
                        "assertPageText\n   • expected: `" + expected + "`", List.of());
            }
            default:
                throw new UnsupportedUiOperation(plan.operation());
        }
    }

    /**
     * Снимает текст или атрибут единственного элемента в именованный слот снимка.
     * <p>
     * Элемент разрешается нестрого ({@code strictAmbiguity = false}), но результат
     * обязан содержать ровно один элемент. Атрибут берётся из {@code plan.attribute()};
     * по умолчанию это {@code innerText}. Значение читает {@link #readUiValue}.
     *
     * @param session   сессия, страница которой снимается
     * @param stepIndex индекс шага, начиная с 0
     * @param plan      план шага; нужны {@code snapshotKey} и локатор
     * @return результат со слотом снимка, значением и enrichment
     * @throws LocatorNotResolved            ключ снимка не указан, либо найдено не ровно 1 элемент
     * @throws AssertionFailure              значение элемента не удалось прочитать
     */
    public SnapshotResult snapshotUiText(UiSession session, int stepIndex, StepPlanner.Plan plan) {
        String key = requireSnapshotKey(plan.snapshotKey());
        List<ElementInfo> elements = resolveAllElements(session.page(), plan, false);
        if (elements.size() != 1) {
            throw new LocatorNotResolved(
                    "snapshotUiText: ожидался ровно 1 элемент, найдено " + elements.size()
                            + " — уточните локатор");
        }
        ElementInfo el = elements.get(0);
        String attr = plan.attribute() == null || plan.attribute().isBlank()
                ? "innerText" : plan.attribute();
        String value = readUiValue(session.page(), el.locator(), attr);
        String detail = "snapshotUiText → snapshot[" + key + "] ← `" + el.locator()
                + "` [" + attr + "]\n   • length: " + value.length()
                + "\n   • preview: " + truncate(value, 240);
        log.info("UI-шаг {}: snapshotUiText key={} locator={} attr={} len={}",
                stepIndex + 1, key, el.locator(), attr, value.length());
        return new SnapshotResult(
                StepEnrichment.ui(stepIndex, "snapshotUiText", detail, elements),
                key, value);
    }

    /**
     * Сравнивает текущее значение единственного элемента со снимком того же ключа.
     * <p>
     * Наличие снимка проверяется до поиска элемента. Элемент разрешается нестрого и
     * должен быть единственным. Сравнение строгое, по строкам. При расхождении в
     * сообщении ошибки приводятся ожидаемое и фактическое значения, обрезанные
     * до 300 символов.
     *
     * @param session   сессия, страница которой проверяется
     * @param stepIndex индекс шага, начиная с 0
     * @param plan      план шага; нужны {@code snapshotKey} и локатор
     * @param snapshots снимки кейса по ключам; может быть {@code null}
     * @return enrichment шага при совпадении значений
     * @throws AssertionFailure   снимка нет, значение не удалось прочитать или значения различаются
     * @throws LocatorNotResolved ключ не указан либо найдено не ровно 1 элемент
     */
    public StepEnrichment assertUiTextEqualsSnapshot(UiSession session, int stepIndex,
                                                     StepPlanner.Plan plan,
                                                     Map<String, String> snapshots) {
        String key = requireSnapshotKey(plan.snapshotKey());
        String reference = snapshots == null ? null : snapshots.get(key);
        if (reference == null) {
            throw new AssertionFailure("assertUiTextEqualsSnapshot: нет снимка `" + key + "`");
        }
        List<ElementInfo> elements = resolveAllElements(session.page(), plan, false);
        if (elements.size() != 1) {
            throw new LocatorNotResolved(
                    "assertUiTextEqualsSnapshot: ожидался ровно 1 элемент, найдено "
                            + elements.size());
        }
        ElementInfo el = elements.get(0);
        String attr = plan.attribute() == null || plan.attribute().isBlank()
                ? "innerText" : plan.attribute();
        String actual = readUiValue(session.page(), el.locator(), attr);
        boolean equals = reference.equals(actual);

        StringBuilder detail = new StringBuilder("assertUiTextEqualsSnapshot")
                .append("\n   • snapshotKey: `").append(key).append("`")
                .append("\n   • locator: `").append(el.locator()).append("`")
                .append("\n   • attribute: ").append(attr)
                .append("\n   • expected length: ").append(reference.length())
                .append("\n   • actual length: ").append(actual.length())
                .append("\n   • ").append(equals ? "✅ equal" : "❌ differ");
        if (!equals) {
            detail.append("\n   • expected: \"")
                    .append(escapeQuotes(truncateSmart(reference, 300))).append("\"")
                    .append("\n   • actual:   \"")
                    .append(escapeQuotes(truncateSmart(actual, 300))).append("\"");
            throw new AssertionFailure("assertUiTextEqualsSnapshot: значения различаются\n"
                    + detail);
        }
        log.info("UI-шаг {}: assertUiTextEqualsSnapshot key={} ok", stepIndex + 1, key);
        return StepEnrichment.ui(stepIndex, "assertUiTextEqualsSnapshot",
                detail.toString(), elements);
    }

    /**
     * Читает значение атрибута из первого элемента по локатору.
     * <p>
     * Значения нормализуются так:
     * <ul>
     *   <li>{@code innerText} и {@code textContent}: обрезка пробелов по краям,
     *       последовательности пробельных символов сжимаются до одного пробела;</li>
     *   <li>{@code value}: свойство {@code value} элемента, {@code null} превращается в пустую строку;</li>
     *   <li>любой другой атрибут: {@code getAttribute}, отсутствующее значение даёт пустую строку.</li>
     * </ul>
     *
     * @param page    страница, на которой ищется элемент
     * @param locator локатор, из которого берётся первый элемент
     * @param attr    имя свойства или атрибута
     * @return прочитанное значение, никогда не {@code null}
     * @throws AssertionFailure ошибка чтения элемента
     */
    private String readUiValue(Page page, String locator, String attr) {
        Locator loc = page.locator(locator).first();
        try {
            return switch (attr) {
                case "innerText" -> {
                    String t = loc.innerText();
                    yield t == null ? "" : t.trim().replaceAll("\\s+", " ");
                }
                case "textContent" -> {
                    String t = loc.textContent();
                    yield t == null ? "" : t.trim().replaceAll("\\s+", " ");
                }
                case "value" -> {
                    Object v = loc.evaluate("e => e.value == null ? '' : String(e.value)");
                    yield v == null ? "" : v.toString();
                }
                default -> {
                    Object v = loc.evaluate("(e, a) => e.getAttribute(a) || ''", attr);
                    yield v == null ? "" : v.toString();
                }
            };
        } catch (Exception e) {
            throw new AssertionFailure("не удалось прочитать `" + attr + "` у `"
                    + locator + "`: " + e.getMessage());
        }
    }

    /**
     * Проверяет ключ снимка и возвращает его без пробелов по краям.
     *
     * @param k ключ из плана; может быть {@code null}
     * @return нормализованный ключ
     * @throws LocatorNotResolved ключ отсутствует или пуст
     */
    private static String requireSnapshotKey(String k) {
        if (k == null || k.isBlank()) {
            throw new LocatorNotResolved(
                    "snapshotKey обязателен для операций snapshot/compare");
        }
        return k.trim();
    }

    /**
     * Обрезает строку до {@code max} символов и добавляет «…», если она длиннее.
     *
     * @param s   исходная строка; может быть {@code null}
     * @param max максимальная длина без многоточия
     * @return обрезанная строка либо пустая строка для {@code null}
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Результат операции {@code snapshotUiText}.
     *
     * @param enrichment запись для отчёта
     * @param key        имя слота снимка, из {@code plan.snapshotKey()}
     * @param value      прочитанное значение элемента
     */
    public record SnapshotResult(StepEnrichment enrichment, String key, String value) {
    }

    /**
     * Дописывает в отчёт описание одного элемента: локатор, число совпадений,
     * тег с основными атрибутами, текст, XPath и альтернативные CSS-локаторы.
     * Пустые и {@code null}-поля пропускаются.
     *
     * @param sb отчёт, в который дописывается описание
     * @param el описываемый элемент
     */
    private void appendElementDetail(StringBuilder sb, ElementInfo el) {
        sb.append("     locator: `").append(el.locator()).append("`\n");
        if (el.count() != null) sb.append("     matches: ").append(el.count()).append("\n");
        if (el.tag() != null) {
            sb.append("     element: <").append(el.tag());
            if (el.testidOrId() != null) sb.append(" id=\"").append(el.testidOrId()).append("\"");
            if (el.role() != null) sb.append(" role=\"").append(el.role()).append("\"");
            if (el.type() != null) sb.append(" type=\"").append(el.type()).append("\"");
            sb.append(">\n");
        }
        if (el.text() != null && !el.text().isBlank()) {
            sb.append("     text: \"")
                    .append(escapeQuotes(truncateSmart(el.text(), 200)))
                    .append("\"\n");
        }
        if (el.xpath() != null && !el.xpath().isBlank()) {
            sb.append("     xpath: `").append(el.xpath()).append("`\n");
        }
        if (el.alternativesCss() != null && !el.alternativesCss().isEmpty()) {
            sb.append("     alternatives (css):\n");
            for (String a : el.alternativesCss()) sb.append("       - ").append(a).append("\n");
        }
    }

    /**
     * Разрешает все элементы плана в {@link ElementInfo} и проверяет их количество.
     * <p>
     * Для каждой цели {@code plan.effectiveElements()} считается число совпадений.
     * Локатор, который не удалось вычислить, не находит элементов или (при
     * {@code strictAmbiguity}) находит больше одного, даёт {@link LocatorNotResolved}.
     * В сообщении о неоднозначности перечисляются кандидаты с тегом, id, data-testid,
     * role, aria-label и текстом, а ключ роли берётся из {@code elementRole} цели плана
     * или равен {@code "element"}. Нестрогий режим возвращает все совпадения;
     * если их несколько, у локатора добавляется суффикс {@code >> nth=i}.
     *
     * @param page             страница, на которой вычисляются локаторы
     * @param plan             план шага с целями элементов
     * @param strictAmbiguity  {@code true} требует единственного совпадения на каждую цель
     * @return элементы всех целей в порядке плана; индекс {@link ElementInfo#index()} сквозной
     * @throws LocatorNotResolved план не содержит целей, локатор некорректен, не найден или неоднозначен
     */
    public List<ElementInfo> resolveAllElements(Page page, StepPlanner.Plan plan,
                                                boolean strictAmbiguity) {
        List<StepPlanner.ElementTarget> targets = plan.effectiveElements();
        if (targets.isEmpty()) {
            throw new LocatorNotResolved("план не содержит ни одного локатора");
        }
        List<ElementInfo> result = new ArrayList<>();
        int idx = 0;
        for (StepPlanner.ElementTarget t : targets) {
            Locator loc = page.locator(t.locator());
            int count;
            try {
                count = loc.count();
            } catch (PlaywrightException e) {
                throw new LocatorNotResolved("некорректный локатор `" + t.locator()
                        + "`: " + e.getMessage());
            }
            if (count == 0) {
                throw new LocatorNotResolved("локатор `" + t.locator() + "` не найден в DOM");
            }
            if (strictAmbiguity && count > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("локатор `").append(t.locator()).append("` матчит ")
                        .append(count).append(" элементов — уточните, какой использовать:\n");
                for (int i = 0; i < count; i++) {
                    Locator el = loc.nth(i);
                    String tag = safeString(evaluate(el, "e => e.tagName ? e.tagName.toLowerCase() : ''"));
                    String testid = safeAttr(el, "data-testid");
                    if (testid == null) testid = safeAttr(el, "data-test-id");
                    if (testid == null) testid = safeAttr(el, "data-test");
                    String id = safeAttr(el, "id");
                    String role = safeAttr(el, "role");
                    String aria = safeAttr(el, "aria-label");
                    String text = safeString(safeInnerText(el));
                    if (text != null) text = text.trim().replaceAll("[ \\t]+", " ");
                    sb.append("  #").append(i + 1).append(": <").append(tag);
                    if (id != null) sb.append(" id=\"").append(id).append("\"");
                    if (testid != null) sb.append(" data-testid=\"").append(testid).append("\"");
                    if (role != null) sb.append(" role=\"").append(role).append("\"");
                    if (aria != null) sb.append(" aria-label=\"").append(aria).append("\"");
                    sb.append(">");
                    if (text != null && !text.isBlank()) {
                        sb.append(" text=\"").append(truncateSmart(text, 120)).append("\"");
                    }
                    sb.append("\n");
                }
                String roleKey = t.elementRole() != null && !t.elementRole().isBlank()
                        ? t.elementRole() : "element";
                throw new LocatorNotResolved(sb.toString().trim(), roleKey);
            }
            for (int i = 0; i < count; i++) {
                Locator el = loc.nth(i);
                String individual = count > 1
                        ? t.locator() + " >> nth=" + i
                        : t.locator();
                result.add(inspectLocator(page, el, t, idx++, count, individual));
            }
        }
        return result;
    }

    /**
     * Собирает {@link ElementInfo} для одного совпадения локатора.
     * <p>
     * Атрибуты читаются через {@link #safeAttr}, текст через {@link #safeInnerText}.
     * Для {@code data-testid}, {@code data-test-id} и {@code data-test} приоритет
     * выше {@code id}: в {@code testidOrId} попадает первое найденное из них.
     * Альтернативы из {@code t.alternatives()} делятся на два набора. Префикс
     * {@code xpath=} снимается. Строки, начинающиеся с {@code //}, {@code /html}
     * или {@code ./}, считаются XPath и попадают в {@code alternativesXpath}
     * без дублей. Остальные строки попадают в {@code alternativesCss}, даже если
     * это не CSS (например, {@code text=} или {@code role=}). Вычисленный на странице
     * XPath ставится первым в {@code alternativesXpath}.
     *
     * @param page              страница, на которой ищется элемент
     * @param el                конкретное совпадение локатора
     * @param t                 цель плана, из которой взяты альтернативы и роль
     * @param index             сквозной индекс элемента в шаге
     * @param count             общее число совпадений локатора
     * @param individualLocator локатор, однозначно указывающий на этот элемент
     * @return описание элемента для отчёта и для планировщика
     */
    private ElementInfo inspectLocator(Page page, Locator el, StepPlanner.ElementTarget t,
                                       int index, int count, String individualLocator) {
        String tag = safeString(evaluate(el, "e => e.tagName ? e.tagName.toLowerCase() : ''"));
        String testid = safeAttr(el, "data-testid");
        if (testid == null) testid = safeAttr(el, "data-test-id");
        if (testid == null) testid = safeAttr(el, "data-test");
        String id = safeAttr(el, "id");
        String role = safeAttr(el, "role");
        String aria = safeAttr(el, "aria-label");
        String type = safeAttr(el, "type");
        String text = safeString(safeInnerText(el));
        if (text != null) text = text.trim().replaceAll("[ \\t]+", " ");

        String xpath = computeXPath(page, individualLocator);
        List<String> altsCss = new ArrayList<>();
        if (t.alternatives() != null) {
            for (String a : t.alternatives()) {
                String s = a.trim();
                if (s.startsWith("xpath=")) s = s.substring("xpath=".length()).trim();
                if (!s.startsWith("//") && !s.startsWith("/html") && !s.startsWith("./")) {
                    altsCss.add(s);
                }
            }
        }
        List<String> altsXpath = new ArrayList<>();
        if (xpath != null && !xpath.isBlank()) altsXpath.add(xpath);
        if (t.alternatives() != null) {
            for (String a : t.alternatives()) {
                String s = a.trim();
                if (s.startsWith("xpath=")) s = s.substring("xpath=".length()).trim();
                if (s.startsWith("//") || s.startsWith("/html") || s.startsWith("./")) {
                    if (!altsXpath.contains(s)) altsXpath.add(s);
                }
            }
        }
        return new ElementInfo(index, individualLocator, xpath, tag,
                testid != null ? testid : id, role, aria, type, text, count,
                altsCss, altsXpath);
    }

    /**
     * Вычисляет в браузере XPath первого элемента по локатору.
     * <p>
     * Приоритеты построения: {@code data-testid}, затем {@code id}. Для кнопок,
     * ссылок и заголовков с текстом не длиннее 60 символов без переносов строк
     * используется {@code normalize-space()}. Во всех остальных случаях строится
     * путь от ближайшего предка с {@code id} или {@code data-testid} (либо от
     * корня) с индексами одноимённых соседей. Значения в XPath вставляются через
     * JS-функцию {@code xpLit}: литерал в {@code '...'}, если нет апострофа; иначе в {@code "..."},
     * если нет двойных кавычек; иначе {@code concat(...)} из частей.
     *
     * @param page    страница, на которой вычисляется XPath
     * @param locator локатор; берётся первый элемент
     * @return XPath либо {@code null}, если элемент не найден или скрипт завершился ошибкой
     *         (ошибка логируется на уровне debug)
     */
    private String computeXPath(Page page, String locator) {
        try {
            Locator loc = page.locator(locator);
            if (loc.count() == 0) return null;
            Object result = loc.first().evaluate("""
                        (el) => {
                            const xpLit = (s) => {
                                if (!s.includes("'")) return `'${s}'`;
                                if (!s.includes('"')) return `"${s}"`;
                                return 'concat(' + s.split("'").map(p => `'${p}'`).join(`, "'", `) + ')';
                            };
                            const testid = el.getAttribute('data-testid')
                                         || el.getAttribute('data-test-id')
                                         || el.getAttribute('data-test');
                            if (testid) return `//*[@data-testid=${xpLit(testid)}]`;
                            if (el.id) return `//*[@id=${xpLit(el.id)}]`;
                            const tag = el.tagName.toLowerCase();
                            const text = (el.innerText || '').trim();
                            if (['button','a','h1','h2','h3','h4','h5','h6'].includes(tag)
                                && text && text.length <= 60 && !text.includes('\\n')) {
                                return `//${tag}[normalize-space()=${xpLit(text)}]`;
                            }
                            const parts = [];
                            let cur = el;
                            while (cur && cur.nodeType === 1) {
                                let part = cur.tagName.toLowerCase();
                                const cid = cur.id;
                                const ctestid = cur.getAttribute('data-testid');
                                if (cid) { parts.unshift(`//*[@id=${xpLit(cid)}]`); break; }
                                if (ctestid) { parts.unshift(`//*[@data-testid=${xpLit(ctestid)}]`); break; }
                                const parent = cur.parentElement;
                                if (parent) {
                                    const siblings = Array.from(parent.children).filter(c => c.tagName === cur.tagName);
                                    if (siblings.length > 1) {
                                        const idx = siblings.indexOf(cur) + 1;
                                        part += `[${idx}]`;
                                    }
                                }
                                parts.unshift(part);
                                cur = parent;
                            }
                            return '/' + parts.join('/');
                        }
                    """);
            return result == null ? null : result.toString();
        } catch (Exception e) {
            log.debug("computeXPath failed for `{}`: {}", locator, e.getMessage());
            return null;
        }
    }

    /**
     * Читает атрибут элемента, не выбрасывая исключений Playwright.
     *
     * @param loc  локатор элемента
     * @param name имя атрибута
     * @return значение атрибута или {@code null}, если атрибута нет, он пуст или чтение завершилось ошибкой
     */
    private String safeAttr(Locator loc, String name) {
        try {
            String v = loc.getAttribute(name);
            return (v == null || v.isBlank()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Возвращает {@code innerText} элемента, не выбрасывая исключений Playwright.
     *
     * @param loc локатор элемента
     * @return текст или {@code null} при ошибке
     */
    private String safeInnerText(Locator loc) {
        try {
            return loc.innerText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Выполняет JavaScript-выражение над элементом, не выбрасывая исключений.
     *
     * @param loc  локатор элемента
     * @param expr JavaScript-функция вида {@code e => ...}
     * @return результат выполнения или {@code null} при ошибке
     */
    private Object evaluate(Locator loc, String expr) {
        try {
            return loc.evaluate(expr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Приводит значение к строке.
     *
     * @param o значение; может быть {@code null}
     * @return {@code o.toString()} либо {@code null}
     */
    private String safeString(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Обрезает текст для отчёта, стараясь не разрывать строки и слова.
     * <p>
     * Если строка длиннее {@code max}, обрезка выполняется по последнему переводу строки
     * или пробелу, но только если он находится во второй половине лимита; тогда к
     * результату добавляется «…». Иначе строка обрезается ровно по {@code max} символам.
     * Результат может превышать {@code max} на служебные символы «…» или перевода строки.
     *
     * @param s   исходная строка; может быть {@code null}
     * @param max предельная длина без многоточия
     * @return обрезанная строка или пустая строка для {@code null}
     */
    private static String truncateSmart(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int nl = s.lastIndexOf('\n', max);
        if (nl > max / 2) return s.substring(0, nl) + "\n…";
        int space = s.lastIndexOf(' ', max);
        if (space > max / 2) return s.substring(0, space) + "…";
        return s.substring(0, max) + "…";
    }

    /**
     * Экранирует текст для вставки в отчёт внутри двойных кавычек.
     * Экранируются {@code "}, {@code \}, перевод строки, табуляция и возврат каретки.
     *
     * @param s исходная строка; может быть {@code null}
     * @return экранированная строка или пустая строка для {@code null}
     */
    private static String escapeQuotes(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Возвращает URL текущей страницы сессии.
     *
     * @param session сессия браузера
     * @return URL страницы или {@code null}, если его не удалось получить
     */
    public String currentUrl(UiSession session) {
        try {
            return session.page().url();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Браузерная сессия одного прогона: изолированный контекст и открытая страница.
     * <p>
     * Создаётся только через {@link UiExecutor#newSession(String)}. Метод
     * {@link #close()} закрывает контекст и глушит ошибки закрытия, поэтому
     * повторный вызов безопасен.
     */
    public static final class UiSession implements AutoCloseable {

        /**
         * Контекст браузера, которому принадлежит страница.
         */
        private final BrowserContext context;

        /**
         * Текущая страница сессии.
         */
        private final Page page;

        /**
         * Создаёт сессию из уже открытого контекста и страницы.
         *
         * @param context контекст браузера
         * @param page    страница в этом контексте
         */
        UiSession(BrowserContext context, Page page) {
            this.context = context;
            this.page = page;
        }

        /**
         * Возвращает страницу сессии.
         *
         * @return страница Playwright
         */
        public Page page() {
            return page;
        }

        /**
         * Закрывает контекст браузера. Ошибки закрытия игнорируются.
         */
        @Override
        public void close() {
            try {
                context.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Сигнал, что план не содержит достаточно данных для выполнения шага: нет
     * значения или URL, локатор не найден либо неоднозначен.
     * <p>
     * Это не провал теста, а запрос уточнения. Исполнители выбрасывают его там, где
     * проверка не может быть выполнена, а не там, где проверка не прошла
     * ({@link AssertionFailure}).
     */
    public static final class LocatorNotResolved extends RuntimeException {

        /**
         * Ключ роли элемента для формирования вопроса пользователю; может быть {@code null}.
         */
        private final String roleKey;

        /**
         * Создаёт исключение без ключа роли.
         *
         * @param message причина, которая попадёт в вопрос пользователю
         */
        public LocatorNotResolved(String message) {
            this(message, null);
        }

        /**
         * Создаёт исключение с ключом роли элемента.
         *
         * @param message причина, которая попадёт в вопрос пользователю
         * @param roleKey ключ роли элемента либо {@code null}
         */
        public LocatorNotResolved(String message, String roleKey) {
            super(message);
            this.roleKey = roleKey;
        }

        /**
         * Возвращает ключ роли элемента, с которым было создано исключение.
         *
         * @return ключ роли либо {@code null}
         */
        public String roleKey() {
            return roleKey;
        }
    }

    /**
     * Операция плана не поддерживается UI-исполнителем. Такой шаг не относится к браузеру
     * и должен маршрутизироваться в другой исполнитель.
     */
    public static final class UnsupportedUiOperation extends RuntimeException {

        /**
         * Создаёт исключение для неподдерживаемой операции.
         *
         * @param op имя операции из плана
         */
        public UnsupportedUiOperation(String op) {
            super("UIExecutor не поддерживает операцию: " + op);
        }
    }
}
