package com.example.sdlc.codebase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {

    private final ArchitectureRules rules = new ArchitectureRules();

    @Test
    void wellLayeredModuleHasNoViolations() throws Exception {
        CodebaseModel model = new CodebaseIndexer().index(IndexerTest.fixture("fixture-module"));

        assertThat(rules.check(model)).isEmpty();
    }

    @Test
    void reportsEachForbiddenDependencyOfTheViolatingModule() throws Exception {
        CodebaseModel model = new CodebaseIndexer().index(IndexerTest.fixture("violating-module"));

        // Order's Javadoc names OrderController, and a test uses OrderRepository: neither is reported.
        assertThat(rules.check(model)).containsExactly(
                new LayerViolation("com.acme.shop.domain.Order", Layer.DOMAIN,
                        "com.acme.shop.service.OrderService", Layer.SERVICE, "DOMAIN must not depend on SERVICE"),
                new LayerViolation("com.acme.shop.persistence.OrderRepository", Layer.PERSISTENCE,
                        "com.acme.shop.service.OrderService", Layer.SERVICE, "PERSISTENCE must not depend on SERVICE"),
                new LayerViolation("com.acme.shop.web.OrderController", Layer.API,
                        "com.acme.shop.persistence.OrderRepository", Layer.PERSISTENCE, "API must not depend on PERSISTENCE"));
    }

    @Test
    void checksConstructedModelsAndExemptsTests() {
        CodebaseModel model = new CodebaseModel(List.of(
                unit("a.Repo", Layer.PERSISTENCE, false, "a.Web", "a.Entity"),
                unit("a.Entity", Layer.DOMAIN, false, "a.Web", "a.Repo", "a.Config", "a.RepoFake", "x.NotInModel"),
                unit("a.Web", Layer.API, false, "a.Service", "a.Entity"),
                unit("a.Service", Layer.SERVICE, false, "a.Repo", "a.Web"),
                unit("a.Config", Layer.CONFIG, false, "a.Repo", "a.Web"),
                unit("a.Other", Layer.OTHER, false, "a.Repo"),
                // Test units carry a constrained layer here, so only the test flag keeps them out of the report.
                unit("a.WebTest", Layer.API, true, "a.Repo"),
                unit("a.RepoFake", Layer.PERSISTENCE, true)),
                List.of(), List.of());

        assertThat(rules.check(model))
                .extracting(v -> v.fromType() + " -> " + v.toType() + ": " + v.rule())
                .containsExactly(
                        "a.Entity -> a.Repo: DOMAIN must not depend on PERSISTENCE",
                        "a.Entity -> a.Web: DOMAIN must not depend on API",
                        "a.Repo -> a.Web: PERSISTENCE must not depend on API");
    }

    private static SourceUnit unit(String typeName, Layer layer, boolean test, String... dependsOn) {
        return new SourceUnit(typeName, typeName.substring(typeName.lastIndexOf('.') + 1), typeName.replace('.', '/') + ".java",
                layer, test, Set.of(dependsOn), Set.of(), Set.of(), List.of(), List.of());
    }
}
