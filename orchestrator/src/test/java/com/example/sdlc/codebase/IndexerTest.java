package com.example.sdlc.codebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.sdlc.Json;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

class IndexerTest {

    private static final String PKG = "com.acme.links.";

    private static CodebaseModel model;

    @BeforeAll
    static void indexFixture() throws URISyntaxException {
        model = new CodebaseIndexer().index(fixture("fixture-module"));
    }

    static Path fixture(String name) throws URISyntaxException {
        return Path.of(IndexerTest.class.getResource("/" + name).toURI());
    }

    private static SourceUnit unit(String simpleName) {
        return model.unit(simpleName).orElseThrow();
    }

    @Test
    void indexesEveryTopLevelTypeWithItsLayer() {
        assertThat(model.units()).extracting(SourceUnit::typeName, SourceUnit::layer).containsExactly(
                tuple(PKG + "LinksApplication", Layer.CONFIG),
                tuple(PKG + "api.LinkController", Layer.API),
                tuple(PKG + "api.LinkControllerIT", Layer.TEST),
                tuple(PKG + "api.RedirectController", Layer.API),
                tuple(PKG + "api.RedirectIT", Layer.TEST),
                tuple(PKG + "api.ShortenRequest", Layer.API),
                tuple(PKG + "config.ShortenerProperties", Layer.CONFIG),
                tuple(PKG + "domain.CodeGenerator", Layer.DOMAIN),
                tuple(PKG + "domain.CodeGeneratorTest", Layer.TEST),
                tuple(PKG + "domain.Link", Layer.DOMAIN),
                tuple(PKG + "domain.LinkNotFoundException", Layer.DOMAIN),
                tuple(PKG + "domain.LinkStats", Layer.DOMAIN),
                tuple(PKG + "persistence.ClickRepository", Layer.PERSISTENCE),
                tuple(PKG + "persistence.JdbcClickRepository", Layer.PERSISTENCE),
                tuple(PKG + "persistence.LinkRepository", Layer.PERSISTENCE),
                tuple(PKG + "service.LinkService", Layer.SERVICE),
                tuple(PKG + "service.LinkServiceTest", Layer.TEST));
    }

    @Test
    void recordsPathTestFlagAndTypeAnnotations() {
        SourceUnit controller = unit("LinkController");
        assertThat(controller.simpleName()).isEqualTo("LinkController");
        assertThat(controller.path()).isEqualTo("src/main/java/com/acme/links/api/LinkController.java");
        assertThat(controller.test()).isFalse();
        assertThat(controller.annotations()).containsExactly("RestController", "RequestMapping");

        SourceUnit test = unit("LinkServiceTest");
        assertThat(test.test()).isTrue();
        assertThat(test.path()).isEqualTo("src/test/java/com/acme/links/service/LinkServiceTest.java");
        assertThat(test.annotations()).isEmpty();
    }

    @Test
    void resolvesDependenciesThroughSamePackageExplicitAndWildcardImports() {
        assertThat(unit("LinkService").dependsOn()).containsExactly(
                PKG + "domain.CodeGenerator",
                PKG + "domain.Link",
                PKG + "domain.LinkNotFoundException",
                PKG + "domain.LinkStats",
                PKG + "persistence.ClickRepository",
                PKG + "persistence.LinkRepository");
        assertThat(unit("LinkController").dependsOn()).containsExactly(
                PKG + "api.ShortenRequest", PKG + "domain.Link", PKG + "domain.LinkStats", PKG + "service.LinkService");
        assertThat(unit("JdbcClickRepository").dependsOn()).containsExactly(PKG + "persistence.ClickRepository");
        assertThat(unit("LinkControllerIT").dependsOn()).containsExactly(PKG + "api.LinkController", PKG + "api.ShortenRequest");
        assertThat(unit("CodeGeneratorTest").dependsOn()).containsExactly(PKG + "domain.CodeGenerator");
        assertThat(unit("LinksApplication").dependsOn()).as("self references are ignored").isEmpty();
    }

    @Test
    void typeNamesInCommentsAndStringLiteralsAreNotDependencies() {
        // RedirectController is in scope (same package) but only named in a comment and a string;
        // LinkRepository is named fully qualified, in Javadoc.
        assertThat(unit("LinkController").dependsOn())
                .doesNotContain(PKG + "api.RedirectController", PKG + "persistence.LinkRepository");
        // CodeGenerator's Javadoc links to LinkStats in the same package.
        assertThat(unit("CodeGenerator").dependsOn()).isEmpty();
    }

    @Test
    void resolvesSimpleNamesTheWayJavaScopesThem(@TempDir Path module) throws IOException {
        write(module, "src/main/java/demo/model/List.java", "package demo.model; public class List {}");
        write(module, "src/main/java/demo/model/Entry.java", "package demo.model; public class Entry {}");
        write(module, "src/main/java/demo/model/Outer.java",
                "package demo.model; public class Outer { public static class Inner {} public static final int MAX = 1; }");
        write(module, "src/main/java/demo/model/Holder.java", """
                package demo.model;

                import java.util.List;
                import java.util.Map.Entry;

                public class Holder {
                    List<String> names;
                    Entry<String, String> pair;
                    HolderTest invisibleFromMain;
                }
                """);
        write(module, "src/main/java/demo/app/Consumer.java", """
                package demo.app;

                import static demo.model.Outer.MAX;

                import demo.model.*;
                import demo.model.Outer.Inner;
                import java.util.List;

                class Consumer {
                    List<Inner> inners;
                    int max = MAX;
                }
                """);
        write(module, "src/main/java/demo/app/Qualified.java", "package demo.app; class Qualified { demo.model.Holder holder; }");
        write(module, "src/test/java/demo/model/HolderTest.java", "package demo.model; class HolderTest { Holder holder; List list; }");

        CodebaseModel scoped = new CodebaseIndexer().index(module);

        // Single-type imports of library types hide same-package and on-demand project types of that name,
        // and main code cannot see test types.
        assertThat(scoped.unit("Holder").orElseThrow().dependsOn()).isEmpty();
        // Nested-type and static imports count as the top-level type.
        assertThat(scoped.unit("Consumer").orElseThrow().dependsOn()).containsExactly("demo.model.Outer");
        assertThat(scoped.unit("Qualified").orElseThrow().dependsOn()).containsExactly("demo.model.Holder");
        assertThat(scoped.unit("HolderTest").orElseThrow().dependsOn()).containsExactly("demo.model.Holder", "demo.model.List");
    }

    @Test
    void classifiesSpringStereotypesAndNonClassTypes(@TempDir Path module) throws IOException {
        write(module, "src/main/java/demo/Clock.java", "package demo; @Component class Clock {}");
        write(module, "src/main/java/demo/Errors.java", "package demo; @RestControllerAdvice class Errors {}");
        write(module, "src/main/java/demo/App.java", "package demo; @SpringBootApplication class App {}");
        write(module, "src/main/java/demo/Wiring.java", "package demo; @Configuration class Wiring {}");
        write(module, "src/main/java/demo/Port.java", "package demo; interface Port {}");
        write(module, "src/main/java/demo/Marker.java", "package demo; @interface Marker {}");
        write(module, "src/main/java/demo/Status.java", "package demo; enum Status { NEW }");

        assertThat(new CodebaseIndexer().index(module).units()).extracting(SourceUnit::simpleName, SourceUnit::layer)
                .containsExactly(
                        tuple("App", Layer.CONFIG),
                        tuple("Clock", Layer.SERVICE),
                        tuple("Errors", Layer.API),
                        tuple("Marker", Layer.OTHER),
                        tuple("Port", Layer.OTHER),
                        tuple("Status", Layer.DOMAIN),
                        tuple("Wiring", Layer.CONFIG));
    }

    @Test
    void extractsEndpointsWithClassPrefixAndNormalisedPathPatterns() {
        assertThat(model.endpoints()).containsExactly(
                new Endpoint("POST", "/api/links", PKG + "api.LinkController", "shorten"),
                new Endpoint("DELETE", "/api/links/{code}", PKG + "api.LinkController", "delete"),
                new Endpoint("GET", "/api/links/{code}", PKG + "api.LinkController", "resolve"),
                new Endpoint("GET", "/api/links/{code}/stats", PKG + "api.LinkController", "stats"),
                new Endpoint("GET", "/go/{code}", PKG + "api.RedirectController", "redirect"),
                new Endpoint("GET", "/r/{code}", PKG + "api.RedirectController", "redirect"));
    }

    @Test
    void extractsEndpointsFromArraysConstantsAndMethodlessRequestMapping(@TempDir Path module) throws IOException {
        write(module, "src/main/java/demo/web/ItemController.java", """
                package demo.web;

                @RestController
                @RequestMapping({"/v1", "/v2"})
                class ItemController {

                    @GetMapping({"", "/list"})
                    List<Item> list() { return List.of(); }

                    @GetMapping(Paths.ITEMS + "/{id}")
                    Item item(@PathVariable long id) { return null; }

                    @RequestMapping(path = "/sync", method = {RequestMethod.PUT, RequestMethod.POST})
                    void sync() { }

                    @RequestMapping("/any")
                    String any() { return ""; }
                }
                """);

        // An unresolved constant keeps its source text instead of silently vanishing from the path.
        assertThat(new CodebaseIndexer().index(module).endpoints()).extracting(Endpoint::route).containsExactly(
                "GET /v1", "GET /v1/Paths.ITEMS/{id}", "ANY /v1/any", "GET /v1/list", "POST /v1/sync", "PUT /v1/sync",
                "GET /v2", "GET /v2/Paths.ITEMS/{id}", "ANY /v2/any", "GET /v2/list", "POST /v2/sync", "PUT /v2/sync");
    }

    @Test
    void mergesColumnsAddedByLaterMigrations() {
        String v1 = "src/main/resources/db/migration/V1__create_links_and_clicks.sql";
        assertThat(model.tables()).containsExactly(
                new Table("clicks", List.of("id", "link_id", "clicked_at"), v1),
                new Table("links", List.of("id", "code", "target_url", "created_at", "hit_count"), v1));
    }

    @Test
    void detectsTableAccessInTextBlocksAndConcatenatedStrings() {
        assertThat(unit("LinkRepository").tablesRead()).containsExactly("links");
        assertThat(unit("LinkRepository").tablesWritten()).containsExactly("links");
        // "JOIN links" sits in a literal of its own, concatenated to the SELECT.
        assertThat(unit("JdbcClickRepository").tablesRead()).containsExactly("clicks", "links");
        assertThat(unit("JdbcClickRepository").tablesWritten()).containsExactly("clicks");
        assertThat(unit("LinkControllerIT").tablesRead()).containsExactly("links");
        assertThat(unit("LinkControllerIT").tablesWritten()).isEmpty();
        assertThat(unit("LinkService").touchesTables()).isFalse();
    }

    @Test
    void tableAccessIgnoresProseUnknownTablesAndDeleteSources(@TempDir Path module) throws IOException {
        write(module, "src/main/resources/db/migration/V1__init.sql", """
                CREATE TABLE ${schema}.orders (id BIGINT, status TEXT);
                CREATE TABLE "Audit" (id BIGINT);
                CREATE TABLE archive (id BIGINT);
                """);
        write(module, "src/main/java/demo/OrderStore.java", """
                package demo;

                class OrderStore {
                    void purge() {
                        jdbc.update("DELETE FROM orders WHERE status = 'DONE'");
                    }

                    void touch() {
                        jdbc.update("update public.\\"AUDIT\\" a set id = ? where a.id = ?");
                    }

                    void list() {
                        jdbc.query("SELECT * FROM invoices JOIN archive r ON r.id = invoices.id");
                    }

                    void fail() {
                        throw new IllegalStateException("could not update archive");
                    }
                }
                """);

        CodebaseModel sql = new CodebaseIndexer().index(module);

        assertThat(sql.tables()).extracting(Table::name, Table::columns).containsExactly(
                tuple("archive", List.of("id")), tuple("audit", List.of("id")), tuple("orders", List.of("id", "status")));
        SourceUnit store = sql.unit("OrderStore").orElseThrow();
        assertThat(store.tablesWritten()).containsExactly("audit", "orders");
        // DELETE FROM is not a read, invoices is not a known table, and the exception message is not an UPDATE.
        assertThat(store.tablesRead()).containsExactly("archive");
    }

    @Test
    void recordsUrlPathLiteralsNormalisedLikeEndpointPaths() {
        assertThat(unit("RedirectIT").pathLiterals()).containsExactly("/go/abcd", "/r/{code}");
        assertThat(unit("LinkControllerIT").pathLiterals()).containsExactly("/api/links");
        assertThat(unit("LinkController").pathLiterals()).containsExactly("/api/links", "/{code}", "/{code}/stats");
        assertThat(unit("LinkService").pathLiterals()).isEmpty();
    }

    @Test
    void pathLiteralsComeFromStringsAndTextBlocksButNotFromUrlsOrProse(@TempDir Path module) throws IOException {
        write(module, "src/test/java/demo/ItemsIT.java", """
                package demo;

                class ItemsIT {
                    void requests() throws Exception {
                        mvc.perform(get("/items/{id}", 7));
                        mvc.perform(get("/items/7?expand=true#top"));
                        mvc.perform(post("/items/").content("{\\"name\\": \\"/not/a/path\\"}"));
                        mvc.perform(get(\"""
                                /items/{id}/history
                                \""", 7));
                        mvc.perform(get("/v1" + "/items"));
                        String cdn = "//cdn.example.org/logo.png";
                        String absolute = "https://example.org/items";
                        String relative = "items/7";
                        String prose = "/ is the root";
                    }
                }
                """);

        assertThat(new CodebaseIndexer().index(module).unit("ItemsIT").orElseThrow().pathLiterals())
                .containsExactly("/items", "/items/7", "/items/{id}", "/items/{id}/history", "/v1/items");
    }

    @Test
    void nonLiteralOperandsAndFormatArgumentsInPathsStandForVariables(@TempDir Path module) throws IOException {
        write(module, "src/test/java/demo/OrdersIT.java", """
                package demo;

                class OrdersIT {
                    void requests(String code, Order order, int port) throws Exception {
                        mvc.perform(get("/" + code));
                        mvc.perform(get("/orders/" + order.code() + "/lines?page=" + page));
                        mvc.perform(get("/carts/%s/items/%d".formatted(code, 3)));
                        mvc.perform(get("/users/" + code, "/other"));
                        rest.getForEntity("http://localhost:" + port + "/health", String.class);
                    }
                }
                """);

        // The chains without operands still add their pieces ("/orders", "/lines"), as before.
        assertThat(new CodebaseIndexer().index(module).unit("OrdersIT").orElseThrow().pathLiterals()).containsExactly(
                "/", "/carts/{}/items/{}", "/health", "/lines", "/orders", "/orders/{}/lines", "/other", "/users",
                "/users/{}", "/{}");
    }

    @Test
    void operandsDoNotChangeTheSqlSeenInLiterals() {
        JavaSource source = JavaSource.parse("""
                class Repo {
                    String sql = "SELECT * FROM " + table + " WHERE id = ?";
                    void run() { jdbc.update("DELETE FROM a"); log("done " + count); jdbc.query("SELECT 1"); }
                }
                """);
        int end = source.types().getFirst().end();

        assertThat(source.literalChains(0, end))
                .containsExactly("SELECT * FROM ", " WHERE id = ?", "DELETE FROM a", "done ", "SELECT 1");
        assertThat(source.literalChains(0, end, "{}"))
                .containsExactly("SELECT * FROM {} WHERE id = ?", "DELETE FROM a", "done {}", "SELECT 1");
    }

    @Test
    void modelsSerializedBeforePathLiteralsStillLoad() {
        ObjectNode json = (ObjectNode) Json.tree(unit("RedirectIT"));
        json.remove("pathLiterals");

        assertThat(Json.convert(json, SourceUnit.class).pathLiterals()).isEmpty();
    }

    @Test
    void normalisesSpringPathPatterns() {
        assertThat(Endpoint.normalizePath("")).isEqualTo("/");
        assertThat(Endpoint.normalizePath("/api//links/")).isEqualTo("/api/links");
        assertThat(Endpoint.normalizePath("/{code:[A-Za-z0-9_-]{4,32}}/x")).isEqualTo("/{code}/x");
        assertThat(Endpoint.normalizePath("files/{*path}")).isEqualTo("/files/{*path}");
        assertThat(Endpoint.normalizePath("/broken/{oops")).isEqualTo("/broken/{oops");
    }

    @Test
    void answersModelQueries() {
        assertThat(model.unit(PKG + "domain.Link")).map(SourceUnit::simpleName).contains("Link");
        assertThat(model.unit("Nope")).isEmpty();
        assertThat(model.dependentsOf(PKG + "service.LinkService")).extracting(SourceUnit::simpleName)
                .containsExactly("LinkController", "RedirectController", "LinkServiceTest");
        assertThat(model.summary())
                .startsWith("17 units (API 3, SERVICE 1, DOMAIN 4, PERSISTENCE 3, CONFIG 2, TEST 4), 6 endpoints, 2 tables")
                .contains("\n  GET /api/links/{code} -> LinkController#resolve")
                .endsWith("\n  links(id, code, target_url, created_at, hit_count)");
    }

    @Test
    void moduleWithoutSourcesYieldsEmptyModel(@TempDir Path dir) {
        CodebaseModel empty = new CodebaseIndexer().index(dir.resolve("does-not-exist"));

        assertThat(empty.units()).isEmpty();
        assertThat(empty.endpoints()).isEmpty();
        assertThat(empty.tables()).isEmpty();
    }

    @Test
    void toleratesMalformedAndUnusualFiles(@TempDir Path module) throws IOException {
        write(module, "src/main/java/demo/Tricky.java", """
                package demo;

                import demo.model.*;

                public class Tricky {
                    private static final char OPEN = '{';
                    private static final String URL = "http://example.org/*not-a-comment*/";
                    private static final String BLOCK = \"""
                        he said "}" and \\\""" is still inside
                        \""";
                    Widget widget;
                }

                final class Second implements Runnable {
                    public void run() { new Gadget(); }

                    static class Nested { }
                }
                """);
        write(module, "src/main/java/demo/model/Widget.java", "package demo.model; public record Widget(int size) {}");
        write(module, "src/main/java/demo/model/Gadget.java", "package demo.model;\npublic class Gadget {}");
        write(module, "src/main/java/demo/Broken.java", "package demo; /* never closed class Ghost {");
        write(module, "src/main/java/demo/Empty.java", "");
        Files.createDirectories(module.resolve("src/main/java/demo"));
        Files.write(module.resolve("src/main/java/demo/Binary.java"), new byte[] {(byte) 0xFF, 0, (byte) 0xC3, '{', '"', 0x7F});
        write(module, "src/main/resources/db/migration/V1__init.sql",
                "CREATE TABLE t (a INT, note VARCHAR(10) DEFAULT 'x,y;z');");
        write(module, "src/main/resources/db/migration/V2__add.sql", "ALTER TABLE t ADD b INT;");
        write(module, "src/main/resources/db/migration/V3__garbage.sql", "CREATE TABLE ( ;; ALTER TABLE; DROP");
        write(module, "src/main/resources/db/migration/V4__temp.sql", "CREATE TABLE tmp (x INT); DROP TABLE tmp;");
        // Numerically after V2 although it sorts before it as text; the rename only works in that order.
        write(module, "src/main/resources/db/migration/V10__rename.sql",
                "ALTER TABLE t RENAME COLUMN b TO c; ALTER TABLE ghost ADD COLUMN z INT;");
        write(module, "src/main/resources/db/migration/notes.sql", "CREATE TABLE ignored (x INT);");

        CodebaseModel odd = new CodebaseIndexer().index(module);

        assertThat(odd.units()).extracting(SourceUnit::typeName)
                .containsExactly("demo.Second", "demo.Tricky", "demo.model.Gadget", "demo.model.Widget");
        assertThat(odd.unit("Tricky").orElseThrow().dependsOn()).containsExactly("demo.model.Widget");
        assertThat(odd.unit("Second").orElseThrow().dependsOn()).containsExactly("demo.model.Gadget");
        assertThat(odd.tables()).containsExactly(
                new Table("t", List.of("a", "note", "c"), "src/main/resources/db/migration/V1__init.sql"));
    }

    @Test
    void neverThrowsOnTruncatedSources(@TempDir Path dir) throws Exception {
        Path fixture = fixture("fixture-module");
        Map<String, String> samples = Map.of(
                "src/main/java/com/acme/links/api/LinkController.java", "src/main/java/Cut.java",
                "src/main/java/com/acme/links/persistence/LinkRepository.java", "src/main/java/Cut.java",
                "src/main/resources/db/migration/V1__create_links_and_clicks.sql", "src/main/resources/db/migration/V1__cut.sql");
        int modules = 0;
        for (var sample : samples.entrySet()) {
            String text = Files.readString(fixture.resolve(sample.getKey()));
            for (int cut = 0; cut <= text.length(); cut += 7) {
                Path module = dir.resolve("m" + modules++);
                write(module, sample.getValue(), text.substring(0, cut));
                write(module, "src/test/java/CutTest.java", text.substring(cut));

                assertThatCode(() -> new ImpactAnalyzer().analyze(new CodebaseIndexer().index(module), List.of("links", "Cut")))
                        .as("cut at %d of %s", cut, sample.getKey())
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void survivesPathologicallyLongDottedNames(@TempDir Path module) throws IOException {
        // Backtracking regex groups recurse per repetition; names this long used to overflow the stack.
        String chain = "a" + ".b".repeat(50_000);
        write(module, "src/main/java/demo/Chain.java",
                "package demo; import " + chain + "; @" + chain + " class Chain { Object o = " + chain + "; }");
        write(module, "src/main/resources/db/migration/V1__init.sql",
                "CREATE TABLE t (id INT); CREATE TABLE " + chain + " (id INT); DROP TABLE " + "x, ".repeat(50_000) + "t;");

        CodebaseModel model = new CodebaseIndexer().index(module);

        assertThat(model.units()).extracting(SourceUnit::typeName).containsExactly("demo.Chain");
        assertThat(model.tables()).extracting(Table::name).containsExactly("b");
    }

    @Test
    void skipsUnreadableDirectoriesAndSymbolicLinksButKeepsTheRest(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path module = dir.resolve("module");
        write(module, "src/main/java/demo/Kept.java", "package demo; class Kept {}");
        write(module, "src/main/java/demo/locked/Hidden.java", "package demo.locked; class Hidden {}");
        write(module, "src/main/resources/db/migration/V1__init.sql", "CREATE TABLE kept (id INT);");
        write(module, "src/main/resources/db/migration/locked/V2__more.sql", "CREATE TABLE hidden (id INT);");
        write(dir, "outside/Secret.java", "package demo; class Secret {}");
        Files.createSymbolicLink(module.resolve("src/main/java/demo/Secret.java"), dir.resolve("outside/Secret.java"));
        List<Path> locked = List.of(
                module.resolve("src/main/java/demo/locked"), module.resolve("src/main/resources/db/migration/locked"));
        for (Path directory : locked) {
            Files.setPosixFilePermissions(directory, Set.of());
        }
        try {
            CodebaseModel partial = new CodebaseIndexer().index(module);

            // The locked directories may still be readable when running as root, so only what must survive is asserted.
            assertThat(partial.units()).extracting(SourceUnit::typeName).contains("demo.Kept").doesNotContain("demo.Secret");
            assertThat(partial.tables()).extracting(Table::name).contains("kept");
        } finally {
            for (Path directory : locked) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    static void write(Path module, String relativePath, String content) throws IOException {
        Path file = module.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
