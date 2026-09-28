package com.example.sdlc.capability;

import static com.example.sdlc.capability.CapabilityFixtures.artifact;
import static com.example.sdlc.capability.CapabilityFixtures.changes;
import static com.example.sdlc.capability.CapabilityFixtures.context;
import static com.example.sdlc.capability.CapabilityFixtures.output;
import static com.example.sdlc.capability.CapabilityFixtures.strings;
import static com.example.sdlc.capability.CapabilityFixtures.visible;
import static com.example.sdlc.capability.CapabilityFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.codebase.CodebaseIndexer;
import com.example.sdlc.workspace.Workspace;

import tools.jackson.databind.JsonNode;

/**
 * API change control over a real module: the published OpenAPI document before and after the change, the
 * controllers as the indexer sees them, and the designed contract. Each case changes one thing.
 */
class ApiReviewHandlerTest {

    private static final String CONTROLLER = "src/main/java/demo/api/LinkController.java";
    private static final String OPENAPI = WorkspaceFacts.OPENAPI;
    private static final String DELETE = "DELETE /api/links/{code}";
    private static final String STATS = "GET /api/links/{code}/stats";

    private static final String STATS_OPERATION = """
              /api/links/{code}/stats:
                get:
                  parameters:
                    - name: days
                      in: query
                      schema:
                        type: integer
                  responses:
                    '200':
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/LinkStats'
            """;

    private static final String LINK_STATS_SCHEMA = """
                LinkStats:
                  type: object
                  properties:
                    clicks:
                      type: integer
                    window:
                      type: object
                      properties:
                        from:
                          type: string
                          format: date
                        days:
                          type: integer
                    daily:
                      type: array
                      items:
                        type: object
                        properties:
                          day:
                            type: string
                          visits:
                            type: integer
            """;

    private static final String BASELINE_OPENAPI = """
            openapi: 3.0.3
            info:
              title: Links
              version: 1.0.0
            paths:
              /api/links:
                post:
                  parameters:
                    - $ref: '#/components/parameters/IdempotencyKey'
                  requestBody:
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/ShortenRequest'
                  responses:
                    '201':
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Link'
              /api/links/{code}:
                get:
                  responses:
                    '200':
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Link'
            """ + STATS_OPERATION + """
            components:
              parameters:
                IdempotencyKey:
                  name: Idempotency-Key
                  in: header
                  schema:
                    type: string
              schemas:
                ShortenRequest:
                  type: object
                  required: [targetUrl]
                  properties:
                    targetUrl:
                      type: string
                    alias:
                      type: string
                    options:
                      type: object
                      properties:
                        ttlDays:
                          type: integer
                Link:
                  type: object
                  required: [code, targetUrl]
                  properties:
                    code:
                      type: string
                    targetUrl:
                      type: string
                    createdAt:
                      type: string
                Problem:
                  type: object
                  properties:
                    title:
                      type: string
                ValidationProblem:
                  allOf:
                    - $ref: '#/components/schemas/Problem'
                    - type: object
                      properties:
                        errors:
                          type: array
                          items:
                            type: string
            """ + LINK_STATS_SCHEMA;

    private static final String BASELINE_CONTROLLER = """
            package demo.api;

            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping("/api/links")
            public class LinkController {

                @PostMapping
                public Link shorten(@RequestBody ShortenRequest request) {
                    return null;
                }

                @GetMapping("/{code}")
                public Link resolve(@PathVariable String code) {
                    return null;
                }

                @GetMapping("/{code}/stats")
                public LinkStats stats(@PathVariable String code) {
                    return null;
                }
            }
            """;

    private static final String DELETE_DOCUMENTED = """
              /api/links/{code}:
                delete:
                  responses:
                    '204':
                      description: Deleted
                get:
            """;

    private static final String DELETE_IMPLEMENTED = """
                @DeleteMapping("/{code}")
                public void delete(@PathVariable String code) {
                }
            }
            """;

    @TempDir
    Path temp;

    private Workspace workspace;
    private final ApiReviewHandler handler = new ApiReviewHandler(new CodebaseIndexer());

    @BeforeEach
    void setUp() throws Exception {
        workspace = CapabilityFixtures.workspace(temp, Map.of(OPENAPI, BASELINE_OPENAPI, CONTROLLER, BASELINE_CONTROLLER));
    }

    private void editOpenApi(String find, String replace) throws Exception {
        write(workspace, OPENAPI, replaceOnce(workspace.read(OPENAPI).orElseThrow(), find, replace));
    }

    private void editController(String find, String replace) throws Exception {
        write(workspace, CONTROLLER, replaceOnce(workspace.read(CONTROLLER).orElseThrow(), find, replace));
    }

    private void documentDelete() throws Exception {
        editOpenApi("  /api/links/{code}:\n    get:\n", DELETE_DOCUMENTED);
    }

    private void implementDelete() throws Exception {
        editController("    }\n}\n", "    }\n\n" + DELETE_IMPLEMENTED);
    }

    /** Starts over from another baseline document, for a shape the shared baseline does not have. */
    private void rebaseOpenApi(String baseline) throws Exception {
        workspace = CapabilityFixtures.workspace(temp.resolve("rebased"), Map.of(OPENAPI, baseline, CONTROLLER, BASELINE_CONTROLLER));
    }

    private static String replaceOnce(String text, String find, String replace) {
        assertThat(text).as("fixture edit anchor").containsOnlyOnce(find);
        return text.replace(find, replace);
    }

    /** A design contract with the given {@code "METHOD /path"} to change label pairs. */
    private static Artifact contract(String... operationsAndChanges) {
        List<Map<String, Object>> operations = new ArrayList<>();
        for (int i = 0; i < operationsAndChanges.length; i += 2) {
            String[] operation = operationsAndChanges[i].split(" ", 2);
            operations.add(Map.of("method", operation[0], "path", operation[1], "responses", List.of(200),
                    "change", operationsAndChanges[i + 1]));
        }
        return artifact(ArtifactKeys.API_CONTRACT, Map.of("operations", operations), "design");
    }

    private JsonNode review(Artifact... artifacts) {
        List<Artifact> visible = new ArrayList<>(List.of(artifacts));
        List<String> changed = workspace.changedPaths();
        if (!changed.isEmpty()) {
            visible.add(changes("impl", 1, changed.toArray(String[]::new)));
        }
        return output(handler.execute(context("api-review", "review-api", workspace, visible(visible.toArray(Artifact[]::new)))),
                ArtifactKeys.API_COMPATIBILITY);
    }

    @Test
    void untouchedApiIsCompatible() {
        JsonNode review = review(contract("GET /api/links/{code}", "UNCHANGED"));

        assertThat(review.path("status").asString()).isEqualTo("COMPATIBLE");
        assertThat(review.path("operationsBefore").asInt()).isEqualTo(3);
        assertThat(review.path("operationsAfter").asInt()).isEqualTo(3);
        assertThat(review.path("addedOperations")).isEmpty();
        assertThat(review.path("undocumented")).as("the indexer sees every documented handler").isEmpty();
    }

    @Test
    void removedOperationIsBreaking() throws Exception {
        editOpenApi(STATS_OPERATION, "");
        editController("""

                    @GetMapping("/{code}/stats")
                    public LinkStats stats(@PathVariable String code) {
                        return null;
                    }
                """, "");

        JsonNode review = review();

        assertThat(review.path("status").asString()).isEqualTo("BREAKING");
        assertThat(strings(review.path("breakingChanges"))).containsExactly("operation removed: GET /api/links/{code}/stats");
        assertThat(review.path("undocumented")).isEmpty();
    }

    @Test
    void documentedOperationWhoseHandlerIsRemovedIsBreakingWhateverTheDesignLabelsSay() throws Exception {
        editController("""

                    @GetMapping("/{code}/stats")
                    public LinkStats stats(@PathVariable String code) {
                        return null;
                    }
                """, "");

        JsonNode review = review(contract(STATS, "UNCHANGED"));

        assertThat(review.path("status").asString()).isEqualTo("BREAKING");
        assertThat(strings(review.path("breakingChanges"))).containsExactly("operation no longer implemented: " + STATS);
        assertThat(review.path("operationsAfter").asInt()).as("the document still promises it").isEqualTo(3);
    }

    @Test
    void operationTheBaselineAlreadyLeftUnimplementedIsNotBlamedOnTheChange() throws Exception {
        workspace = CapabilityFixtures.workspace(temp.resolve("drift"), Map.of(OPENAPI, BASELINE_OPENAPI,
                CONTROLLER, replaceOnce(BASELINE_CONTROLLER, "@GetMapping(\"/{code}/stats\")", "@GetMapping(\"/{code}/statistics\")")));
        editOpenApi("        createdAt:\n", "        expiresAt:\n          type: string\n        createdAt:\n");

        JsonNode review = review();

        assertThat(review.path("breakingChanges")).isEmpty();
        assertThat(review.path("unimplemented")).isEmpty();
        assertThat(strings(review.path("undocumented"))).as("drift the baseline already had is still reported")
                .containsExactly("GET /api/links/{code}/statistics (implemented but not in OpenAPI)");
    }

    @Test
    void removedResponsePropertyIsBreaking() throws Exception {
        editOpenApi("""
                        createdAt:
                          type: string
                """, "");

        JsonNode review = review();

        assertThat(review.path("status").asString()).isEqualTo("BREAKING");
        assertThat(strings(review.path("breakingChanges"))).containsExactly("property removed: Link.createdAt");
    }

    @Test
    void removedSchemaIsBreaking() throws Exception {
        editOpenApi(LINK_STATS_SCHEMA, "");

        JsonNode review = review();

        assertThat(review.path("status").asString()).isEqualTo("BREAKING");
        assertThat(strings(review.path("breakingChanges"))).containsExactly("schema removed: LinkStats");
    }

    @Test
    void requestPropertyThatBecomesRequiredIsBreaking() throws Exception {
        editOpenApi("required: [targetUrl]\n", "required: [targetUrl, alias]\n");

        JsonNode review = review();

        assertThat(review.path("status").asString()).isEqualTo("BREAKING");
        assertThat(strings(review.path("breakingChanges"))).containsExactly("request property became required: ShortenRequest.alias");
    }

    @Test
    void responsePropertyThatBecomesRequiredOnlyPromisesMore() throws Exception {
        editOpenApi("required: [code, targetUrl]\n", "required: [code, targetUrl, createdAt]\n");

        JsonNode review = review();

        assertThat(review.path("status").asString()).isEqualTo("COMPATIBLE");
        assertThat(review.path("breakingChanges")).isEmpty();
    }

    @Test
    void designedOperationThatIsImplementedButNotDocumentedIsIncomplete() throws Exception {
        implementDelete();

        JsonNode review = review(contract(DELETE, "NEW"));

        assertThat(review.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(strings(review.path("undocumented"))).containsExactly(DELETE, DELETE + " (implemented but not in OpenAPI)");
        assertThat(review.path("unimplemented")).isEmpty();
        assertThat(review.path("breakingChanges")).isEmpty();
    }

    @Test
    void designedOperationThatIsDocumentedButNotImplementedIsIncomplete() throws Exception {
        documentDelete();

        JsonNode review = review(contract(DELETE, "NEW"));

        assertThat(review.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(strings(review.path("unimplemented"))).containsExactly(DELETE);
        assertThat(review.path("undocumented")).isEmpty();
        assertThat(strings(review.path("addedOperations"))).containsExactly(DELETE);
    }

    @Test
    void documentedOperationThatIsNotImplementedIsIncompleteEvenWhenNotDesigned() throws Exception {
        documentDelete();

        JsonNode review = review();

        assertThat(review.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(strings(review.path("unimplemented"))).containsExactly(DELETE);
        assertThat(review.path("undocumented")).isEmpty();
        assertThat(review.path("breakingChanges")).isEmpty();
    }

    @Test
    void implementedEndpointMissingFromTheDocumentIsIncompleteEvenWhenNotDesigned() throws Exception {
        implementDelete();

        JsonNode review = review();

        assertThat(review.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(strings(review.path("undocumented"))).containsExactly(DELETE + " (implemented but not in OpenAPI)");
        assertThat(review.path("unimplemented")).isEmpty();
    }

    @Test
    void operationLabelledUnchangedThatTheBaselineNeverDocumentedIsStillChecked() {
        JsonNode review = review(contract("GET /api/links/{code}", "UNCHANGED", "GET /api/links/{code}/visitors", "UNCHANGED"));

        assertThat(review.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(strings(review.path("undocumented"))).containsExactly("GET /api/links/{code}/visitors");
        assertThat(strings(review.path("unimplemented"))).containsExactly("GET /api/links/{code}/visitors");
    }

    @Test
    void additiveOperationThatIsDocumentedAndImplementedIsCompatible() throws Exception {
        documentDelete();
        implementDelete();

        JsonNode review = review(contract(DELETE, "NEW", "GET /api/links/{code}", "UNCHANGED"));

        assertThat(review.path("status").asString()).isEqualTo("COMPATIBLE");
        assertThat(strings(review.path("addedOperations"))).containsExactly(DELETE);
        assertThat(review.path("breakingChanges")).isEmpty();
        assertThat(review.path("undocumented")).isEmpty();
        assertThat(review.path("unimplemented")).isEmpty();
        assertThat(review.path("operationsAfter").asInt()).isEqualTo(4);
    }

    @Test
    void propertyRemovedFromANestedResponseObjectIsBreaking() throws Exception {
        editOpenApi("""
                            days:
                              type: integer
                        daily:
                """, """
                        daily:
                """);

        JsonNode review = review();

        assertThat(review.path("status").asString()).isEqualTo("BREAKING");
        assertThat(strings(review.path("breakingChanges"))).containsExactly("property removed: LinkStats.window.days");
    }

    @Test
    void propertyRemovedFromArrayItemsIsBreaking() throws Exception {
        editOpenApi("""
                              visits:
                                type: integer
                """, "");

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactly("property removed: LinkStats.daily[].visits");
    }

    @Test
    void propertyRemovedFromAnAllOfMemberIsBreaking() throws Exception {
        editOpenApi("""
                        - type: object
                          properties:
                            errors:
                              type: array
                              items:
                                type: string
                """, """
                        - type: object
                """);

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactly("property removed: ValidationProblem.errors");
    }

    @Test
    void changedPropertyTypeOrFormatIsBreaking() throws Exception {
        editOpenApi("        clicks:\n          type: integer\n", "        clicks:\n          type: string\n");
        editOpenApi("format: date\n", "format: date-time\n");

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactlyInAnyOrder(
                "type changed: LinkStats.clicks integer -> string", "format changed: LinkStats.window.from date -> date-time");
    }

    @Test
    void nestedRequestPropertyThatBecomesRequiredIsBreaking() throws Exception {
        editOpenApi("        options:\n          type: object\n", "        options:\n          type: object\n          required: [ttlDays]\n");

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactly("request property became required: ShortenRequest.options.ttlDays");
    }

    @Test
    void requestBodyThatRequiresMoreThroughAllOfIsBreaking() throws Exception {
        editOpenApi("""
                            schema:
                              $ref: '#/components/schemas/ShortenRequest'
                """, """
                            schema:
                              allOf:
                                - $ref: '#/components/schemas/ShortenRequest'
                                - required: [alias]
                """);

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactly("request property became required: POST /api/links request.alias");
    }

    @Test
    void parameterThatBecomesRequiredIsBreaking() throws Exception {
        editOpenApi("          in: query\n", "          in: query\n          required: true\n");
        editOpenApi("      in: header\n", "      in: header\n      required: true\n");

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactlyInAnyOrder(
                "parameter became required: GET /api/links/{code}/stats query days",
                "parameter became required: POST /api/links header idempotency-key");
    }

    @Test
    void removedParameterAndNewRequiredParameterAreBreaking() throws Exception {
        editOpenApi("        - name: days\n          in: query\n", "        - name: range\n          in: query\n          required: true\n");

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactlyInAnyOrder(
                "parameter removed: GET /api/links/{code}/stats query days",
                "required parameter added: GET /api/links/{code}/stats query range");
    }

    @Test
    void removedSuccessResponseIsBreaking() throws Exception {
        editOpenApi("'201':", "'299':");

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactly("response removed: POST /api/links 201");
    }

    @Test
    void successResponseThatReturnsAnotherSchemaIsBreaking() throws Exception {
        editOpenApi("""
                  /api/links/{code}:
                    get:
                      responses:
                        '200':
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Link'
                """, """
                  /api/links/{code}:
                    get:
                      responses:
                        '200':
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Problem'
                """);

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactly("response schema changed: GET /api/links/{code} 200 response Link -> Problem");
    }

    @Test
    void successResponseNoLongerServedAsJsonIsBreaking() throws Exception {
        editOpenApi("""
                  /api/links/{code}:
                    get:
                      responses:
                        '200':
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Link'
                """, """
                  /api/links/{code}:
                    get:
                      responses:
                        '200':
                          content:
                            text/plain:
                              schema:
                                type: string
                """);

        JsonNode review = review();

        assertThat(review.path("status").asString()).isEqualTo("BREAKING");
        assertThat(strings(review.path("breakingChanges"))).containsExactly("response content removed: GET /api/links/{code} 200 application/json");
    }

    @Test
    void arrayResponseWhoseItemsReturnAnotherSchemaIsBreakingLikeATopLevelOne() throws Exception {
        rebaseOpenApi(replaceOnce(BASELINE_OPENAPI, """
                        '200':
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Link'
                """, """
                        '200':
                          content:
                            application/json:
                              schema:
                                type: array
                                items:
                                  $ref: '#/components/schemas/Link'
                """));
        editOpenApi("""
                                items:
                                  $ref: '#/components/schemas/Link'
                """, """
                                items:
                                  $ref: '#/components/schemas/LinkV2'
                """);
        editOpenApi("    Problem:\n", """
                    LinkV2:
                      allOf:
                        - $ref: '#/components/schemas/Link'
                        - type: object
                          properties:
                            expiresAt:
                              type: string
                    Problem:
                """);

        JsonNode review = review();

        assertThat(strings(review.path("breakingChanges"))).containsExactly("response schema changed: GET /api/links/{code} 200 response Link[] -> LinkV2[]");
    }

    @Test
    void alternativeInsertedBeforeTheExistingOnesIsCompatibleButARequestAlternativeRemovedIsBreaking() throws Exception {
        rebaseOpenApi(replaceOnce(replaceOnce(BASELINE_OPENAPI, "$ref: '#/components/schemas/ShortenRequest'\n",
                        "$ref: '#/components/schemas/AnyRequest'\n"),
                "    Problem:\n", """
                    AnyRequest:
                      oneOf:
                        - $ref: '#/components/schemas/ShortenRequest'
                        - $ref: '#/components/schemas/Link'
                    Problem:
                """));
        editOpenApi("      oneOf:\n", "      oneOf:\n        - $ref: '#/components/schemas/Problem'\n");

        JsonNode inserted = review();

        assertThat(inserted.path("status").asString()).isEqualTo("COMPATIBLE");
        assertThat(inserted.path("breakingChanges")).isEmpty();

        editOpenApi("        - $ref: '#/components/schemas/Link'\n", "");

        assertThat(strings(review().path("breakingChanges"))).containsExactly("request alternative removed: AnyRequest oneOf Link");
    }

    @Test
    void additiveChangesAtEveryLevelAreCompatible() throws Exception {
        documentDelete();
        implementDelete();
        editOpenApi("            days:\n", "            to:\n              type: string\n              format: date\n            days:\n");
        editOpenApi("        - name: days\n", "        - name: tz\n          in: query\n          schema:\n            type: string\n        - name: days\n");
        editOpenApi("                $ref: '#/components/schemas/LinkStats'\n",
                "                $ref: '#/components/schemas/LinkStats'\n        '404':\n          description: Unknown code\n");
        editOpenApi("            ttlDays:\n", "            note:\n              type: string\n            ttlDays:\n");

        JsonNode review = review(contract(DELETE, "NEW"));

        assertThat(review.path("status").asString()).isEqualTo("COMPATIBLE");
        assertThat(review.path("breakingChanges")).isEmpty();
        assertThat(strings(review.path("addedOperations"))).containsExactly(DELETE);
    }

    @Test
    void designedOperationsAreMatchedByRouteNotBySpelling() throws Exception {
        documentDelete();
        implementDelete();

        JsonNode review = review(contract("GET /api/links/{code:[A-Za-z0-9_-]{4,32}}", "UNCHANGED",
                "GET /api/links/{id}/stats/", "UNCHANGED", "DELETE /api/links/{linkCode}/", "NEW"));

        assertThat(review.path("status").asString()).isEqualTo("COMPATIBLE");
        assertThat(review.path("undocumented")).isEmpty();
        assertThat(review.path("unimplemented")).isEmpty();
    }
}
