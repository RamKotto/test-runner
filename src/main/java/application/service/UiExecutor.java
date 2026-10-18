package application.service;

import application.config.AppProperties;
import application.dto.Dtos.StepEnrichment;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
                page.locator(loc).click();
                return new StepEnrichment(stepIndex, "click", "click → `" + loc + "`");
            }
            case "fill": {
                String loc = requireLocator(plan);
                String value = plan.value();
                if (value == null) throw new LocatorNotResolved("не указано значение для fill");
                assertLocatorExists(page, loc);
                page.locator(loc).fill(value);
                return new StepEnrichment(stepIndex, "fill", "fill → `" + loc + "` = `" + value + "`");
            }
            case "assertText": {
                String loc = requireLocator(plan);
                String expected = plan.value();
                if (expected == null) throw new LocatorNotResolved("не указан ожидаемый текст");
                assertLocatorExists(page, loc);
                String actual = page.locator(loc).innerText();
                if (!actual.contains(expected)) {
                    throw new LocatorNotResolved(
                            "текст в `" + loc + "` = \"" + actual + "\" не содержит \"" + expected + "\"");
                }
                return new StepEnrichment(stepIndex, "assertText",
                        "assertText → `" + loc + "` содержит `" + expected + "`");
            }
            default:
                throw new LocatorNotResolved("неподдерживаемая UI-операция: " + plan.operation());
        }
    }

    public String snapshotHtml(UiSession session) {
        try {
            return session.page().content();
        } catch (Exception e) {
            log.debug("Не удалось получить HTML: {}", e.getMessage());
            return "";
        }
    }

    public String currentUrl(UiSession session) {
        try {
            return session.page().url();
        } catch (Exception e) {
            return null;
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
