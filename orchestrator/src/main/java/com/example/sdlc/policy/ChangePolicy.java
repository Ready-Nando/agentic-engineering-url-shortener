package com.example.sdlc.policy;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.sdlc.workspace.FileDelta;

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
            new PolicyRule("CMP-01", PolicyCategory.COMPLIANCE, PolicyDecision.DENY,
                    "Schema stores raw personal data (IP address, e-mail, phone, user agent)"),
            new PolicyRule("CMP-02", PolicyCategory.COMPLIANCE, PolicyDecision.DENY,
                    "Personal data written to application logs"),
            new PolicyRule("CC-01", PolicyCategory.CHANGE_CONTROL, PolicyDecision.DENY,
                    "Already-applied database migration modified or deleted"),
            new PolicyRule("CC-02", PolicyCategory.CHANGE_CONTROL, PolicyDecision.REQUIRE_APPROVAL,
                    "New (not yet applied) database migration added or changed"),
            new PolicyRule("CC-03", PolicyCategory.CHANGE_CONTROL, PolicyDecision.DENY,
                    "Destructive schema or data operation in a migration (DROP, TRUNCATE, DELETE)"),
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
    private static final Pattern PROCESS_EXECUTION = Pattern.compile(
            "Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)(?!\\s*\\.\\s*(?:availableProcessors|freeMemory|totalMemory|maxMemory"
                    + "|addShutdownHook|removeShutdownHook)\\s*\\()"
                    + "|\\.\\s*exec\\s*\\(|\\bProcessBuilder\\b|\\bScriptEngineManager\\b|\\bURLClassLoader\\b"
                    + "|\\bSystem\\s*\\.\\s*load(?:Library)?\\s*\\(");

    // "name TYPE", also as the target of a type change ("ALTER COLUMN name [SET DATA] TYPE TYPE").
    private static final Pattern SQL_COLUMN_DEFINITION = Pattern.compile(
            "(?i)(\"[^\"]+\"|`[^`]+`|\\[[^\\]]+]|\\b[a-z_][\\w$]*)\\s+(?:SET\\s+DATA\\s+)?(?:TYPE\\s+)?"
                    + "(?:N?VARCHAR(?:2|_IGNORECASE)?|N?CHAR(?:ACTER)?|(?:TINY|MEDIUM|LONG)?TEXT|CITEXT"
                    + "|N?CLOB|INET|CIDR|VARBINARY|BINARY|(?:TINY|MEDIUM|LONG)?BLOB|BYTEA)\\b");

    private static final Pattern LOG_STATEMENT = Pattern.compile(
            "(?i)\\b\\w*log(?:ger)?\\s*\\.\\s*(?:at)?(?:trace|debug|info|warn|error)\\s*\\(");
    private static final Pattern PERSONAL_DATA_SOURCE = Pattern.compile(
            "(?i)getRemoteAddr|X-Forwarded-For|\"User-Agent\"|HttpHeaders\\.USER_AGENT|\\bemail\\b");

    // Applied to one statement at a time. Any DROP action of an ALTER TABLE counts (ONLY, IF EXISTS, quoted names,
    // several actions), except dropping a column's default or NOT NULL constraint.
    private static final Pattern DESTRUCTIVE_SQL = Pattern.compile(
            "(?i)\\b(DROP\\s+(?:TABLE|COLUMN|SCHEMA|DATABASE)|TRUNCATE|DELETE\\s+FROM)\\b"
                    + "|\\bALTER\\s+TABLE\\b.*?\\bDROP\\b(?!\\s+(?:NOT\\s+NULL|DEFAULT)\\b)");

    private static final Pattern DISABLED_TEST = Pattern.compile("@(?:[\\w.]+\\.)?(?:Disabled\\w*|Ignore)\\b");

    private static final PathMatcher MIGRATION = glob("{db/migration/**,**/db/migration/**}");
    private static final PathMatcher REQUEST_HANDLING = glob("{**/web/**,**/api/**,**/*Controller.java,**/*Validator.java,**/*Policy.java}");
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
            if (mainJava && PROCESS_EXECUTION.matcher(line).find()) {
                findings.add(finding("SEC-02", path, "added: " + line.strip()));
            }
            if (testSource && PROCESS_EXECUTION.matcher(line).find()) {
                findings.add(finding("SEC-05", path, "tests run on the verification host; added: " + line.strip()));
            }
            if (mainJava && LOG_STATEMENT.matcher(line).find() && PERSONAL_DATA_SOURCE.matcher(line).find()) {
                findings.add(finding("CMP-02", path, "log statement includes personal data: " + line.strip()));
            }
            if (testSource && DISABLED_TEST.matcher(line).find()) {
                findings.add(finding("CC-08", path, "test disabled: " + line.strip()));
            }
        }
        if (path.equals("mvnw") || path.equals("mvnw.cmd") || path.startsWith(".mvn/") || path.startsWith(".github/")) {
            findings.add(finding("SEC-03", path, "toolchain files are outside agent autonomy"));
        }
        // Keyed on the baseline, not on the operation: re-creating a file an earlier change deleted still changes it.
        if (existing && path.startsWith("src/main/java/") && REQUEST_HANDLING.matches(asPath)) {
            findings.add(finding("SEC-04", path, "security-relevant code path changed; needs reviewer sign-off"));
        }
        if (existing && testSource && delta.after() == null) {
            findings.add(finding("CC-08", path, "existing test removed; dropping coverage is a human decision"));
        }
        if (MIGRATION.matches(asPath)) {
            // A migration that is not yet applied runs as a whole, so all of it is checked: added lines alone lose
            // the context (an open string literal or comment) that decides what an edited line means.
            String text = existing || delta.after() == null ? String.join("\n", added) : delta.after();
            evaluateMigration(path, existing, text, findings);
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
            findings.add(finding("CC-01", path, "migrations that may already be applied are immutable; add a new version instead"));
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

    private static PathMatcher glob(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    private static PolicyFinding finding(String ruleId, String path, String message) {
        PolicyRule rule = RULES.stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow();
        return new PolicyFinding(rule.id(), rule.category(), rule.decision(), path, message);
    }
}
