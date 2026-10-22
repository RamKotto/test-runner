package application.service;

import application.config.AppProperties;
import application.dto.Dtos.ElementInfo;
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
import java.util.Map;

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

    public StepEnrichment executeStep(UiSession session, int stepIndex,
                                      StepPlanner.Plan plan, String currentUrl) {
        Page page = session.page();
        String op = plan.operation() == null ? "" : plan.operation();
        switch (op) {
            case "navigate": {
                String url = plan.value() != null && !plan.value().isBlank()
                        ? plan.value() : currentUrl;
                if (url == null) throw new LocatorNotResolved("не указан URL для навигации");
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

    // ---- snapshot/compare UI ----

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

    private static String requireSnapshotKey(String k) {
        if (k == null || k.isBlank()) {
            throw new LocatorNotResolved(
                    "snapshotKey обязателен для операций snapshot/compare");
        }
        return k.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public record SnapshotResult(StepEnrichment enrichment, String key, String value) {
    }

    // ---- существующие helpers ----

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

    private String computeXPath(Page page, String locator) {
        try {
            Locator loc = page.locator(locator);
            if (loc.count() == 0) return null;
            Object result = loc.first().evaluate("""
                        (el) => {
                            const testid = el.getAttribute('data-testid')
                                         || el.getAttribute('data-test-id')
                                         || el.getAttribute('data-test');
                            if (testid) return `//*[@data-testid='${testid.replace(/'/g, "\\'")}']`;
                            if (el.id) return `//*[@id='${el.id.replace(/'/g, "\\'")}']`;
                            const tag = el.tagName.toLowerCase();
                            const text = (el.innerText || '').trim();
                            if (['button','a','h1','h2','h3','h4','h5','h6'].includes(tag)
                                && text && text.length <= 60 && !text.includes('\\n')) {
                                return `//${tag}[normalize-space()='${text.replace(/'/g, "\\'")}']`;
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

    private String safeAttr(Locator loc, String name) {
        try {
            String v = loc.getAttribute(name);
            return (v == null || v.isBlank()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private String safeInnerText(Locator loc) {
        try {
            return loc.innerText();
        } catch (Exception e) {
            return null;
        }
    }

    private Object evaluate(Locator loc, String expr) {
        try {
            return loc.evaluate(expr);
        } catch (Exception e) {
            return null;
        }
    }

    private String safeString(Object o) {
        return o == null ? null : o.toString();
    }

    private static String truncateSmart(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int nl = s.lastIndexOf('\n', max);
        if (nl > max / 2) return s.substring(0, nl) + "\n…";
        int space = s.lastIndexOf(' ', max);
        if (space > max / 2) return s.substring(0, space) + "…";
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

    public String currentUrl(UiSession session) {
        try {
            return session.page().url();
        } catch (Exception e) {
            return null;
        }
    }

    public void dumpHtml(UiSession session, String testCaseId, int stepIndex) {
        if (!dumpHtmlEnabled) return;
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
        private final String roleKey;

        public LocatorNotResolved(String message) {
            this(message, null);
        }

        public LocatorNotResolved(String message, String roleKey) {
            super(message);
            this.roleKey = roleKey;
        }

        public String roleKey() {
            return roleKey;
        }
    }

    public static final class UnsupportedUiOperation extends RuntimeException {
        public UnsupportedUiOperation(String op) {
            super("UIExecutor не поддерживает операцию: " + op);
        }
    }
}
