package application.service;

import application.dto.Dtos.ParsedTestCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TestCaseParser {

    private static final Pattern HEADER = Pattern.compile("^#\\s+(TC-[A-Z0-9\\-]+)\\s+[—-]\\s+(.+)$");
    private static final Pattern FIELD = Pattern.compile("^-\\s+\\*\\*([^*]+):\\*\\*\\s*(.+)$");
    private static final Pattern INDEX_ENTRY = Pattern.compile("\\[([^\\]]+)]\\(([^)]+\\.md)\\)");
    private static final Pattern SAFE_NAME = Pattern.compile("^([a-zA-Z0-9_\\-]+\\.md)$");

    private static final Set<String> KNOWN_TYPES =
            Set.of("UI", "API", "E2E", "NEGATIVE", "SECURITY");

    public boolean isIndexMarkdown(String md) {
        return md != null && md.startsWith("# Тест-кейсы — индекс");
    }

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

    public String toSafeFilename(String relativePath) {
        String base = relativePath.replace("\\", "/");
        if (base.startsWith("./")) base = base.substring(2);
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        if (!SAFE_NAME.matcher(base).matches()) return null;
        return base;
    }

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

            if (id == null) {
                Matcher h = HEADER.matcher(line);
                if (h.matches()) {
                    id = h.group(1);
                    title = h.group(2).strip();
                    continue;
                }
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

        if (id == null || title == null || steps.isEmpty()) {
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

    public record IndexEntry(String testCaseId, String relativePath) {
    }
}
