package application.service;

import application.config.AppProperties;
import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.ParsedTestCase;
import application.dto.Dtos.StepEnrichment;
import application.exception.GenerationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Построитель планов шагов: формирует промпт, запрашивает план у LLM и разбирает ответ.
 * <p>
 * Место в конвейере: {@link HybridCaseRunner} вызывает {@link #planStep} для каждого шага.
 * Модель получает контекст кейса, предыдущие результаты, снимки, DOM страницы и последний
 * ответ API, а возвращает один план или массив планов в JSON. Каждый план проверяется
 * на соответствие типу кейса ({@link Plan#validateAgainst}). Ответ, который нельзя разобрать
 * или который нарушает правила, превращается в {@link GenerationException}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StepPlanner {

    /**
     * Операции, которые выполняет {@link UiExecutor}, в нижнем регистре.
     */
    public static final Set<String> UI_OPS = Set.of(
            "navigate", "click", "fill", "asserttext", "waitfortext", "assertpagetext",
            "snapshotuitext", "assertuitextequalssnapshot");

    /**
     * Операции, которые выполняет {@link ApiExecutor}, в нижнем регистре.
     */
    public static final Set<String> API_OPS = Set.of(
            "apicall", "assertbody", "assertheader", "assertstatus", "parsejson",
            "assertbodyabsent", "assertheaderabsent",
            "snapshotapivalue", "assertapivalueequalssnapshot");

    /**
     * Клиент LLM, через который отправляется промпт.
     */
    private final OpenRouterClient openRouter;

    /**
     * Настройки приложения; из них берётся системный промпт планировщика.
     */
    private final AppProperties appProps;

    /**
     * Маппер для разбора JSON-ответа модели.
     */
    private final ObjectMapper mapper;

    /**
     * Цель действия над элементом страницы: локатор и данные для отчёта.
     *
     * @param locator         CSS- или Playwright-локатор
     * @param locatorStrategy стратегия, которой получен локатор
     * @param elementRole     роль элемента (кнопка, поле и т. п.)
     * @param elementName     видимое имя элемента
     * @param alternatives    запасные локаторы
     */
    public record ElementTarget(
            String locator,
            String locatorStrategy,
            String elementRole,
            String elementName,
            List<String> alternatives
    ) {
    }

    /**
     * План одного действия, предложенный моделью.
     * <p>
     * Поля соответствуют схеме ответа модели. Не все поля нужны каждой операции: например,
     * {@code path} и {@code method} нужны только {@code apiCall}, {@code snapshotKey}
     * только операциям снимков. Специальные значения {@code operation}: {@code skip} —
     * пропуск шага, {@code needClarification} — запрос уточнения у пользователя.
     *
     * @param operation          имя операции
     * @param locator            локатор одного элемента (альтернатива {@code elements})
     * @param value              значение для fill, ожидаемый текст или статус
     * @param method             HTTP-метод для apiCall
     * @param path               путь или абсолютный URL для apiCall
     * @param bodyJson           тело запроса в виде JSON-строки
     * @param expectedStatus     ожидаемый HTTP-статус apiCall
     * @param topicKey           тема вопроса для уточнения
     * @param question           текст вопроса пользователю
     * @param reason             причина пропуска или уточнения
     * @param locatorStrategy    стратегия локатора
     * @param elementRole        роль элемента
     * @param elementName        имя элемента
     * @param locatorAlternatives запасные локаторы в виде строки (JSON-массив или через запятую)
     * @param elements           цели элементов для операций с несколькими элементами
     * @param requestHeaders     заголовки запроса, по одному на строку в формате {@code Имя: значение}
     * @param snapshotKey        ключ снимка
     * @param jsonPath           путь внутри JSON-ответа
     * @param attribute          атрибут элемента для снимка и сравнения UI
     */
    public record Plan(
            String operation, String locator, String value, String method, String path,
            String bodyJson, Integer expectedStatus, String topicKey, String question, String reason,
            String locatorStrategy, String elementRole, String elementName, String locatorAlternatives,
            List<ElementTarget> elements,
            String requestHeaders,
            String snapshotKey,
            String jsonPath,
            String attribute
    ) {

        /**
         * Истина, если модель запросила уточнение у пользователя.
         *
         * @return {@code true} для операции {@code needClarification} без учёта регистра
         */
        public boolean needsClarification() {
            return "needClarification".equalsIgnoreCase(operation);
        }

        /**
         * Истина, если шаг нужно пропустить.
         *
         * @return {@code true} для операции {@code skip} без учёта регистра
         */
        public boolean isSkip() {
            return "skip".equalsIgnoreCase(operation);
        }

        /**
         * Истина, если операция относится к UI-исполнителю.
         *
         * @return {@code true}, если операция входит в {@link StepPlanner#UI_OPS}
         */
        public boolean isUiOperation() {
            String op = operation == null ? "" : operation.toLowerCase();
            return UI_OPS.contains(op);
        }

        /**
         * Истина, если операция относится к API-исполнителю.
         *
         * @return {@code true}, если операция входит в {@link StepPlanner#API_OPS}
         */
        public boolean isApiOperation() {
            String op = operation == null ? "" : operation.toLowerCase();
            return API_OPS.contains(op);
        }

        /**
         * Проверяет, что операция допустима для типа кейса.
         * <p>
         * Для {@code UI} допустимы только {@link StepPlanner#UI_OPS}, для {@code API} только
         * {@link StepPlanner#API_OPS}. Для {@code E2E}, {@code NEGATIVE} и {@code SECURITY}
         * допустима любая из двух групп. Планы {@code skip} и {@code needClarification}
         * проверки не проходят. Неизвестный тип только логируется, проверка пропускается.
         *
         * @param caseType тип кейса; может быть {@code null}
         * @return этот же план
         * @throws GenerationException операция не входит в допустимый набор для типа
         */
        public Plan validateAgainst(String caseType) {
            if (isSkip() || needsClarification()) return this;
            String op = operation == null ? "" : operation.toLowerCase();
            String t = caseType == null ? "" : caseType.trim().toUpperCase();

            switch (t) {
                case "UI" -> {
                    if (!UI_OPS.contains(op)) {
                        throw new GenerationException(
                                "План не соответствует типу кейса (UI): operation=" + op
                                        + ". Разрешены: " + UI_OPS);
                    }
                }
                case "API" -> {
                    if (!API_OPS.contains(op)) {
                        throw new GenerationException(
                                "План не соответствует типу кейса (API): operation=" + op
                                        + ". Разрешены: " + API_OPS);
                    }
                }
                case "E2E", "NEGATIVE", "SECURITY" -> {
                    if (!UI_OPS.contains(op) && !API_OPS.contains(op)) {
                        throw new GenerationException(
                                "План не соответствует типу кейса (" + t + "): operation=" + op
                                        + ". Разрешены: " + UI_OPS + " ∪ " + API_OPS);
                    }
                }
                default -> log.warn("validateAgainst: неизвестный тип кейса '{}', пропускаю", t);
            }
            return this;
        }

        /**
         * Возвращает цели элементов плана.
         * <p>
         * Если задан непустой список {@code elements}, возвращается он. Иначе, если задан
         * {@code locator}, создаётся одна цель с альтернативами из {@code locatorAlternatives}.
         * В остальных случаях список пуст.
         *
         * @return неизменяемый или непустой список целей; никогда не {@code null}
         */
        public List<ElementTarget> effectiveElements() {
            if (elements != null && !elements.isEmpty()) return elements;
            if (locator != null && !locator.isBlank()) {
                return List.of(new ElementTarget(
                        locator, locatorStrategy, elementRole, elementName,
                        splitAlternativesInternal(locatorAlternatives)));
            }
            return List.of();
        }
    }

    /**
     * Разбирает список альтернативных локаторов из строки.
     * <p>
     * Строка, начинающаяся с {@code [}, разбирается как JSON-массив, и каждый элемент
     * приводится к тексту. Если разбор не удался или это не массив, строка делится по
     * запятым. Пустые части отбрасываются, остальные обрезаются. Запятые внутри локатора
     * (например, в CSS-псевдоклассе) разбивают его на части. Значения {@code null} и
     * {@code "null"} дают пустой список.
     *
     * @param alts строка альтернатив; может быть {@code null}
     * @return изменяемый список альтернатив, возможно пустой
     */
    private static List<String> splitAlternativesInternal(String alts) {
        if (alts == null || alts.isBlank() || "null".equalsIgnoreCase(alts)) return List.of();
        String s = alts.trim();
        if (s.startsWith("[")) {
            try {
                var m = new com.fasterxml.jackson.databind.ObjectMapper();
                var arr = m.readTree(s);
                if (arr.isArray()) {
                    List<String> out = new ArrayList<>();
                    for (var n : arr) out.add(n.asText());
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    /**
     * Строит промпт для текущего шага, запрашивает план у модели и возвращает его.
     * <p>
     * Промпт включает тип кейса, все шаги с отметкой текущего, последующие шаги (чтобы
     * модель могла сразу снять снимок для будущего сравнения), ответы пользователя,
     * выполненные шаги, снимки, DOM-снимок страницы, базовый URL API, статус, заголовки и
     * тело последнего ответа. Если базовый URL API не задан, модель получает инструкцию
     * вернуть {@code needClarification} для {@code api_base_url}.
     * <p>
     * Ограничения по размеру: DOM обрезается до 40000 символов, тело последнего ответа
     * до 12000, описание каждого выполненного шага до 500, превью снимка до 200. Лимит
     * {@code app.planner.max-input-chars} в этом методе не проверяется.
     * <p>
     * Ответ модели очищается от обёртки {@code ```json} и разбирается через
     * {@link #parsePlans}.
     *
     * @param tc                    тест-кейс
     * @param stepIndex             индекс текущего шага, с 0
     * @param stepText              текст текущего шага
     * @param domSnapshot           текстовый снимок DOM страницы; может быть {@code null}
     * @param apiBaseUrl            базовый URL API; может быть {@code null}
     * @param previousEnrichments   записи выполненных шагов кейса; может быть {@code null}
     * @param lastResponseBody      тело последнего ответа API; может быть {@code null}
     * @param lastResponseHeaders   заголовки последнего ответа API; может быть {@code null}
     * @param lastStatusCode        статус последнего ответа API; может быть {@code null}
     * @param snapshots             снимки значений по ключам; может быть {@code null}
     * @return список планов (один или несколько) для текущего шага
     * @throws GenerationException ошибка LLM, невалидный JSON, пустой массив или план,
     *                             не соответствующий типу кейса
     */
    public List<Plan> planStep(ParsedTestCase tc, int stepIndex, String stepText, String domSnapshot,
                               String apiBaseUrl, List<StepEnrichment> previousEnrichments,
                               String lastResponseBody, Map<String, String> lastResponseHeaders,
                               Integer lastStatusCode, Map<String, String> snapshots) {
        StringBuilder user = new StringBuilder();
        user.append("Тип кейса: ").append(tc.type()).append("\n");
        user.append("Тест-кейс: ").append(tc.id()).append(" — ").append(tc.title()).append("\n");
        user.append("Ожидаемый результат всего кейса: ").append(tc.expectedResult()).append("\n\n");

        if (tc.steps() != null && !tc.steps().isEmpty()) {
            user.append("Все шаги кейса (контекст для планирования снимков):\n");
            for (int j = 0; j < tc.steps().size(); j++) {
                String marker = (j == stepIndex) ? "  ← ТЕКУЩИЙ" : "";
                user.append("  ").append(j + 1).append(". ")
                        .append(tc.steps().get(j)).append(marker).append("\n");
            }
            user.append("\n");
        }

        user.append("Шаг #").append(stepIndex + 1).append(": ").append(stepText).append("\n\n");

        if (stepIndex + 1 < tc.steps().size()) {
            user.append("Последующие шаги (для ПРАВИЛА 8.1: если в них упоминается сравнение — "
                    + "сними snapshot СРАЗУ на текущем шаге):\n");
            for (int j = stepIndex + 1; j < tc.steps().size(); j++) {
                user.append("- Шаг #").append(j + 1).append(": ")
                        .append(tc.steps().get(j)).append("\n");
            }
            user.append("\n");
        }

        if (tc.answers() != null && !tc.answers().isEmpty()) {
            user.append("Ранее пользователь ответил на уточнения в этом кейсе:\n");
            for (AnswerRequest.Answer a : tc.answers()) {
                user.append("- Вопрос: ").append(a.question())
                        .append("\n  Ответ: ").append(a.answer()).append("\n");
            }
            user.append("\nИспользуй эти ответы. Если ответ равен SKIP — верни operation=\"skip\".\n\n");
        }

        if (previousEnrichments != null && !previousEnrichments.isEmpty()) {
            user.append("Уже выполненные шаги этого кейса:\n");
            for (StepEnrichment e : previousEnrichments) {
                user.append("- Шаг ").append(e.stepIndex() + 1)
                        .append(": ").append(e.action()).append(" → ")
                        .append(truncate(e.detail(), 500)).append("\n");
            }
            user.append("\nДля проверки тела используй assertBody, отсутствия подстроки — ")
                    .append("assertBodyAbsent, заголовка — assertHeader, отсутствия заголовка — ")
                    .append("assertHeaderAbsent, валидности JSON — parseJson, статуса — assertStatus.\n\n");
        }

        if (snapshots != null && !snapshots.isEmpty()) {
            user.append("Уже сделанные снимки значений (snapshotKey → preview):\n");
            for (Map.Entry<String, String> e : snapshots.entrySet()) {
                user.append("- ").append(e.getKey()).append(": ")
                        .append(truncate(e.getValue(), 200)).append("\n");
            }
            user.append("\nДля сравнения значений между шагами используй ")
                    .append("assertApiValueEqualsSnapshot / assertUiTextEqualsSnapshot ")
                    .append("с тем же snapshotKey.\n\n");
        }

        if (domSnapshot != null && !domSnapshot.isBlank()) {
            user.append("DOM-СНАПШОТ страницы (только интерактивные элементы с атрибутами):\n");
            user.append(domSnapshot.length() > 40000 ? domSnapshot.substring(0, 40000) : domSnapshot)
                    .append("\n");
        }

        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            user.append("\nAPI base URL НЕ ЗАДАН. ");
            user.append("Если шаг требует HTTP-запроса, верни needClarification ")
                    .append("с topicKey=\"api_base_url\" и question=\"Укажите базовый URL API ")
                    .append("(например, https://api.open-meteo.com)\", ")
                    .append("reason=\"apiBaseUrl не передан в RunRequest\". ")
                    .append("НЕ возвращай относительный path без base — это приведёт к ошибке.\n");
        } else {
            user.append("\nAPI base URL: ").append(apiBaseUrl);
            user.append("\nПравила склейки path с base URL:");
            user.append("\n  • Если path относительный (начинается с /), он будет склеен с этим base URL.");
            user.append("\n  • Если шаг требует ДРУГОЙ хост (например, api.open-meteo.com вместо ")
                    .append(apiBaseUrl).append("), верни path с полным URL http(s)://...");
            user.append("\n  • Нельзя возвращать относительный path, если apiBaseUrl не подходит ")
                    .append("по хосту — либо полный URL в path, либо needClarification(api_base_url).\n");
        }

        if (lastStatusCode != null)
            user.append("\nHTTP статус последнего ответа: ").append(lastStatusCode).append("\n");
        if (lastResponseHeaders != null && !lastResponseHeaders.isEmpty()) {
            user.append("\nЗаголовки последнего ответа API:\n");
            for (Map.Entry<String, String> h : lastResponseHeaders.entrySet()) {
                user.append("- ").append(h.getKey()).append(": ").append(h.getValue()).append("\n");
            }
        }
        if (lastResponseBody != null && !lastResponseBody.isBlank()) {
            user.append("\nТело последнего ответа API:\n")
                    .append(lastResponseBody.length() > 12000
                            ? lastResponseBody.substring(0, 12000) : lastResponseBody)
                    .append("\n");
        }

        String response = openRouter.complete(appProps.getPrompts().getPlanStep(), user.toString());
        String cleaned = response.replaceAll("^```json\\s*", "").replaceAll("```\\s*$", "").trim();
        return parsePlans(cleaned, tc.type());
    }

    /**
     * Разбирает JSON-ответ модели как один план или массив планов и проверяет каждый план.
     *
     * @param cleaned  очищенный текст ответа модели
     * @param caseType тип кейса для проверки операций
     * @return список планов, не пустой
     * @throws GenerationException ответ не является валидным JSON, массив пуст или план не проходит проверку
     */
    private List<Plan> parsePlans(String cleaned, String caseType) {
        try {
            JsonNode root = mapper.readTree(cleaned);
            List<Plan> plans = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    plans.add(toPlan(node).validateAgainst(caseType));
                }
                if (plans.isEmpty()) {
                    throw new GenerationException(
                            "Модель вернула пустой массив планов");
                }
                return plans;
            }
            plans.add(toPlan(root).validateAgainst(caseType));
            return plans;
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new GenerationException("Модель вернула невалидный JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Преобразует JSON-объект в {@link Plan}.
     * <p>
     * Строковые поля берутся через {@link #str}, которое вызывает {@code toString()} у
     * значения. Поля, переданные моделью не строкой (например, вложенный объект в
     * {@code bodyJson}), попадают в план как Java-представление, а не как JSON.
     * {@code elements} разбирается через {@link #parseElements}, {@code expectedStatus}
     * через {@link #parseExpectedStatus}.
     *
     * @param node JSON-узел плана
     * @return план без проверки типа кейса
     * @throws GenerationException узел не является объектом или неверный {@code expectedStatus}
     */
    private Plan toPlan(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new GenerationException(
                    "Ожидался JSON-объект плана, получено: "
                            + (node == null ? "null" : node.getNodeType()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = mapper.convertValue(node, new TypeReference<>() {
        });
        List<ElementTarget> elements = parseElements(map.get("elements"));
        return new Plan(
                str(map.get("operation")), str(map.get("locator")), str(map.get("value")),
                str(map.get("method")), str(map.get("path")), str(map.get("bodyJson")),
                parseExpectedStatus(map.get("expectedStatus")),
                str(map.get("topicKey")), str(map.get("question")), str(map.get("reason")),
                str(map.get("locatorStrategy")), str(map.get("elementRole")),
                str(map.get("elementName")), str(map.get("locatorAlternatives")),
                elements, str(map.get("requestHeaders")),
                str(map.get("snapshotKey")), str(map.get("jsonPath")), str(map.get("attribute"))
        );
    }

    /**
     * Извлекает цели элементов из поля {@code elements} плана.
     * <p>
     * Записи, которые не являются объектом или у которых пустой {@code locator}, пропускаются.
     * Поле {@code alternatives} может быть массивом или строкой с запятыми, которую разбирает
     * {@link #splitAlternativesInternal}.
     *
     * @param raw значение поля {@code elements}; может быть {@code null}
     * @return список целей, возможно пустой
     */
    private List<ElementTarget> parseElements(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) return List.of();
        List<ElementTarget> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object loc = m.get("locator");
            if (loc == null || loc.toString().isBlank()) continue;
            List<String> alts = new ArrayList<>();
            Object altRaw = m.get("alternatives");
            if (altRaw instanceof List<?> al) {
                for (Object a : al) if (a != null) alts.add(a.toString());
            } else if (altRaw != null) {
                alts = splitAlternativesInternal(altRaw.toString());
            }
            out.add(new ElementTarget(
                    loc.toString(),
                    str(m.get("locatorStrategy")),
                    str(m.get("elementRole")),
                    str(m.get("elementName")),
                    alts
            ));
        }
        return out;
    }

    /**
     * Приводит ожидаемый HTTP-статус к числу.
     * <p>
     * Принимается число любого типа, которое приводится через {@code intValue()}, либо строка
     * ровно из трёх цифр. {@code null} означает, что статус не проверяется.
     *
     * @param raw значение из JSON; может быть {@code null}
     * @return код статуса или {@code null}
     * @throws GenerationException значение не число и не строка из трёх цифр
     */
    private static Integer parseExpectedStatus(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && s.matches("\\d{3}")) return Integer.parseInt(s);
        throw new GenerationException("expectedStatus должен быть числом из 3 цифр, получено: " + raw);
    }

    /**
     * Приводит значение к строке через {@code toString()}.
     *
     * @param o значение; может быть {@code null}
     * @return строка либо {@code null}
     */
    private static String str(Object o) {
        return o == null ? null : o.toString();
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
}
