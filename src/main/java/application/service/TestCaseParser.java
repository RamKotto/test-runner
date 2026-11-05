package application.service;

import application.dto.Dtos.ParsedTestCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор Markdown-файлов тест-кейсов и индекса {@code _index.md}.
 * <p>
 * Место в конвейере: {@link TestCaseFetcher} скачивает файлы и передаёт их сюда;
 * результат — {@link ParsedTestCase}, который дальше выполняет
 * {@link HybridCaseRunner}. Формат кейса:
 * <ul>
 *   <li>заголовок {@code # TC-ID — Название};</li>
 *   <li>поля списком {@code - **Ключ:** значение} (ID, Тип, Приоритет, Требование);</li>
 *   <li>разделы {@code ## Предусловия}, {@code ## Шаги} (нумерованный список)
 *       и {@code ## Ожидаемый результат}.</li>
 * </ul>
 * Нераспознанные строки молча игнорируются.
 */
@Slf4j
@Service
public class TestCaseParser {

    /**
     * Заголовок кейса вида {@code # TC-XXX — Название}; тире может быть длинным или коротким.
     */
    private static final Pattern HEADER = Pattern.compile("^#\\s+(TC-[A-Z0-9\\-]+)\\s+[—-]\\s+(.+)$");

    /**
     * Строка поля вида {@code - **Ключ:** значение}.
     */
    private static final Pattern FIELD = Pattern.compile("^-\\s+\\*\\*([^*]+):\\*\\*\\s*(.+)$");

    /**
     * Ссылка на файл кейса в индексе: {@code [текст](файл.md)}.
     */
    private static final Pattern INDEX_ENTRY = Pattern.compile("\\[([^\\]]+)]\\(([^)]+\\.md)\\)");

    /**
     * Безопасное имя файла: латинские буквы, цифры, «_» и «-», расширение {@code .md}, без каталогов.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("^([a-zA-Z0-9_\\-]+\\.md)$");

    /**
     * Допустимые типы кейса. Любой другой тип помечается как {@code INVALID}.
     */
    private static final Set<String> KNOWN_TYPES =
            Set.of("UI", "API", "E2E", "NEGATIVE", "SECURITY");

    /**
     * Проверяет, что документ является индексом кейсов.
     *
     * @param md текст документа; может быть {@code null}
     * @return {@code true}, если текст начинается с заголовка «# Тест-кейсы — индекс»
     */
    public boolean isIndexMarkdown(String md) {
        return md != null && md.startsWith("# Тест-кейсы — индекс");
    }

    /**
     * Извлекает ссылки на файлы кейсов из индекса.
     * Строки обрабатываются по отдельности, строки без ссылки на {@code .md} пропускаются; в каждой строке берётся первая ссылка.
     * Порядок ссылок сохраняется.
     *
     * @param md текст индекса; не должен быть {@code null}
     * @return список записей индекса; пустой, если ссылок нет
     */
    public List<IndexEntry> parseIndex(String md) {
        List<IndexEntry> result = new ArrayList<>();
        for (String line : md.split("\\R")) {
            Matcher m = INDEX_ENTRY.matcher(line.trim());
            if (m.find()) {
                result.add(new IndexEntry(m.group(1), m.group(2)));
            }
        }
        return result;
    }

    /**
     * Приводит относительный путь из индекса к безопасному имени файла.
     * <p>
     * Обратные слеши заменяются прямыми, ведущий {@code ./} отбрасывается, каталоги
     * отбрасываются, остаётся последний сегмент пути. Результат должен соответствовать
     * {@link #SAFE_NAME}.
     *
     * @param relativePath относительный путь из ссылки индекса; не должен быть {@code null}
     * @return безопасное имя файла либо {@code null}, если имя не проходит проверку
     */
    public String toSafeFilename(String relativePath) {
        String base = relativePath.replace("\\", "/");
        if (base.startsWith("./")) base = base.substring(2);
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        if (!SAFE_NAME.matcher(base).matches()) return null;
        return base;
    }

    /**
     * Разбирает Markdown тест-кейса.
     * <p>
     * Правила:
     * <ul>
     *   <li>идентификатор и название берутся из заголовка, но заголовок учитывается,
     *       только если идентификатор ещё не задан; поле {@code **ID:**} перекрывает его;</li>
     *   <li>тип приводится к верхнему регистру и проверяется по {@link #KNOWN_TYPES}; при отсутствии
     *       или неизвестном значении ставится {@code INVALID} с предупреждением в логе;</li>
     *   <li>приоритет по умолчанию {@code P2}, требование по умолчанию {@code N/A};</li>
     *   <li>шаги — строки вида {@code N. текст} из раздела «Шаги»; предусловия — пункты
     *       списка из раздела «Предусловия»;</li>
     *   <li>ожидаемый результат — пункты и строки раздела «Ожидаемый результат», склеенные
     *       через пробел; строки-заголовки и цитаты ({@code >}) пропускаются;</li>
     *   <li>секции определяются по подстроке в заголовке {@code ## }, без учёта регистра.</li>
     * </ul>
     *
     * @param markdown       текст кейса; не должен быть пустым
     * @param sourceFilename имя файла, которое сохраняется в записи и выводится в ошибке
     * @return разобранный кейс с исходным текстом в {@code rawMarkdown}
     * @throws IllegalArgumentException текст пуст, либо не найдены идентификатор, название или шаги
     */
    public ParsedTestCase parse(String markdown, String sourceFilename) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("Пустой Markdown");
        }
        String id = null, title = null, type = null, requirementRef = "N/A",
                expectedResult = "", priority = "P2";
        List<String> preconditions = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        String section = "none";

        for (String rawLine : markdown.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;

            Matcher h = HEADER.matcher(line);
            if (h.matches()) {
                if (id == null) id = h.group(1);
                title = h.group(2).strip();
                continue;
            }

            if (line.startsWith("## ")) {
                String hdr = line.substring(3).strip().toLowerCase();
                if (hdr.contains("шаги")) section = "steps";
                else if (hdr.contains("предусловия")) section = "preconditions";
                else if (hdr.contains("ожидаемый результат")) section = "expected";
                else section = "other";
                continue;
            }

            Matcher f = FIELD.matcher(line);
            if (f.matches()) {
                String key = f.group(1).strip();
                String val = f.group(2).strip();
                switch (key) {
                    case "ID" -> id = val;
                    case "Тип" -> type = val;
                    case "Приоритет" -> priority = val;
                    case "Требование" -> requirementRef = val;
                    default -> {
                    }
                }
                continue;
            }

            if (line.startsWith(">")) continue;

            if ("steps".equals(section)) {
                Matcher s = Pattern.compile("^\\d+\\.\\s+(.+)$").matcher(line);
                if (s.matches()) steps.add(s.group(1).strip());
            } else if ("preconditions".equals(section)) {
                if (line.startsWith("- ")) preconditions.add(line.substring(2).strip());
            } else if ("expected".equals(section)) {
                if (line.startsWith("- ")) {
                    String item = line.substring(2).strip();
                    expectedResult = expectedResult.isBlank() ? item : expectedResult + " " + item;
                } else if (!line.startsWith("#")) {
                    expectedResult = expectedResult.isBlank() ? line : expectedResult + " " + line;
                }
            }
        }

        if (title == null) {
            throw new IllegalArgumentException("Не удалось распарсить тест-кейс: " + sourceFilename
                    + " (не найден заголовок «# TC-ID — название», id=" + id + ")");
        }
        if (id == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Не удалось распарсить тест-кейс: " + sourceFilename
                    + " (id=" + id + ", steps=" + steps.size() + ")");
        }
        if (type == null) {
            log.warn("TC {}: не указан тип кейса, помечаю как INVALID", id);
            type = "INVALID";
        } else {
            String norm = type.trim().toUpperCase();
            if (!KNOWN_TYPES.contains(norm)) {
                log.warn("TC {}: неизвестный тип '{}', помечаю как INVALID", id, type);
                type = "INVALID";
            } else {
                type = norm;
            }
        }
        return new ParsedTestCase(id, type, title, requirementRef, preconditions, steps,
                expectedResult, priority, sourceFilename, markdown, List.of());
    }

    /**
     * Запись индекса: ссылка на файл тест-кейса.
     *
     * @param testCaseId   текст ссылки, обычно идентификатор кейса
     * @param relativePath путь к файлу относительно каталога индекса
     */
    public record IndexEntry(String testCaseId, String relativePath) {
    }
}
