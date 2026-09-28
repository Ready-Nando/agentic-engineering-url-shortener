package com.example.sdlc.codebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImpactAnalyzerTest {

    private static final String PKG = "com.acme.links.";
    private static final String CONTROLLER = PKG + "api.LinkController";
    private static final String REDIRECT = PKG + "api.RedirectController";
    private static final String SERVICE = PKG + "service.LinkService";
    private static final String LINK_REPOSITORY = PKG + "persistence.LinkRepository";
    private static final String CLICK_PORT = PKG + "persistence.ClickRepository";
    private static final String CLICK_ADAPTER = PKG + "persistence.JdbcClickRepository";

    private static CodebaseModel model;

    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();

    @BeforeAll
    static void indexFixture() throws Exception {
        model = new CodebaseIndexer().index(IndexerTest.fixture("fixture-module"));
    }

    @Test
    void typeSeedPullsInTransitiveDependentsWithHopDistances() {
        ImpactAnalysis impact = analyzer.analyze(model, List.of("Link"));

        assertThat(impact.seeds()).containsExactly("Link");
        assertThat(impact.unresolvedSeeds()).isEmpty();
        assertThat(impact.components())
                .extracting(ImpactedComponent::typeName, ImpactedComponent::distance, ImpactedComponent::reason)
                .containsExactly(
                        tuple(PKG + "domain.Link", 0, "seed"),
                        tuple(CONTROLLER, 1, "depends on Link"),
                        tuple(LINK_REPOSITORY, 1, "depends on Link"),
                        tuple(SERVICE, 1, "depends on Link"),
                        tuple(REDIRECT, 2, "depends on LinkService"));
        assertThat(impact.components().getFirst().path()).isEqualTo("src/main/java/com/acme/links/domain/Link.java");
        assertThat(impact.components().getFirst().layer()).isEqualTo(Layer.DOMAIN);
        assertThat(impact.endpoints()).hasSize(6);
        assertThat(impact.tables()).containsExactly("links");
        assertThat(impact.tests())
                .containsExactly(PKG + "api.LinkControllerIT", PKG + "api.RedirectIT", PKG + "service.LinkServiceTest");
    }

    @Test
    void tableSeedStartsFromTheTypesTouchingIt() {
        ImpactAnalysis impact = analyzer.analyze(model, List.of("CLICKS"));

        assertThat(impact.seeds()).containsExactly("CLICKS");
        assertThat(impact.components())
                .extracting(ImpactedComponent::typeName, ImpactedComponent::distance, ImpactedComponent::reason)
                .containsExactly(
                        tuple(CLICK_ADAPTER, 1, "reads and writes clicks"),
                        tuple(CLICK_PORT, 2, "implemented by JdbcClickRepository"),
                        tuple(SERVICE, 3, "depends on ClickRepository"),
                        tuple(CONTROLLER, 4, "depends on LinkService"),
                        tuple(REDIRECT, 4, "depends on LinkService"));
        // JdbcClickRepository joins links, so links is touched too.
        assertThat(impact.tables()).containsExactly("clicks", "links");
    }

    @Test
    void endpointSeedMatchesAnyVariableNameAndImpactsItsHandler() {
        ImpactAnalysis impact = analyzer.analyze(model, List.of("get /api/links/{id}"));

        assertThat(impact.seeds()).containsExactly("get /api/links/{id}");
        assertThat(impact.components())
                .extracting(ImpactedComponent::typeName, ImpactedComponent::distance, ImpactedComponent::reason)
                .containsExactly(tuple(CONTROLLER, 0, "handles GET /api/links/{code}"));
        assertThat(impact.endpoints()).extracting(Endpoint::route).containsExactly(
                "POST /api/links", "DELETE /api/links/{code}", "GET /api/links/{code}", "GET /api/links/{code}/stats");
        assertThat(impact.tables()).isEmpty();
        assertThat(impact.tests()).containsExactly(PKG + "api.LinkControllerIT");
    }

    @Test
    void endpointSeedMayUseTheRegexConstrainedForm() {
        ImpactAnalysis impact = analyzer.analyze(model, List.of("GET /r/{code:[a-z]+}"));

        assertThat(impact.unresolvedSeeds()).isEmpty();
        assertThat(impact.components()).extracting(ImpactedComponent::typeName).containsExactly(REDIRECT);
    }

    @Test
    void unknownSeedsAreReportedAsUnresolved() {
        ImpactAnalysis impact = analyzer.analyze(model, List.of(
                "com.acme.links.service.BillingService", "invoices", "DELETE /api/orders/{id}", "POST /api/links/{code}",
                "LinkStats", "  "));

        assertThat(impact.seeds()).containsExactly("LinkStats");
        assertThat(impact.unresolvedSeeds()).containsExactly(
                "DELETE /api/orders/{id}", "POST /api/links/{code}", "com.acme.links.service.BillingService", "invoices");
    }

    @Test
    void seedsMayBeFullyQualifiedNamesOrSourcePaths() {
        ImpactAnalysis byName = analyzer.analyze(model, List.of(LINK_REPOSITORY));
        ImpactAnalysis byPath = analyzer.analyze(model, List.of("src/main/java/com/acme/links/persistence/LinkRepository.java"));

        assertThat(byName.components()).isEqualTo(byPath.components());
        assertThat(byName.components()).extracting(ImpactedComponent::typeName)
                .containsExactly(LINK_REPOSITORY, SERVICE, CONTROLLER, REDIRECT);
    }

    @Test
    void selectsOnlyTestsThatReferenceImpactedTypesOrRequestImpactedEndpoints() {
        assertThat(analyzer.analyze(model, List.of("ShortenRequest")).tests()).containsExactly(PKG + "api.LinkControllerIT");
        assertThat(analyzer.analyze(model, List.of("CodeGenerator")).testReasons()).containsExactly(
                entry(PKG + "api.LinkControllerIT", "references LinkController"),
                entry(PKG + "api.RedirectIT", "path /go/abcd matches GET /go/{code}"),
                entry(PKG + "domain.CodeGeneratorTest", "references CodeGenerator"),
                entry(PKG + "service.LinkServiceTest", "references LinkService"));
        assertThat(analyzer.analyze(model, List.of("ShortenerProperties")).tests()).isEmpty();
    }

    @Test
    void selectsTestsThatOnlyExerciseAnImpactedEndpointOverHttp() {
        ImpactAnalysis impact = analyzer.analyze(model, List.of("RedirectController"));

        // RedirectIT names no project type; LinkControllerIT posts to /api/links, which is not impacted.
        assertThat(impact.tests()).containsExactly(PKG + "api.RedirectIT");
        assertThat(impact.testReasons()).containsEntry(PKG + "api.RedirectIT", "path /go/abcd matches GET /go/{code}");
    }

    @Test
    void selectsTestsThatBuildTheRequestPathFromAVariable(@TempDir Path module) throws IOException {
        IndexerTest.write(module, "src/main/java/demo/api/RedirectController.java", """
                package demo.api;

                @RestController
                class RedirectController {
                    @GetMapping("/{code:[a-z]+}")
                    void redirect(String code) {
                    }
                }
                """);
        IndexerTest.write(module, "src/test/java/demo/api/RedirectIT.java", """
                package demo.api;

                class RedirectIT {
                    void redirects(String code) throws Exception {
                        mvc.perform(get("/" + code)).andExpect(status().isFound());
                    }
                }
                """);

        assertThat(analyzer.analyze(new CodebaseIndexer().index(module), List.of("RedirectController")).testReasons())
                .containsExactly(entry("demo.api.RedirectIT", "path /{} matches GET /{code}"));
    }

    @Test
    void pathVariablesOnEitherSideMatchAnySingleSegment() {
        CodebaseModel web = new CodebaseModel(List.of(
                unit("x.Web", Layer.API, Set.of(), Set.of(), Set.of()),
                testUnit("x.TemplateIT", "/api/links/{id}/stats"),
                testUnit("x.ConcreteIT", "/api/links/abc/stats"),
                testUnit("x.ShortCodeIT", "/abc"),
                testUnit("x.RootIT", "/"),
                testUnit("x.PrefixIT", "/api/links/abc"),
                testUnit("x.LongerIT", "/api/links/abc/stats/daily"),
                testUnit("x.OtherIT", "/api/users/abc/stats")),
                List.of(new Endpoint("GET", "/api/links/{code}/stats", "x.Web", "stats"),
                        new Endpoint("GET", "/{code:[a-z]+}", "x.Web", "redirect")),
                List.of());

        assertThat(analyzer.analyze(web, List.of("x.Web")).testReasons()).containsExactly(
                entry("x.ConcreteIT", "path /api/links/abc/stats matches GET /api/links/{code}/stats"),
                entry("x.ShortCodeIT", "path /abc matches GET /{code:[a-z]+}"),
                entry("x.TemplateIT", "path /api/links/{id}/stats matches GET /api/links/{code}/stats"));
    }

    @Test
    void dataFlowsFollowShortestPathsFromHandlerToSql() {
        ImpactAnalysis impact = analyzer.analyze(model, List.of("GET /api/links/{code}"));
        Endpoint resolve = new Endpoint("GET", "/api/links/{code}", CONTROLLER, "resolve");

        assertThat(impact.dataFlows()).hasSize(4 * 4);
        assertThat(impact.dataFlows()).filteredOn(flow -> flow.entry().equals(resolve))
                .extracting(DataFlow::table, DataFlow::access, DataFlow::path)
                .containsExactly(
                        tuple("clicks", "READ", List.of(CONTROLLER, SERVICE, CLICK_PORT, CLICK_ADAPTER)),
                        tuple("clicks", "WRITE", List.of(CONTROLLER, SERVICE, CLICK_PORT, CLICK_ADAPTER)),
                        tuple("links", "READ", List.of(CONTROLLER, SERVICE, LINK_REPOSITORY)),
                        tuple("links", "WRITE", List.of(CONTROLLER, SERVICE, LINK_REPOSITORY)));
    }

    @Test
    void dataFlowsPreferFewerHopsOverNamesAndBreakTiesByName() {
        CodebaseModel graph = new CodebaseModel(List.of(
                unit("x.Web", Layer.API, Set.of("x.Zeta", "x.Beta", "x.Alpha"), Set.of(), Set.of()),
                unit("x.Alpha", Layer.SERVICE, Set.of("x.Store", "x.Mid"), Set.of(), Set.of()),
                unit("x.Beta", Layer.SERVICE, Set.of("x.Store"), Set.of(), Set.of()),
                unit("x.Mid", Layer.SERVICE, Set.of("x.Log"), Set.of(), Set.of()),
                unit("x.Zeta", Layer.SERVICE, Set.of("x.Log"), Set.of(), Set.of()),
                unit("x.Store", Layer.PERSISTENCE, Set.of(), Set.of("orders"), Set.of()),
                unit("x.Log", Layer.PERSISTENCE, Set.of(), Set.of(), Set.of("orders"))),
                List.of(new Endpoint("GET", "/orders", "x.Web", "list")),
                List.of(new Table("orders", List.of("id"), "V1__orders.sql")));

        ImpactAnalysis impact = analyzer.analyze(graph, List.of("GET /orders"));

        // READ: Alpha and Beta tie at two hops, Alpha wins by name. WRITE: Zeta's two hops beat Alpha's three.
        assertThat(impact.dataFlows()).extracting(DataFlow::table, DataFlow::access, DataFlow::path).containsExactly(
                tuple("orders", "READ", List.of("x.Web", "x.Alpha", "x.Store")),
                tuple("orders", "WRITE", List.of("x.Web", "x.Zeta", "x.Log")));
    }

    @Test
    void resultDoesNotDependOnSeedOrder() {
        List<String> seeds = List.of("clicks", "GET /r/{code}", "Link", "nonsense");

        assertThat(analyzer.analyze(model, seeds)).isEqualTo(analyzer.analyze(model, seeds.reversed()));
    }

    private static SourceUnit unit(String typeName, Layer layer, Set<String> dependsOn, Set<String> read, Set<String> written) {
        return new SourceUnit(typeName, typeName.substring(typeName.lastIndexOf('.') + 1), typeName.replace('.', '/') + ".java",
                layer, false, dependsOn, read, written, List.of(), List.of());
    }

    private static SourceUnit testUnit(String typeName, String pathLiteral) {
        return new SourceUnit(typeName, typeName.substring(typeName.lastIndexOf('.') + 1), typeName.replace('.', '/') + ".java",
                Layer.TEST, true, Set.of(), Set.of(), Set.of(), List.of(), List.of(pathLiteral));
    }
}
