package com.example.sdlc.codebase;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays Flyway versioned migrations ({@code V<version>__<description>.sql}, in numeric version order)
 * into the tables they leave behind. Only CREATE TABLE, DROP TABLE and ALTER TABLE ADD / DROP / RENAME
 * COLUMN and RENAME TO are understood; indexes, views, data changes and everything else are ignored, as
 * are repeatable and Java migrations. Statements are split on ';' outside quotes, dollar quotes and
 * comments.
 */
final class SqlMigrations {

    private static final String MIGRATIONS = "src/main/resources/db/migration";

    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+(?:[._]\\d+)*)__.*\\.sql");
    // A name part may be quoted or a Flyway placeholder such as ${schema}. Repeated groups are possessive so
    // that very long names or DROP lists cannot overflow the regex engine's stack.
    private static final String PART = "(?:\"[^\"]+\"|`[^`]+`|\\[[^\\]]+]|\\$\\{[^}]*}|[\\w$]+)";
    private static final String NAME = PART + "(?:\\s*\\.\\s*" + PART + ")*+";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)create\\s+(?:(?:global\\s+|local\\s+)?temp(?:orary)?\\s+|unlogged\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?(" + NAME + ")");
    private static final Pattern ALTER_TABLE =
            Pattern.compile("(?is)alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?(" + NAME + ")\\s+(.*)");
    private static final Pattern DROP_TABLE = Pattern.compile("(?is)drop\\s+table\\s+(?:if\\s+exists\\s+)?(" + NAME + "(?:\\s*,\\s*" + NAME + ")*+)");
    private static final Pattern ADD_COLUMN = Pattern.compile("(?is)add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?(" + PART + ")");
    private static final Pattern DROP_COLUMN = Pattern.compile("(?is)drop\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?(" + PART + ")");
    private static final Pattern RENAME_TABLE = Pattern.compile("(?is)rename\\s+to\\s+(" + NAME + ")");
    private static final Pattern RENAME_COLUMN = Pattern.compile("(?is)rename\\s+(?:column\\s+)?(" + PART + ")\\s+to\\s+(" + PART + ")");
    private static final Pattern FIRST_TOKEN = Pattern.compile("\\s*(" + PART + ")");
    private static final Pattern NAME_PART = Pattern.compile(PART);
    /** Leading words of table elements and ALTER actions that are constraints or indexes, not columns. */
    private static final Set<String> NOT_COLUMNS = Set.of(
            "constraint", "primary", "foreign", "unique", "check", "index", "key", "exclude", "like", "fulltext", "spatial");

    private record Script(Path file, List<BigInteger> version) implements Comparable<Script> {

        @Override
        public int compareTo(Script other) {
            for (int i = 0; i < Math.min(version.size(), other.version.size()); i++) {
                int byPart = version.get(i).compareTo(other.version.get(i));
                if (byPart != 0) {
                    return byPart;
                }
            }
            int byLength = Integer.compare(version.size(), other.version.size());
            return byLength != 0 ? byLength : file.compareTo(other.file);
        }
    }

    private SqlMigrations() {
    }

    static List<Table> read(Path moduleRoot) {
        List<Script> scripts = CodebaseIndexer.files(moduleRoot.resolve(MIGRATIONS), name -> name.endsWith(".sql")).stream()
                .map(SqlMigrations::script)
                .flatMap(Optional::stream)
                .sorted()
                .toList();
        Map<String, List<String>> columns = new HashMap<>();
        Map<String, String> definedIn = new HashMap<>();
        for (Script script : scripts) {
            String path = CodebaseIndexer.relativePath(moduleRoot, script.file());
            for (String statement : CodebaseIndexer.read(script.file()).map(SqlMigrations::statements).orElse(List.of())) {
                apply(statement.strip(), path, columns, definedIn);
            }
        }
        return columns.keySet().stream()
                .sorted()
                .map(name -> new Table(name, columns.get(name), definedIn.get(name)))
                .toList();
    }

    /**
     * Last part of a possibly schema-qualified, possibly quoted name, unquoted and lower-cased. Parts are
     * matched rather than split on '.', so {@code ${flyway.schema}.links} and {@code "a.b"} come out right.
     */
    static String normalizeName(String name) {
        Matcher part = NAME_PART.matcher(name);
        String last = name.strip();
        while (part.find()) {
            last = part.group();
        }
        return last.replaceAll("[\"`\\[\\]]", "").toLowerCase(Locale.ROOT);
    }

    private static Optional<Script> script(Path file) {
        Matcher name = FILE_NAME.matcher(file.getFileName().toString());
        if (!name.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Script(file, Arrays.stream(name.group(1).split("[._]")).map(BigInteger::new).toList()));
    }

    private static void apply(String statement, String path, Map<String, List<String>> columns, Map<String, String> definedIn) {
        Matcher create = CREATE_TABLE.matcher(statement);
        if (create.lookingAt()) {
            String table = normalizeName(create.group(1));
            if (!columns.containsKey(table)) {
                columns.put(table, tableElements(statement, create.end()));
                definedIn.put(table, path);
            }
            return;
        }
        Matcher alter = ALTER_TABLE.matcher(statement);
        if (alter.lookingAt()) {
            String table = normalizeName(alter.group(1));
            List<String> tableColumns = columns.get(table);
            if (tableColumns != null) {
                for (String action : splitTopLevel(alter.group(2))) {
                    table = alter(table, tableColumns, action.strip(), columns, definedIn);
                }
            }
            return;
        }
        Matcher drop = DROP_TABLE.matcher(statement);
        if (drop.lookingAt()) {
            for (String name : drop.group(1).split(",")) {
                columns.remove(normalizeName(name));
                definedIn.remove(normalizeName(name));
            }
        }
    }

    /** Applies one ALTER TABLE action and returns the table's name afterwards. */
    private static String alter(String table, List<String> tableColumns, String action,
                                Map<String, List<String>> columns, Map<String, String> definedIn) {
        Matcher renameTable = RENAME_TABLE.matcher(action);
        Matcher renameColumn = RENAME_COLUMN.matcher(action);
        Matcher addColumn = ADD_COLUMN.matcher(action);
        Matcher dropColumn = DROP_COLUMN.matcher(action);
        if (renameTable.lookingAt()) {
            String renamed = normalizeName(renameTable.group(1));
            columns.put(renamed, columns.remove(table));
            definedIn.put(renamed, definedIn.remove(table));
            return renamed;
        }
        if (renameColumn.lookingAt()) {
            int index = tableColumns.indexOf(normalizeName(renameColumn.group(1)));
            if (index >= 0) {
                tableColumns.set(index, normalizeName(renameColumn.group(2)));
            }
        } else if (addColumn.lookingAt()) {
            columnName(addColumn.group(1)).filter(c -> !tableColumns.contains(c)).ifPresent(tableColumns::add);
        } else if (dropColumn.lookingAt()) {
            columnName(dropColumn.group(1)).ifPresent(tableColumns::remove);
        }
        return table;
    }

    /** Column names from the parenthesised element list that follows the table name, if any. */
    private static List<String> tableElements(String statement, int afterName) {
        String rest = statement.substring(afterName).stripLeading();
        List<String> names = new ArrayList<>();
        if (!rest.startsWith("(")) {
            return names;
        }
        for (String element : splitTopLevel(rest.substring(1))) {
            Matcher first = FIRST_TOKEN.matcher(element);
            if (first.lookingAt()) {
                columnName(first.group(1)).filter(c -> !names.contains(c)).ifPresent(names::add);
            }
        }
        return names;
    }

    private static Optional<String> columnName(String token) {
        boolean quoted = "\"`[".indexOf(token.charAt(0)) >= 0;
        String name = normalizeName(token);
        return quoted || !NOT_COLUMNS.contains(name) ? Optional.of(name) : Optional.empty();
    }

    /** Splits on commas outside parentheses and single quotes, stopping at an unmatched ')'. */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (quoted) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && depth-- == 0) {
                parts.add(text.substring(from, i));
                return parts;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(text.substring(from));
        return parts;
    }

    /** Statements of a script with comments removed; quoted text is kept intact. */
    private static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (sql.startsWith("--", i)) {
                int newline = sql.indexOf('\n', i);
                i = newline < 0 ? sql.length() : newline;
            } else if (sql.startsWith("/*", i)) {
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? sql.length() : close + 2;
                current.append(' ');
            } else if (sql.startsWith("$$", i) || c == '\'' || c == '"' || c == '`') {
                String quote = sql.startsWith("$$", i) ? "$$" : String.valueOf(c);
                int close = sql.indexOf(quote, i + quote.length());
                int end = close < 0 ? sql.length() : close + quote.length();
                current.append(sql, i, end);
                i = end;
            } else if (c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        statements.add(current.toString());
        return statements;
    }
}
