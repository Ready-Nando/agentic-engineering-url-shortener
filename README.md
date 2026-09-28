# Governed agentic SDLC for a URL shortener

A working prototype of an **agentic software-delivery workflow with controlled autonomy**, applied to a real
URL-shortening service.

- [`shortener/`](shortener/README.md) – a production-style URL shortener: REST API, redirects, click
  analytics, idempotent creation, collision-safe code generation, RFC 9457 errors, Flyway-managed schema,
  OpenAPI contract with drift test, 137 tests.
- [`orchestrator/`](docs/architecture.md) – a deterministic orchestration engine that takes a requirement
  through requirements analysis, codebase impact analysis, planning, design, implementation, testing,
  documentation, validation and release readiness as an explicit **dependency graph with entry/exit gates**,
  parallel branches, human approval checkpoints, bounded retries, fallback, rework, rollback, safe stop,
  policy guardrails, selective re-planning, lineage and reliability metrics.
- [`scenarios/`](docs/scenarios.md) – three end-to-end scenarios against the shortener: **greenfield**
  (new capability), **brownfield** (enhancing an existing data flow) and **ambiguous** (the workflow stops to
  ask instead of inventing requirements).

The outcome of every run is a **reviewable engineering result**: a patch against `shortener/` that was verified
by running the shortener's real test suite in an isolated workspace, the evidence behind it (artifacts with
lineage, audit log, approvals) and a generated engineering summary. Nothing is merged or deployed
automatically; humans own approvals and the final decision.

## What is real and what is recorded

Reasoning (requirement interpretation, plans, designs, code proposals) sits behind a `ReasoningProvider`
interface. So that the project builds, tests and demonstrates without API keys or network access, the
shipped provider **replays recorded reasoning outputs** stored in `scenarios/*/recordings/`. Everything
else is executed for real on every run: plan validation, dependency scheduling and parallelism, gates,
change policy, approvals, workspace changes, rollbacks, codebase analysis of the actual shortener source,
and verification with the shortener's real Maven test suite. The recordings include realistic mistakes (a
plan missing a security review, a proposal that stores raw IP addresses, an implementation with a real
bug) so the governance and recovery paths are exercised by genuine behaviour, not by printed text.

Verification builds and tests the proposed code on the host, with an allow-listed environment but no sandbox.
That is acceptable for the reviewed recordings shipped here; a live model-backed provider should run the
orchestrator inside a container or VM without network access (see [architecture](docs/architecture.md#reasoning-boundary)).

## Quickstart

Requirements: a JDK 21 or newer (tested with 21 as the compile target on JDK 25). No Maven installation is
needed (the Maven Wrapper is included); the first build downloads dependencies.

```bash
./mvnw verify
```

Builds both modules and runs the default test suites (shortener: 137 tests; orchestrator: 277 tests covering
policy, workspace, codebase analysis, gates, engine semantics, CLI and a deterministic scenario run). The
slower scenario tests that run the shortener's real test suite are opt-in:

```bash
./mvnw -pl orchestrator -Pe2e test
```

### Run the URL shortener

```bash
./mvnw -f shortener spring-boot:run
```

```bash
curl -s -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' -d '{"url":"https://example.org/docs"}'
```

The API is described in [`shortener/README.md`](shortener/README.md) and served as OpenAPI at
`http://localhost:8080/openapi.yaml`.

### Run the scenarios

`./sdlc` is a small launcher for the orchestrator CLI (build first with `./mvnw verify` or
`./mvnw -q -DskipTests package`).

```bash
./sdlc scenarios
```

```bash
./sdlc run brownfield
```

```bash
./sdlc run greenfield
```

```bash
./sdlc run ambiguous
```

The ambiguous scenario stops and waits for a human (exit code 3). It prints its questions and the exact
command to continue, for example:

```bash
./sdlc resume <run-id> --answers scenarios/ambiguous/answers.yaml
```

Each scenario takes roughly 10–20 seconds once Maven's cache is warm, most of it the shortener's own test suite
running in the workspace. Useful variations:

- `--reviewer interactive` – you make the approval / clarification decisions in the terminal.
- `--reviewer deferred` – every checkpoint pauses the run (exit code 3); continue it from any later process.
- `--verification static` – disables real builds; verification falls back to degraded static checks and the
  release readiness gate refuses to declare the outcome ready (all changes are rolled back).

Continuing or stopping a paused run:

```bash
./sdlc resume <run-id> --approve <request-id> --comment "..."
```

```bash
./sdlc resume <run-id> --request-changes <request-id> --target design --comment "what should change"
```

```bash
./sdlc cancel <run-id> --reason "..."
```

`resume` also takes `--reject <request-id>`, `--answers <file>` and `--as <name>`. A resumed run keeps the
reviewer it was started with; handing a human-reviewed run to the scenario script requires
`--reviewer scripted --allow-scripted-reviewer`. `cancel` is a human-initiated safe stop: open checkpoints
are cancelled, every applied change is compensated and the workspace is verified against the baseline.

### Inspect a run

```bash
./sdlc status <run-id>
```

```bash
./sdlc lineage <run-id> changes/impl-stats
```

```bash
./sdlc events <run-id> --type POLICY_EVALUATED
```

```bash
./sdlc metrics
```

`./sdlc governance` prints the policy rules, gates and capability catalogue. Every run is stored under
`runs/<run-id>/`: `run.json` (state), `events.jsonl` (audit log), `artifacts/` (every artifact version),
`workspace/` and `baseline/`, and `outcome/` with `ENGINEERING_SUMMARY.md`, `changes.patch` (or
`abandoned-changes.patch` after a safe stop) and `metrics.json`. A completed run's patch applies to this
repository with `git apply runs/<run-id>/outcome/changes.patch`.

## Repository layout

```
shortener/        URL shortener service (Spring Boot, standalone Maven module)
orchestrator/     SDLC orchestrator (plain Java CLI)
scenarios/        scenario definitions and recorded reasoning
docs/             architecture, scenarios, testing, engineering summary
sdlc              CLI launcher
```

## Documentation

- [Architecture](docs/architecture.md) – components, orchestration model, control flow, governance,
  recovery, re-planning, observability, key decisions.
- [Scenarios](docs/scenarios.md) – what each scenario demonstrates and how to follow it.
- [Testing](docs/testing.md) – testing approach and what is (and is not) covered.
- [Engineering summary](docs/engineering-summary.md) – plan and rationale, artifacts, validation, risks,
  trade-offs, assumptions and limitations.
- [URL shortener](shortener/README.md) – service API and design notes.
