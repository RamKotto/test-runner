package application.service;

import application.config.AppProperties;
import application.dto.Dtos.StepEnrichment;
import application.exception.AssertionFailure;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class UiExecutor {

    private final Playwright playwright;
    private final Browser browser;
    private final AppProperties appProps;

    public UiExecutor(AppProperties appProps) {
        this.appProps = appProps;
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(appProps.getPlaywright().isHeadless()));
        log.info("Playwright запущен (headless={})", appProps.getPlaywright().isHeadless());
    }

    public UiSession newSession(String startUrl) {
        BrowserContext ctx = browser.newContext();
        Page page = ctx.newPage();
        page.setDefaultTimeout(appProps.getPlaywright().getActionTimeoutMs());
        page.setDefaultNavigationTimeout(appProps.getPlaywright().getNavigationTimeoutMs());
        if (startUrl != null && !startUrl.isBlank()) {
            page.navigate(startUrl);
        }
        return new UiSession(ctx, page);
    }

    /**
     * Компактный DOM-снапшот: только интерактивные и "якорные" элементы с атрибутами.
     * Отдаётся в LLM вместо innerText — чтобы модель могла выбрать реальный селектор.
     */
    public String snapshotForPlanning(UiSession session) {
        try {
            return (String) session.page().evaluate("""
                        () => {
                            const MAX = 600;
                            const out = [];
                            const seen = new Set();
                            const interesting = new Set([
                                'A','BUTTON','INPUT','SELECT','TEXTAREA','FORM','LABEL',
                                'H1','H2','H3','H4','H5','H6','TABLE','TR','TD','TH',
                                'PRE','CODE','NAV','MAIN','HEADER','FOOTER','SECTION','ARTICLE'
                            ]);
                            const walk = (el, depth) => {
                                if (!el || el.nodeType !== 1) return;
                                if (out.length >= MAX) return;
                                const tag = el.tagName;
                                const role = el.getAttribute('role');
                                const testid = el.getAttribute('data-testid')
                                             || el.getAttribute('data-test-id')
                                             || el.getAttribute('data-test');
                                const id = el.id;
                                const cls = (el.className && typeof el.className === 'string')
                                             ? el.className.trim() : '';
                                const ariaLabel = el.getAttribute('aria-label');
                                const name = el.getAttribute('name');
                                const type = el.getAttribute('type');
                                const href = tag === 'A' ? el.getAttribute('href') : null;
                                const placeholder = el.getAttribute('placeholder');
                                const isInteresting = interesting.has(tag)
                                    || testid || id || role || ariaLabel
                                    || (cls && /btn|button|link|input|card|nav|menu|modal|form|code|pre/i.test(cls));
                                let text = '';
                                if (isInteresting) {
                                    text = (el.innerText || '').trim().replace(/\\s+/g, ' ').slice(0, 120);
                                }
                                const key = tag + '|' + (id||'') + '|' + (testid||'') + '|' + text;
                                if (isInteresting && !seen.has(key)) {
                                    seen.add(key);
                                    const attrs = [];
                                    if (id) attrs.push('#' + id);
                                    if (testid) attrs.push('data-testid="' + testid + '"');
                                    if (role) attrs.push('role="' + role + '"');
                                    if (ariaLabel) attrs.push('aria-label="' + ariaLabel + '"');
                                    if (name) attrs.push('name="' + name + '"');
                                    if (type) attrs.push('type="' + type + '"');
                                    if (href) attrs.push('href="' + href + '"');
                                    if (placeholder) attrs.push('placeholder="' + placeholder + '"');
                                    if (cls) attrs.push('class="' + cls.slice(0, 100) + '"');
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

    public StepEnrichment executeStep(UiSession session,
                                      int stepIndex,
                                      StepPlanner.Plan plan,
                                      String currentUrl) {
        Page page = session.page();
        switch (plan.operation()) {
            case "navigate": {
                String url = plan.value() != null && !plan.value().isBlank()
                        ? plan.value() : currentUrl;
                if (url == null) throw new LocatorNotResolved("не указан URL для навигации");
                page.navigate(url);
                return new StepEnrichment(stepIndex, "navigate",
                        "navigate → `" + page.url() + "`");
            }
            case "click": {
                String loc = requireLocator(plan);
                assertLocatorExists(page, loc);
                LocatorInfo info = inspectLocator(page, loc);
                page.locator(loc).click();
                String detail = buildLocatorDetail("click → `" + loc + "`", info, plan, null);
                return new StepEnrichment(stepIndex, "click", detail);
            }
            case "fill": {
                String loc = requireLocator(plan);
                String value = plan.value();
                if (value == null) throw new LocatorNotResolved("не указано значение для fill");
                assertLocatorExists(page, loc);
                LocatorInfo info = inspectLocator(page, loc);
                page.locator(loc).fill(value);
                String detail = buildLocatorDetail(
                        "fill → `" + loc + "` = `" + value + "`", info, plan, null);
                return new StepEnrichment(stepIndex, "fill", detail);
            }
            case "assertText": {
                String loc = requireLocator(plan);
                String expected = plan.value();
                if (expected == null) throw new LocatorNotResolved("не указан ожидаемый текст");
                assertLocatorExists(page, loc);
                LocatorInfo info = inspectLocator(page, loc);
                String actual = page.locator(loc).innerText();
                if (!actual.contains(expected)) {
                    // Текст на странице есть, но другой — это FAILED, не уточнение.
                    throw new AssertionFailure(
                            "текст в `" + loc + "` = \"" + truncate(actual, 200)
                                    + "\" не содержит \"" + expected + "\"");
                }
                String snippet = actual.length() > 160 ? actual.substring(0, 160) + "…" : actual;
                String detail = buildLocatorDetail(
                        "assertText → `" + loc + "` содержит `" + expected + "`",
                        info, plan, snippet);
                return new StepEnrichment(stepIndex, "assertText", detail);
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
                return new StepEnrichment(stepIndex, "waitForText",
                        "waitForText → `body` содержит `" + expected + "`");
            }
            case "assertBody": {
                String expected = plan.value();
                if (expected == null || expected.isBlank()) {
                    throw new LocatorNotResolved("не указан ожидаемый текст для assertBody");
                }
                String actual = page.locator("body").innerText();
                if (!actual.contains(expected)) {
                    throw new AssertionFailure("текст в body не содержит `" + expected + "`");
                }
                return new StepEnrichment(stepIndex, "assertBody",
                        "assertBody → `body` содержит `" + expected + "`");
            }
            default:
                throw new LocatorNotResolved("неподдерживаемая UI-операция: " + plan.operation());
        }
    }

    /**
     * Проверка локатора и подтягивание реальных атрибутов.
     */
    private LocatorInfo inspectLocator(Page page, String locator) {
        try {
            Locator loc = page.locator(locator);
            int count = loc.count();
            if (count == 0) {
                return new LocatorInfo(locator, 0, null, null, null, null, null, null);
            }
            Locator first = loc.first();
            String tag = safeString(first.evaluate("e => e.tagName ? e.tagName.toLowerCase() : ''"));
            String testid = safeAttr(first, "data-testid");
            if (testid == null) testid = safeAttr(first, "data-test-id");
            if (testid == null) testid = safeAttr(first, "data-test");
            String id = safeAttr(first, "id");
            String role = safeAttr(first, "role");
            String aria = safeAttr(first, "aria-label");
            String type = safeAttr(first, "type");
            String text = safeString(first.innerText());
            if (text != null) {
                text = text.trim().replaceAll("\\s+", " ");
                if (text.length() > 80) text = text.substring(0, 80) + "…";
            }
            return new LocatorInfo(locator, count, tag, testid != null ? testid : id,
                    role, aria, text, type);
        } catch (Exception e) {
            log.debug("inspectLocator failed for `{}`: {}", locator, e.getMessage());
            return new LocatorInfo(locator, -1, null, null, null, null, null, null);
        }
    }

    private String buildLocatorDetail(String header, LocatorInfo info, StepPlanner.Plan plan,
                                      String snippet) {
        StringBuilder sb = new StringBuilder(header);
        sb.append("\n   • matches: ").append(info.count() >= 0 ? info.count() : "unknown");
        if (info.tag() != null) {
            sb.append("\n   • element: <").append(info.tag());
            if (info.testidOrId() != null) {
                boolean isTestId = plan.locatorStrategy() != null
                        && plan.locatorStrategy().toLowerCase().contains("testid");
                if (isTestId) {
                    sb.append(" data-testid=\"").append(info.testidOrId()).append("\"");
                } else {
                    sb.append(" id=\"").append(info.testidOrId()).append("\"");
                }
            }
            if (info.role() != null) sb.append(" role=\"").append(info.role()).append("\"");
            if (info.ariaLabel() != null) sb.append(" aria-label=\"").append(info.ariaLabel()).append("\"");
            if (info.type() != null) sb.append(" type=\"").append(info.type()).append("\"");
            sb.append(">");
        }
        if (info.text() != null && !info.text().isBlank()) {
            sb.append("\n   • text: \"").append(info.text()).append("\"");
        }
        if (snippet != null && !snippet.isBlank()) {
            sb.append("\n   • snippet: \"").append(snippet.replace("\n", "\\n")).append("\"");
        }
        if (plan.locatorStrategy() != null && !plan.locatorStrategy().isBlank()) {
            sb.append("\n   • strategy: ").append(plan.locatorStrategy());
        }

        // elementRole: из плана, иначе выводим из реального тега/type
        String derivedRole = plan.elementRole();
        if (derivedRole == null || derivedRole.isBlank()) {
            derivedRole = deriveRole(info);
        }
        if (derivedRole != null && !derivedRole.isBlank()) {
            sb.append("\n   • role: ").append(derivedRole);
        }

        // elementName: из плана, иначе берём видимый текст
        String derivedName = plan.elementName();
        if (derivedName == null || derivedName.isBlank()) {
            derivedName = info.text();
        }
        if (derivedName != null && !derivedName.isBlank()) {
            sb.append("\n   • name: \"").append(derivedName).append("\"");
        }

        if (plan.locatorAlternatives() != null && !plan.locatorAlternatives().isBlank()
                && !"null".equalsIgnoreCase(plan.locatorAlternatives())) {
            sb.append("\n   • alternatives: ").append(plan.locatorAlternatives());
        }
        if (info.count() > 1) {
            sb.append("\n   • ⚠️ WARNING: локатор матчит ")
                    .append(info.count())
                    .append(" элементов — добавьте уточнение в локатор или обратитесь к разработчику");
        }
        return sb.toString();
    }

    /**
     * Fallback для elementRole, если LLM не вернула поле.
     */
    private String deriveRole(LocatorInfo info) {
        if (info.tag() == null) return null;
        return switch (info.tag()) {
            case "button" -> "button";
            case "a" -> "link";
            case "input" -> {
                String t = info.type() == null ? "" : info.type().toLowerCase();
                yield switch (t) {
                    case "submit", "button", "reset" -> "button";
                    case "checkbox" -> "checkbox";
                    case "radio" -> "radio";
                    case "file" -> "file-upload";
                    default -> "textbox";
                };
            }
            case "textarea" -> "textbox";
            case "select" -> "combobox";
            case "h1", "h2", "h3", "h4", "h5", "h6" -> "heading";
            case "pre", "code" -> "code";
            case "img" -> "img";
            case "nav" -> "navigation";
            case "main" -> "main";
            case "header" -> "banner";
            case "footer" -> "contentinfo";
            case "section", "article" -> "region";
            case "table" -> "table";
            case "tr" -> "row";
            case "td", "th" -> "cell";
            default -> null;
        };
    }

    private String safeAttr(Locator loc, String name) {
        try {
            String v = loc.getAttribute(name);
            return (v == null || v.isBlank()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private String safeString(Object o) {
        return o == null ? null : o.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public String currentUrl(UiSession session) {
        try {
            return session.page().url();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Сохраняет HTML-снимок страницы рядом со скачанными кейсами.
     */
    public void dumpHtml(UiSession session, String testCaseId, int stepIndex) {
        try {
            Path dir = appProps.getDownload().getDirectory();
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve(testCaseId + ".step" + (stepIndex + 1) + ".html"),
                    session.page().content());
        } catch (Exception e) {
            log.debug("dumpHtml failed: {}", e.getMessage());
        }
    }

    private String requireLocator(StepPlanner.Plan plan) {
        if (plan.locator() == null || plan.locator().isBlank()) {
            throw new LocatorNotResolved("LLM не вернула локатор");
        }
        return plan.locator();
    }

    private void assertLocatorExists(Page page, String locator) {
        try {
            int count = page.locator(locator).count();
            if (count == 0) {
                throw new LocatorNotResolved("локатор `" + locator + "` не найден в DOM");
            }
        } catch (PlaywrightException e) {
            throw new LocatorNotResolved("некорректный локатор `" + locator + "`: " + e.getMessage());
        }
    }

    private record LocatorInfo(String locator, int count, String tag, String testidOrId,
                               String role, String ariaLabel, String text, String type) {
    }

    public static final class UiSession implements AutoCloseable {
        private final BrowserContext context;
        private final Page page;

        UiSession(BrowserContext context, Page page) {
            this.context = context;
            this.page = page;
        }

        public Page page() {
            return page;
        }

        @Override
        public void close() {
            try {
                context.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static final class LocatorNotResolved extends RuntimeException {
        public LocatorNotResolved(String message) {
            super(message);
        }
    }
}
