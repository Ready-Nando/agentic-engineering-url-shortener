package com.example.sdlc.policy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.example.sdlc.Json;
import com.example.sdlc.workspace.FileDelta;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.ObjectReader;
import tools.jackson.dataformat.yaml.YAMLParser;

/**
 * Deterministic guardrails for workspace changes proposed by reasoning components. Decisions are derived
 * only from the concrete paths and content of the change, so a proposer cannot talk its way past policy.
 */
public final class ChangePolicy {

    public static final List<PolicyRule> RULES = List.of(
            new PolicyRule("SEC-01", PolicyCategory.SECURITY, PolicyDecision.DENY,
                    "Hard-coded secret or credential in added content"),
            new PolicyRule("SEC-02", PolicyCategory.SECURITY, PolicyDecision.DENY,
                    "Process execution or dynamic code loading added to production code (src/main/java)"),
            new PolicyRule("SEC-03", PolicyCategory.SECURITY, PolicyDecision.DENY,
                    "Build toolchain or CI configuration modified (mvnw, .mvn/, .github/)"),
            new PolicyRule("SEC-04", PolicyCategory.SECURITY, PolicyDecision.REQUIRE_APPROVAL,
                    "Existing request-handling or input-validation code changed or deleted (src/main/java: web/ and api/"
                            + " packages, *Controller, *Validator and *Policy classes)"),
            new PolicyRule("SEC-05", PolicyCategory.SECURITY, PolicyDecision.REQUIRE_APPROVAL,
                    "Process execution or dynamic code loading added to test code (src/test), which verification runs on the host"),
            new PolicyRule("SEC-06", PolicyCategory.SECURITY, PolicyDecision.REQUIRE_APPROVAL,
                    "New request-handling code added (src/main/java: a new *Controller class or a new file in a web/ or api/ package)"),
            new PolicyRule("CMP-01", PolicyCategory.COMPLIANCE, PolicyDecision.DENY,
                    "Schema stores raw personal data (IP address, e-mail, phone, user agent)"),
            new PolicyRule("CMP-02", PolicyCategory.COMPLIANCE, PolicyDecision.DENY,
                    "Personal data written to application logs"),
            new PolicyRule("CC-01", PolicyCategory.CHANGE_CONTROL, PolicyDecision.DENY,
                    "Already-applied database migration or existing SQL resource modified or deleted"),
            new PolicyRule("CC-02", PolicyCategory.CHANGE_CONTROL, PolicyDecision.REQUIRE_APPROVAL,
                    "New (not yet applied) database migration or SQL resource added or changed"),
            new PolicyRule("CC-03", PolicyCategory.CHANGE_CONTROL, PolicyDecision.DENY,
                    "Destructive schema or data operation in a migration or SQL resource (DROP, TRUNCATE, DELETE)"),
            new PolicyRule("CC-04", PolicyCategory.CHANGE_CONTROL, PolicyDecision.REQUIRE_APPROVAL,
                    "Dependency or build descriptor change (pom.xml)"),
            new PolicyRule("CC-05", PolicyCategory.CHANGE_CONTROL, PolicyDecision.DENY,
                    "File outside the task's approved scope"),
            new PolicyRule("CC-06", PolicyCategory.CHANGE_CONTROL, PolicyDecision.REQUIRE_APPROVAL,
                    "Existing source file changed that impact analysis did not anticipate"),
            new PolicyRule("CC-07", PolicyCategory.CHANGE_CONTROL, PolicyDecision.REQUIRE_APPROVAL,
                    "Large change (more than 15 files or 800 changed lines)"),
            new PolicyRule("CC-08", PolicyCategory.CHANGE_CONTROL, PolicyDecision.REQUIRE_APPROVAL,
                    "Existing test source deleted, or tests disabled (@Disabled, @Ignore)"),
            new PolicyRule("CC-09", PolicyCategory.CHANGE_CONTROL, PolicyDecision.REQUIRE_APPROVAL,
                    "Spring application configuration changed (application*.yml, .yaml, .properties or .xml under src/main/resources)"),
            new PolicyRule("CC-10", PolicyCategory.CHANGE_CONTROL, PolicyDecision.DENY,
                    "Configuration redirects schema management or loads other configuration (spring.flyway.*, spring.sql.init.*,"
                            + " spring.config.import/location, JPA DDL generation, JDBC URL INIT scripts), or cannot be parsed"),
            new PolicyRule("GOV-01", PolicyCategory.GOVERNANCE, PolicyDecision.ALLOW,
                    "Proposer's self-declared risk recorded but not used to relax the decision"));

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}"),
            Pattern.compile("(?i)(password|passwd|secret|api[_-]?key|access[_-]?token|client[_-]?secret)\\s*[:=]\\s*\"[^\"\\s$]{8,}\""));

    // YAML and properties values need no quotes. A key ending in a secret word with a literal value is a
    // credential; a ${...} placeholder is not, unless its default is itself a literal secret. Plain numbers
    // (durations, sizes) are not secrets.
    private static final Pattern CONFIG_SECRET = Pattern.compile(
            "(?i)^\\s*(?:-\\s+)?[\\w.-]*?(password|passwd|secret|api[_-]?key|apikey|token|client[_-]?secret)\\s*[:=]\\s*['\"]?"
                    + "(?:(?!\\$\\{|\\d+(?:[\\s'\"#]|$))[^\\s'\"#]{8,}|\\$\\{[^}:]*:[^}\\s'\"]{8,}})");

    // Runtime.getRuntime() is flagged even when assigned to a variable, except for its harmless resource queries.
    private static final String HARMLESS_RUNTIME_QUERY = "\\s*\\.\\s*(?:availableProcessors|freeMemory|totalMemory|maxMemory"
            + "|addShutdownHook|removeShutdownHook)\\s*\\(";
    private static final Pattern PROCESS_EXECUTION = processExecution(HARMLESS_RUNTIME_QUERY);
    // A Java line ending in Runtime.getRuntime() (perhaps followed by a comment) may continue with a harmless query on
    // the next line; the check of the whole added statement, which drops comments, decides.
    private static final Pattern PROCESS_EXECUTION_JAVA_LINE = processExecution(HARMLESS_RUNTIME_QUERY + "|\\s*(?://.*|/\\*.*)?$");

    // "name TYPE", also as the target of a type change ("ALTER COLUMN name [SET DATA] TYPE TYPE").
    private static final Pattern SQL_COLUMN_DEFINITION = Pattern.compile(
            "(?i)(\"[^\"]+\"|`[^`]+`|\\[[^\\]]+]|\\b[a-z_][\\w$]*)\\s+(?:SET\\s+DATA\\s+)?(?:TYPE\\s+)?"
                    + "(?:N?VARCHAR(?:2|_IGNORECASE)?|N?CHAR(?:ACTER)?|(?:TINY|MEDIUM|LONG)?TEXT|CITEXT"
                    + "|N?CLOB|INET|CIDR|VARBINARY|BINARY|(?:TINY|MEDIUM|LONG)?BLOB|BYTEA)\\b");

    private static final Pattern LOG_STATEMENT = Pattern.compile(
            "(?i)\\b\\w*log(?:ger)?\\s*\\.\\s*(?:at)?(?:trace|debug|info|warn|error)\\s*\\(");
    private static final Pattern PERSONAL_DATA_SOURCE = Pattern.compile(
            "(?i)getRemoteAddr|X-Forwarded-For|\"User-Agent\"|HttpHeaders\\.USER_AGENT|\\bemail\\b");
    // A further call on the result of a call, e.g. ".log(" after "log.atInfo()"; the match ends at its '('.
    private static final Pattern CHAINED_CALL = Pattern.compile("\\s*\\.\\s*(?:<[^>]*>\\s*)?\\w+\\s*\\(");

    // Applied to one statement at a time. Any DROP action of an ALTER TABLE counts (ONLY, IF EXISTS, quoted names,
    // several actions), except dropping a column's default or NOT NULL constraint.
    private static final Pattern DESTRUCTIVE_SQL = Pattern.compile(
            "(?i)\\b(DROP\\s+(?:TABLE|COLUMN|SCHEMA|DATABASE)|TRUNCATE|DELETE\\s+FROM)\\b"
                    + "|\\bALTER\\s+TABLE\\b.*?\\bDROP\\b(?!\\s+(?:NOT\\s+NULL|DEFAULT)\\b)");

    private static final Pattern DISABLED_TEST = Pattern.compile("@(?:[\\w.]+\\.)?(?:Disabled\\w*|Ignore)\\b");

    // Java source is decoded as the compiler does: a Unicode escape is a character (even a quote or a line break).
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u+([0-9a-fA-F]{4})");

    private static final PathMatcher MIGRATION = glob("{db/migration/**,**/db/migration/**}");
    private static final PathMatcher REQUEST_HANDLING = glob("{**/web/**,**/api/**,**/*Controller.java,**/*Validator.java,**/*Policy.java}");
    private static final PathMatcher NEW_REQUEST_HANDLING = glob("{**/web/**,**/api/**,**/*Controller.java}");
    // Case-insensitive: on a case-insensitive file system Spring also finds Application.YML. Spring also loads
    // Java XML properties (application.xml), but only from its own locations, so an EAR descriptor
    // (META-INF/application.xml) is not configuration.
    private static final Pattern APPLICATION_CONFIG = Pattern.compile("src/main/resources/(?:(?:.+/)?(?i:application[^/]*\\.(?:yml|yaml|properties))"
            + "|(?:config/)?(?i:application[^/]*\\.xml))");

    // Normalised (see configKey) property prefixes that decide which schema changes run, or which other
    // configuration is loaded, outside the migration rules.
    private static final List<String> SCHEMA_OR_LOADING_KEYS = List.of("spring.flyway", "spring.liquibase", "spring.sql.init",
            "spring.config.import", "spring.config.location", "spring.config.additionallocation", "spring.config.name",
            "spring.profiles.active", "spring.profiles.include", "spring.profiles.group", "spring.profiles.default",
            "spring.jpa.hibernate.ddlauto", "spring.jpa.generateddl", "spring.jpa.deferdatasourceinitialization",
            "spring.datasource.schema", "spring.datasource.data", "spring.datasource.initializationmode");
    private static final Pattern JPA_SCHEMA_GENERATION = Pattern.compile("spring\\.jpa\\.properties\\..*(?:hbm2ddl|schemageneration).*");
    // SQL a connection pool runs on every new connection (hikari.connection-init-sql, dbcp2.connection-init-sqls, ...).
    private static final Pattern CONNECTION_INIT_SQL = Pattern.compile("spring\\.datasource\\..*initsqls?(?:\\..*)?");
    private static final Pattern JDBC_INIT_SCRIPT = Pattern.compile("(?i)\\bjdbc:.*\\bINIT\\s*=");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)");
    // A placeholder without nested placeholders, with its optional default.
    private static final Pattern INNERMOST_PLACEHOLDER = Pattern.compile("\\$\\{([^${}:]*)(?::([^${}]*))?}");
    // Spring's multi-document .properties separator.
    private static final Pattern PROPERTIES_DOCUMENT_SEPARATOR = Pattern.compile("(?m)^[#!]---[ \\t]*$");
    // Spring refuses duplicate YAML keys; Jackson would silently keep the last one.
    private static final ObjectReader YAML_DOCUMENTS = Json.YAML.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final int MAX_FILES = 15;
    private static final int MAX_CHANGED_LINES = 800;

    public PolicyVerdict evaluate(ChangeContext change) {
        List<PolicyFinding> findings = new ArrayList<>();
        int changedLines = 0;
        for (FileDelta delta : change.deltas()) {
            List<String> added = addedLines(delta);
            changedLines += added.size() + removedLineCount(delta);
            evaluateFile(change, delta, added, findings);
        }
        if (change.deltas().size() > MAX_FILES || changedLines > MAX_CHANGED_LINES) {
            findings.add(finding("CC-07", null, change.deltas().size() + " files, " + changedLines + " changed lines"));
        }
        PolicyVerdict verdict = PolicyVerdict.of(findings);
        if (verdict.decision() != PolicyDecision.ALLOW && isLowRiskClaim(change.declaredRisk())) {
            findings.add(finding("GOV-01", null, "proposer declared risk '" + change.declaredRisk()
                    + "'; decision " + verdict.decision() + " computed from the concrete change"));
            verdict = PolicyVerdict.of(findings);
        }
        return verdict;
    }

    private void evaluateFile(ChangeContext change, FileDelta delta, List<String> added, List<PolicyFinding> findings) {
        String path = delta.path();
        Path asPath = Path.of(path);
        boolean mainJava = path.startsWith("src/main/java/") && path.endsWith(".java");
        boolean testSource = path.startsWith("src/test/");
        boolean java = path.endsWith(".java") && (mainJava || testSource);
        Pattern processExecution = java ? PROCESS_EXECUTION_JAVA_LINE : PROCESS_EXECUTION;
        boolean config = path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".properties");
        boolean existing = change.inBaseline().test(path);

        if (!inScope(change.scope(), asPath)) {
            findings.add(finding("CC-05", path, "not covered by task scope " + change.scope()));
        }
        for (String line : added) {
            if (SECRET_PATTERNS.stream().anyMatch(secret -> secret.matcher(line).find())
                    || (config && CONFIG_SECRET.matcher(line).find())) {
                findings.add(finding("SEC-01", path, "added line looks like a credential: " + redact(line)));
            }
            if (mainJava && processExecution.matcher(line).find()) {
                findings.add(finding("SEC-02", path, "added: " + line.strip()));
            }
            if (testSource && processExecution.matcher(line).find()) {
                findings.add(finding("SEC-05", path, "tests run on the verification host; added: " + line.strip()));
            }
            if (mainJava && LOG_STATEMENT.matcher(line).find() && PERSONAL_DATA_SOURCE.matcher(line).find()) {
                findings.add(finding("CMP-02", path, "log statement includes personal data: " + line.strip()));
            }
            if (testSource && DISABLED_TEST.matcher(line).find()) {
                findings.add(finding("CC-08", path, "test disabled: " + line.strip()));
            }
        }
        if (java) {
            evaluateAddedStatements(path, mainJava, delta, findings);
        }
        if (path.equals("mvnw") || path.equals("mvnw.cmd") || path.startsWith(".mvn/") || path.startsWith(".github/")) {
            findings.add(finding("SEC-03", path, "toolchain files are outside agent autonomy"));
        }
        // Keyed on the baseline, not on the operation: re-creating a file an earlier change deleted still changes it.
        if (existing && path.startsWith("src/main/java/") && REQUEST_HANDLING.matches(asPath)) {
            findings.add(finding("SEC-04", path, "security-relevant code path changed; needs reviewer sign-off"));
        }
        if (!existing && delta.after() != null && path.startsWith("src/main/java/") && NEW_REQUEST_HANDLING.matches(asPath)) {
            findings.add(finding("SEC-06", path, "new request-handling code is reachable from outside; needs reviewer sign-off"));
        }
        if (existing && testSource && delta.after() == null) {
            findings.add(finding("CC-08", path, "existing test removed; dropping coverage is a human decision"));
        }
        // Any SQL on the main classpath can be run against the database (spring.sql.init, a relocated Flyway
        // location), so it is governed like a migration. Test fixtures only ever reach test databases.
        boolean sqlResource = path.startsWith("src/main/resources/") && path.toLowerCase(Locale.ROOT).endsWith(".sql");
        if (!testSource && (MIGRATION.matches(asPath) || sqlResource)) {
            // A migration that is not yet applied runs as a whole, so all of it is checked: added lines alone lose
            // the context (an open string literal or comment) that decides what an edited line means.
            String text = existing || delta.after() == null ? String.join("\n", added) : delta.after();
            evaluateMigration(path, existing, text, findings);
        }
        if (APPLICATION_CONFIG.matcher(path).matches()) {
            findings.add(finding("CC-09", path, "application configuration changes runtime behaviour; needs reviewer sign-off"));
            evaluateConfiguration(path, delta, findings);
        }
        if (asPath.getFileName().toString().equals("pom.xml")) {
            findings.add(finding("CC-04", path, "dependency changes need supply-chain review"));
        }
        if (change.anticipatedPaths() != null && existing && path.endsWith(".java")
                && !change.anticipatedPaths().contains(path)) {
            findings.add(finding("CC-06", path, "not in the impact analysis; scope expansion needs a human decision"));
        }
    }

    /** Migrations may be SQL or code (e.g. Java migrations); statements are matched across line breaks. */
    private static void evaluateMigration(String path, boolean existing, String text, List<PolicyFinding> findings) {
        if (existing) {
            findings.add(finding("CC-01", path, MIGRATION.matches(Path.of(path))
                    ? "migrations that may already be applied are immutable; add a new version instead"
                    : "existing SQL resources may already have run against a database; add a new migration instead"));
        } else {
            findings.add(finding("CC-02", path, "schema change requires approval before it is applied"));
        }
        String sql = (path.endsWith(".sql") ? withoutLeadingComments(text) : text).replaceAll("\\s+", " ");
        Matcher column = SQL_COLUMN_DEFINITION.matcher(sql);
        while (column.find()) {
            if (PersonalData.isRawPersonalDataColumn(column.group(1))) {
                findings.add(finding("CMP-01", path, "column stores raw personal data: " + column.group()
                        + " (store a keyed hash instead)"));
            }
        }
        for (String statement : sql.split(";")) {
            if (DESTRUCTIVE_SQL.matcher(statement).find()) {
                findings.add(finding("CC-03", path, "destructive statement: " + abbreviate(statement.strip())));
            }
        }
    }

    /**
     * Process execution and personal data in logs, checked per added Java statement (whitespace collapsed), so a
     * line break inside a call cannot split a pattern. A statement that only moved or was re-indented is not
     * added; a further copy of an existing one is. A rule the line check already reported for this file is not
     * reported twice.
     */
    private static void evaluateAddedStatements(String path, boolean mainJava, FileDelta delta, List<PolicyFinding> findings) {
        if (delta.after() == null) {
            return;
        }
        Map<String, Integer> before = new HashMap<>();
        if (delta.before() != null) {
            javaStatements(delta.before()).forEach(statement -> before.merge(statement, 1, Integer::sum));
        }
        for (String statement : javaStatements(delta.after())) {
            if (before.merge(statement, -1, Integer::sum) >= 0) {
                continue;
            }
            if (PROCESS_EXECUTION.matcher(statement).find()) {
                if (mainJava) {
                    addOnce(findings, "SEC-02", path, "added statement: " + abbreviate(statement));
                } else {
                    addOnce(findings, "SEC-05", path, "tests run on the verification host; added statement: " + abbreviate(statement));
                }
            }
            if (mainJava && logsPersonalData(statement)) {
                addOnce(findings, "CMP-02", path, "log statement includes personal data: " + abbreviate(statement));
            }
        }
    }

    /**
     * A statement can hold a whole lambda or anonymous class, so only the log call itself counts: its arguments and
     * those of the calls chained to it (log.atInfo().addArgument(...).log(...)).
     */
    private static boolean logsPersonalData(String statement) {
        Matcher log = LOG_STATEMENT.matcher(statement);
        while (log.find()) {
            int end = closingParenthesis(statement, log.end() - 1);
            Matcher chained = CHAINED_CALL.matcher(statement);
            while (chained.region(end, statement.length()).lookingAt()) {
                end = closingParenthesis(statement, chained.end() - 1);
            }
            if (PERSONAL_DATA_SOURCE.matcher(statement.substring(log.start(), end)).find()) {
                return true;
            }
        }
        return false;
    }

    /** The index after the parenthesis that closes the one at {@code open}, skipping literals. */
    private static int closingParenthesis(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                i = literalEnd(text, i, text.startsWith("\"\"\"", i) ? "\"\"\"" : String.valueOf(c));
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    private static void addOnce(List<PolicyFinding> findings, String ruleId, String path, String message) {
        if (findings.stream().noneMatch(f -> f.ruleId().equals(ruleId) && path.equals(f.path()))) {
            findings.add(finding(ruleId, path, message));
        }
    }

    /**
     * Splits Java source into statements at ';', '{' and '}' outside string, text-block and character literals,
     * with whitespace collapsed. Comments are dropped: they neither execute nor log, and an apostrophe in one
     * must not be read as the start of a literal. Separators inside parentheses (a lambda body or an array
     * initialiser passed as an argument, a for header) do not split, so a call always stays in one statement.
     */
    static List<String> javaStatements(String source) {
        String text = UNICODE_ESCAPE.matcher(source).replaceAll(m -> Matcher.quoteReplacement(
                m.group(1) + (char) Integer.parseInt(m.group(2), 16)));
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (text.startsWith("//", i)) {
                int newline = text.indexOf('\n', i);
                i = newline < 0 ? text.length() : newline;
                current.append(' ');
            } else if (text.startsWith("/*", i)) {
                int close = text.indexOf("*/", i + 2);
                i = close < 0 ? text.length() : close + 2;
                current.append(' ');
            } else if (c == '"' || c == '\'') {
                int end = literalEnd(text, i, text.startsWith("\"\"\"", i) ? "\"\"\"" : String.valueOf(c));
                current.append(text, i, end);
                i = end;
            } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) {
                addStatement(statements, current);
                i++;
            } else {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                }
                current.append(c);
                i++;
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private static int literalEnd(String text, int start, String quote) {
        int i = start + quote.length();
        while (i < text.length()) {
            if (text.charAt(i) == '\\') {
                i += 2;
            } else if (text.startsWith(quote, i)) {
                return i + quote.length();
            } else if (text.charAt(i) == '\n' && quote.length() == 1) {
                return i; // unterminated; the compiler rejects it, so do not let it swallow the rest of the file
            } else {
                i++;
            }
        }
        return text.length();
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().replaceAll("\\s+", " ").strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    /**
     * CC-10 is decided on the parsed configuration, not on lines, so nesting, dotted keys, relaxed spelling or a
     * further document cannot hide a key. Documents are compared by position; a key counts when it was added,
     * removed or changed, and also when its value refers (${...}) to a property that changed, or when the
     * activation condition of a document holding such keys changed.
     */
    private static void evaluateConfiguration(String path, FileDelta delta, List<PolicyFinding> findings) {
        List<Map<String, String>> before;
        List<Map<String, String>> after;
        try {
            before = parseConfiguration(path, delta.before());
            after = parseConfiguration(path, delta.after());
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            findings.add(finding("CC-10", path, "configuration cannot be parsed, so its effect cannot be checked: " + abbreviate(reason)));
            return;
        }
        List<Set<String>> changedPerDocument = new ArrayList<>();
        Set<String> changedNames = new HashSet<>();
        for (int i = 0; i < Math.max(before.size(), after.size()); i++) {
            Map<String, String> was = i < before.size() ? before.get(i) : Map.of();
            Map<String, String> now = i < after.size() ? after.get(i) : Map.of();
            Set<String> changed = new TreeSet<>();
            Stream.concat(was.keySet().stream(), now.keySet().stream())
                    .filter(key -> !Objects.equals(was.get(key), now.get(key)))
                    .forEach(changed::add);
            changedPerDocument.add(changed);
            changed.forEach(key -> changedNames.add(configKey(key)));
        }
        Set<String> reasons = new TreeSet<>();
        Map<String, List<String>> valuesBefore = valuesByName(before);
        Map<String, List<String>> valuesAfter = valuesByName(after);
        for (int i = 0; i < changedPerDocument.size(); i++) {
            Map<String, String> was = i < before.size() ? before.get(i) : Map.of();
            Map<String, String> now = i < after.size() ? after.get(i) : Map.of();
            for (String key : changedPerDocument.get(i)) {
                if (isSchemaOrLoadingKey(key)) {
                    reasons.add(key);
                }
            }
            // A JDBC URL can be assembled from other properties of the file, so it is judged with its placeholders
            // resolved, and counts when that resolved value changed.
            for (Map.Entry<String, String> entry : now.entrySet()) {
                String resolved = resolve(entry.getValue(), valuesAfter);
                String previous = was.containsKey(entry.getKey()) ? resolve(was.get(entry.getKey()), valuesBefore) : null;
                if (JDBC_INIT_SCRIPT.matcher(resolved).find() && !resolved.equals(previous)) {
                    reasons.add(entry.getKey() + " (JDBC URL runs an INIT script)");
                }
            }
            List<Map.Entry<String, String>> guarded = Stream.concat(was.entrySet().stream(), now.entrySet().stream())
                    .filter(entry -> isSchemaOrLoadingKey(entry.getKey())).toList();
            if (!guarded.isEmpty()) {
                changedPerDocument.get(i).stream().filter(key -> configKey(key).startsWith("spring.config.activate."))
                        .forEach(key -> reasons.add(key + " (activates " + guarded.getFirst().getKey() + ")"));
            }
            for (Map.Entry<String, String> entry : guarded) {
                Matcher placeholder = PLACEHOLDER.matcher(entry.getValue());
                while (placeholder.find()) {
                    String name = configKey(placeholder.group(1).strip());
                    if (changedNames.stream().anyMatch(changed -> changed.equals(name) || changed.startsWith(name + "."))) {
                        reasons.add(entry.getKey() + " (via ${" + placeholder.group(1).strip() + "})");
                    }
                }
            }
        }
        if (!reasons.isEmpty()) {
            findings.add(finding("CC-10", path, "schema management or configuration loading changed: " + String.join(", ", reasons)));
        }
    }

    /** Every value of each (normalised) property name, over all documents of a file. */
    private static Map<String, List<String>> valuesByName(List<Map<String, String>> documents) {
        Map<String, List<String>> values = new HashMap<>();
        documents.forEach(document -> document.forEach((key, value) ->
                values.computeIfAbsent(configKey(key), name -> new ArrayList<>()).add(value)));
        return values;
    }

    /**
     * Replaces each ${name:default} with every value the file gives the property (any document may be the active
     * one) and the default, so a pattern found in the result may be reachable at runtime. Names the file does not
     * define (environment variables) resolve to their default only.
     */
    private static String resolve(String value, Map<String, List<String>> values) {
        String resolved = value;
        // Bounded, because a property may refer to itself.
        for (int round = 0; round < 10 && resolved.length() < 100_000; round++) {
            Matcher placeholder = INNERMOST_PLACEHOLDER.matcher(resolved);
            if (!placeholder.find()) {
                break;
            }
            resolved = placeholder.replaceAll(m -> {
                List<String> candidates = new ArrayList<>(values.getOrDefault(configKey(m.group(1).strip()), List.of()));
                if (m.group(2) != null) {
                    candidates.add(m.group(2));
                }
                return Matcher.quoteReplacement(String.join(" ", candidates));
            });
        }
        return resolved;
    }

    /** One flattened property map (dotted keys, list indices as [n]) per document; none for an absent file. */
    private static List<Map<String, String>> parseConfiguration(String path, String text) {
        if (text == null) {
            return List.of();
        }
        List<Map<String, String>> documents = new ArrayList<>();
        if (path.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            Properties properties = new Properties();
            try {
                properties.loadFromXML(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Map<String, String> flat = new TreeMap<>();
            properties.stringPropertyNames().forEach(key -> flat.put(key, properties.getProperty(key)));
            return List.of(flat);
        }
        if (path.toLowerCase(Locale.ROOT).endsWith(".properties")) {
            for (String document : PROPERTIES_DOCUMENT_SEPARATOR.split(text, -1)) {
                Properties properties = new Properties();
                try {
                    properties.load(new StringReader(document));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                Map<String, String> flat = new TreeMap<>();
                properties.stringPropertyNames().forEach(key -> flat.put(key, properties.getProperty(key)));
                documents.add(flat);
            }
            return documents;
        }
        // Jackson reads an alias as its anchor's name rather than the anchored content, so the parsed tree would
        // not be what Spring binds.
        try (JsonParser parser = Json.YAML.createParser(text)) {
            while (parser.nextToken() != null) {
                if (parser instanceof YAMLParser yaml && yaml.isCurrentAlias()) {
                    throw new IllegalArgumentException("YAML aliases are not supported in application configuration");
                }
            }
        }
        try (MappingIterator<JsonNode> values = YAML_DOCUMENTS.readValues(text)) {
            for (JsonNode document : values.readAll()) {
                Map<String, String> flat = new TreeMap<>();
                if (document != null) {
                    flatten("", document, flat);
                }
                documents.add(flat);
            }
        }
        return documents;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.put(prefix, "{}");
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (property.getKey().equals("<<")) {
                    // A YAML merge key contributes its entries to the enclosing mapping.
                    if (property.getValue().isArray()) {
                        property.getValue().forEach(merged -> flatten(prefix, merged, out));
                    } else {
                        flatten(prefix, property.getValue(), out);
                    }
                } else {
                    flatten(prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey(), property.getValue(), out);
                }
            }
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.put(prefix, "[]");
            }
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
        } else {
            out.put(prefix, node.isNull() ? "" : node.asString());
        }
    }

    private static boolean isSchemaOrLoadingKey(String key) {
        String normalised = configKey(key);
        return JPA_SCHEMA_GENERATION.matcher(normalised).matches() || CONNECTION_INIT_SQL.matcher(normalised).matches()
                || SCHEMA_OR_LOADING_KEYS.stream()
                .anyMatch(guarded -> normalised.equals(guarded) || normalised.startsWith(guarded + "."));
    }

    /** Spring's relaxed binding: case, '-' and '_' do not matter; [n] and [key] are further path elements. */
    private static String configKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replaceAll("[\\[\\]\"']", ".")
                .replaceAll("\\.{2,}", ".").replaceAll("^\\.|\\.$", "");
    }

    /**
     * Removes SQL comments, but only those before the first quote character. After a quote, "--" or "/*" may be
     * inside a string literal (and dialects disagree on escapes), so removing it could hide the statement that
     * follows; comments there are kept, which can only add findings. For the same reason "--" not followed by
     * whitespace and MySQL's executable "/*!" comments are kept.
     */
    private static String withoutLeadingComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`' || c == '$') {
                return out.append(sql, i, sql.length()).toString();
            }
            int end = -1;
            if (sql.startsWith("--", i) && (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2)))) {
                int newline = sql.indexOf('\n', i);
                end = newline < 0 ? sql.length() : newline;
            } else if (sql.startsWith("/*", i) && !sql.startsWith("/*!", i)) {
                int close = sql.indexOf("*/", i + 2);
                end = close < 0 ? -1 : close + 2;
            }
            if (end < 0) {
                out.append(c);
                i++;
            } else {
                out.append(' ');
                i = end;
            }
        }
        return out.toString();
    }

    private static boolean inScope(List<String> scope, Path path) {
        return scope.stream().anyMatch(pattern -> glob(pattern).matches(path));
    }

    private static boolean isLowRiskClaim(String declaredRisk) {
        return declaredRisk != null && (declaredRisk.equalsIgnoreCase("LOW") || declaredRisk.equalsIgnoreCase("NONE"));
    }

    private static List<String> addedLines(FileDelta delta) {
        if (delta.after() == null) {
            return List.of();
        }
        Set<String> before = delta.before() == null ? Set.of() : new HashSet<>(delta.before().lines().toList());
        return delta.after().lines().filter(line -> !before.contains(line)).toList();
    }

    private static int removedLineCount(FileDelta delta) {
        if (delta.before() == null) {
            return 0;
        }
        Set<String> after = delta.after() == null ? Set.of() : new HashSet<>(delta.after().lines().toList());
        return (int) delta.before().lines().filter(line -> !after.contains(line)).count();
    }

    private static String abbreviate(String text) {
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    private static String redact(String line) {
        String stripped = line.strip();
        return stripped.length() <= 12 ? "****" : stripped.substring(0, 12) + "****";
    }

    private static Pattern processExecution(String harmlessRuntimeQuery) {
        return Pattern.compile("Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)(?!" + harmlessRuntimeQuery + ")"
                + "|\\.\\s*exec\\s*\\(|\\bProcessBuilder\\b|\\bScriptEngineManager\\b|\\bURLClassLoader\\b"
                + "|\\bSystem\\s*\\.\\s*load(?:Library)?\\s*\\(");
    }

    private static PathMatcher glob(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    private static PolicyFinding finding(String ruleId, String path, String message) {
        PolicyRule rule = RULES.stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow();
        return new PolicyFinding(rule.id(), rule.category(), rule.decision(), path, message);
    }
}
