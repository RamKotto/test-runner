package application.service;

import application.config.AppProperties;
import application.dto.Dtos.StepEnrichment;
import application.exception.AssertionFailure;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class UiExecutor {

    private final Playwright playwright;
    private final Browser browser;
    private final AppProperties appProps;

    @Value("${app.debug.dump-html:false}")
    private boolean dumpHtmlEnabled;

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
                        "navigate\n   • url: `" + page.url() + "`");
            }
            case "click": {
                String loc = requireLocator(plan);
                assertLocatorExists(page, loc);
                LocatorInfo info = inspectLocator(page, loc);
                page.locator(loc).click();
                return new StepEnrichment(stepIndex, "click",
                        buildLocatorDetail(page, "click", loc, null, info, plan, null));
            }
            case "fill": {
                String loc = requireLocator(plan);
                String value = plan.value();
                if (value == null) throw new LocatorNotResolved("не указано значение для fill");
                assertLocatorExists(page, loc);
                LocatorInfo info = inspectLocator(page, loc);
                page.locator(loc).fill(value);
                return new StepEnrichment(stepIndex, "fill",
                        buildLocatorDetail(page, "fill", loc, value, info, plan, null));
            }
            case "assertText": {
                String loc = requireLocator(plan);
                String expected = plan.value();
                if (expected == null) throw new LocatorNotResolved("не указан ожидаемый текст");
                assertLocatorExists(page, loc);
                LocatorInfo info = inspectLocator(page, loc);
                String actual = page.locator(loc).innerText();
                if (!actual.contains(expected)) {
                    throw new AssertionFailure(
                            "текст в `" + loc + "` = \"" + truncate(actual, 200)
                                    + "\" не содержит \"" + expected + "\"");
                }
                return new StepEnrichment(stepIndex, "assertText",
                        buildLocatorDetail(page, "assertText", loc, expected, info, plan, actual));
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
                        "waitForText\n   • expected: `" + expected + "`");
            }
            // PATCH: переименовано assertBody → assertPageText (устранена коллизия с API).
            case "assertPageText": {
                String expected = plan.value();
                if (expected == null || expected.isBlank()) {
                    throw new LocatorNotResolved("не указан ожидаемый текст для assertPageText");
                }
                String actual = page.locator("body").innerText();
                if (!actual.contains(expected)) {
                    throw new AssertionFailure("текст в body не содержит `" + expected + "`");
                }
                return new StepEnrichment(stepIndex, "assertPageText",
                        "assertPageText\n   • expected: `" + expected + "`");
            }
            default:
                // PATCH: семантически правильное исключение — это дефект плана, не «локатор».
                throw new UnsupportedUiOperation(plan.operation());
        }
    }

    private String computeXPath(Page page, String locator) {
        try {
            Locator loc = page.locator(locator);
            if (loc.count() == 0) return null;
            Object result = loc.first().evaluate("""
                        (el) => {
                            const testid = el.getAttribute('data-testid')
                                         || el.getAttribute('data-test-id')
                                         || el.getAttribute('data-test');
                            if (testid) {
                                return `//*[@data-testid='${testid.replace(/'/g, "\\'")}']`;
                            }
                            if (el.id) {
                                return `//*[@id='${el.id.replace(/'/g, "\\'")}']`;
                            }
                            const tag = el.tagName.toLowerCase();
                            const text = (el.innerText || '').trim();
                            if (['button', 'a', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6'].includes(tag)
                                && text && text.length <= 60 && !text.includes('\\n')) {
                                return `//${tag}[normalize-space()='${text.replace(/'/g, "\\'")}']`;
                            }
                            if (['button', 'a', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6'].includes(tag)
                                && text && text.length > 60) {
                                const short = text.slice(0, 40).replace(/'/g, "\\'");
                                return `//${tag}[contains(normalize-space(), '${short}')]`;
                            }
                            const parts = [];
                            let cur = el;
                            while (cur && cur.nodeType === 1) {
                                let part = cur.tagName.toLowerCase();
                                const cid = cur.id;
                                const ctestid = cur.getAttribute('data-testid');
                                if (cid) { parts.unshift(`//*[@id='${cid.replace(/'/g, "\\'")}']`); break; }
                                if (ctestid) { parts.unshift(`//*[@data-testid='${ctestid.replace(/'/g, "\\'")}']`); break; }
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

    private List<String> buildXPathAlternatives(Page page, String locator, StepPlanner.Plan plan) {
        List<String> result = new ArrayList<>();
        String computed = computeXPath(page, locator);
        if (computed != null && !computed.isBlank()) {
            result.add(computed);
        }
        String alts = plan.locatorAlternatives();
        if (alts != null && !alts.isBlank() && !"null".equalsIgnoreCase(alts)) {
            for (String alt : splitAlternatives(alts)) {
                String a = alt.trim();
                if (a.startsWith("xpath=")) a = a.substring("xpath=".length()).trim();
                if (a.startsWith("//") || a.startsWith("/html") || a.startsWith("./")) {
                    if (!result.contains(a)) result.add(a);
                }
            }
        }
        return result;
    }

    private LocatorInfo inspectLocator(Page page, String locator) {
        try {
            Locator loc = page.locator(locator);
            int count = loc.count();
            if (count == 0) {
                return new LocatorInfo(locator, 0, null, null, null, null, null, null, null);
            }
            Locator first = loc.first();
            String tag = safeString(first.evaluate("e => e.tagName ? e.tagName.toLowerCase() : ''"));
            String testid = safeAttr(first, "data-testid");
            if (testid == null) testid = safeAttr(first, "data-test-id");
            if (testid == null) testid = safeAttr(first, "data-test");
            String id = safeAttr(first, "id");
            String cls = safeAttr(first, "class");
            String role = safeAttr(first, "role");
            String aria = safeAttr(first, "aria-label");
            String type = safeAttr(first, "type");
            String text = safeString(first.innerText());
            if (text != null) {
                text = text.trim().replaceAll("[ \\t]+", " ");
            }
            return new LocatorInfo(locator, count, tag, testid != null ? testid : id,
                    cls, role, aria, type, text);
        } catch (Exception e) {
            log.debug("inspectLocator failed for `{}`: {}", locator, e.getMessage());
            return new LocatorInfo(locator, -1, null, null, null, null, null, null, null);
        }
    }

    private String buildLocatorDetail(Page page, String operation, String locator, String expected,
                                      LocatorInfo info, StepPlanner.Plan plan, String actualText) {
        StringBuilder sb = new StringBuilder(operation);

        sb.append("\n   • locator: `").append(locator).append("`");
        if (expected != null) {
            sb.append("\n   • expected: `").append(expected).append("`");
        }

        sb.append("\n   • matches: ").append(info.count() >= 0 ? info.count() : "unknown");

        if (info.tag() != null) {
            sb.append("\n   • element: <").append(info.tag());
            if (info.testidOrId() != null) {
                boolean isTestId = plan.locatorStrategy() != null
                        && plan.locatorStrategy().toLowerCase().contains("testid");
                if (isTestId) {
                    sb.append(" data-testid=\"").append(info.testidOrId()).append("\"");
                } else if (info.testidOrId().matches("[a-zA-Z_][a-zA-Z0-9_\\-]*")) {
                    sb.append(" id=\"").append(info.testidOrId()).append("\"");
                }
            }
            if (info.cls() != null && !info.cls().isBlank()) {
                sb.append(" class=\"").append(truncateSmart(info.cls(), 100)).append("\"");
            }
            if (info.role() != null) sb.append(" role=\"").append(info.role()).append("\"");
            if (info.type() != null) sb.append(" type=\"").append(info.type()).append("\"");
            sb.append(">");
        }

        if (info.text() != null && !info.text().isBlank()) {
            sb.append("\n   • text: \"")
                    .append(escapeQuotes(truncateSmart(info.text(), 200)))
                    .append("\"");
        }

        if (plan.locatorStrategy() != null && !plan.locatorStrategy().isBlank()) {
            sb.append("\n   • strategy: ").append(plan.locatorStrategy());
        }

        String derivedRole = deriveRole(info);
        if (derivedRole == null || derivedRole.isBlank()) {
            derivedRole = plan.elementRole();
        }
        if (derivedRole != null && !derivedRole.isBlank()) {
            sb.append("\n   • role: ").append(derivedRole);
        }

        String nameFromText = info.text();
        String nameFromPlan = plan.elementName();
        String chosenName = null;
        if (nameFromText != null && !nameFromText.isBlank()) {
            chosenName = nameFromText;
        } else if (nameFromPlan != null && !nameFromPlan.isBlank()) {
            chosenName = nameFromPlan;
        }
        boolean nameDuplicatesText = chosenName != null && info.text() != null
                && (chosenName.equals(info.text())
                || info.text().startsWith(chosenName)
                || (chosenName.length() >= 30 && info.text().startsWith(chosenName.substring(0, 30))));
        if (chosenName != null && !chosenName.isBlank() && !nameDuplicatesText) {
            sb.append("\n   • name: \"")
                    .append(escapeQuotes(truncateSmart(chosenName, 80)))
                    .append("\"");
        }

        String alts = plan.locatorAlternatives();
        if (alts != null && !alts.isBlank() && !"null".equalsIgnoreCase(alts)) {
            List<String> cssAlts = new ArrayList<>();
            for (String alt : splitAlternatives(alts)) {
                String a = alt.trim();
                if (!a.startsWith("//") && !a.startsWith("xpath=") && !a.startsWith("/html")
                        && !a.startsWith("./")) {
                    cssAlts.add(a);
                }
            }
            if (!cssAlts.isEmpty()) {
                sb.append("\n   • alternatives (css):");
                for (String alt : cssAlts) sb.append("\n     - ").append(alt);
            }
        }

        List<String> xpathAlts = buildXPathAlternatives(page, locator, plan);
        if (!xpathAlts.isEmpty()) {
            sb.append("\n   • xpath:");
            for (String xp : xpathAlts) sb.append("\n     - `").append(xp).append("`");
        }

        if (info.count() > 1) {
            sb.append("\n   • ⚠️ WARNING: локатор матчит ")
                    .append(info.count())
                    .append(" элементов — уточните селектор или обратитесь к разработчику");
        }

        if (actualText != null && info.text() != null && !actualText.equals(info.text())) {
            String snippet = truncateSmart(actualText, 200);
            if (!snippet.equals(truncateSmart(info.text(), 200))) {
                sb.append("\n   • snippet: \"").append(escapeQuotes(snippet)).append("\"");
            }
        }

        return sb.toString();
    }

    private static List<String> splitAlternatives(String alts) {
        String s = alts.trim();
        if (s.startsWith("[")) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var arr = mapper.readTree(s);
                if (arr.isArray()) {
                    List<String> out = new ArrayList<>();
                    for (var n : arr) out.add(n.asText());
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
            } else if (c == '(') {
                depth++;
                cur.append(c);
            } else if (c == ')') {
                depth--;
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                String t = cur.toString().trim();
                if (!t.isEmpty()) out.add(stripQuotes(t));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String t = cur.toString().trim();
        if (!t.isEmpty()) out.add(stripQuotes(t));
        return out;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String truncateSmart(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;

        int nl = s.lastIndexOf('\n', max);
        if (nl > max / 2) {
            return s.substring(0, nl) + "\n…";
        }
        int space = s.lastIndexOf(' ', max);
        if (space > max / 2) {
            return s.substring(0, space) + "…";
        }
        int urlBoundary = Math.max(
                Math.max(s.lastIndexOf('/', max), s.lastIndexOf('?', max)),
                Math.max(s.lastIndexOf('&', max), s.lastIndexOf('#', max))
        );
        if (urlBoundary > max / 2) {
            return s.substring(0, urlBoundary) + "…";
        }
        return s.substring(0, max) + "…";
    }

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

    public void dumpHtml(UiSession session, String testCaseId, int stepIndex) {
        if (!dumpHtmlEnabled) {
            return;
        }
        try {
            Path dir = appProps.getDownload().getDirectory();
            Files.createDirectories(dir);
            Path target = dir.resolve(testCaseId + ".step" + (stepIndex + 1) + ".html");
            Files.writeString(target, session.page().content());
            log.debug("dumpHtml: сохранён {}", target.toAbsolutePath());
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
                               String cls, String role, String ariaLabel, String type, String text) {
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

    // PATCH: отдельное исключение для неподдерживаемой операции — не путать с «локатор не найден».
    public static final class UnsupportedUiOperation extends RuntimeException {
        public UnsupportedUiOperation(String op) {
            super("UIExecutor не поддерживает операцию: " + op);
        }
    }
}
