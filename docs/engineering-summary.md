# Engineering summary

This is the summary of the project as a whole. Every workflow run additionally generates its own summary
(`runs/<run-id>/outcome/ENGINEERING_SUMMARY.md`) from recorded state and events.

## Problem and interpretation

The goal was a prototype that turns a requirement into a reviewable engineering outcome through an agentic,
governed SDLC workflow, demonstrated on a URL shortener built from scratch. The requirements were interpreted
as follows:

- The **URL shortener** is a real product: it must be correct, tested and maintainable on its own, and it is
  the target codebase the workflow reasons about and changes.
- **Workflow orchestration is the differentiator.** It has to be a real dependency-graph engine with gates,
  parallelism, human checkpoints, recovery, governance, lineage, re-planning and metrics that a reviewer can
  watch working – not a scripted pipeline that prints those words.
- "Controlled autonomy" means agents propose and execute multi-step work, while deterministic code decides
  what is allowed, and humans approve high-impact actions and own the final release decision.
- The prototype must be runnable and testable **without paid APIs or network access**, so reasoning is
  isolated behind an interface and replayed deterministically, while everything else executes for real.

## Plan and rationale

1. **Build system and module boundary.** Two Maven modules in one reactor. The shortener is standalone
   buildable so the orchestrator can copy it into an isolated workspace and run its real tests; the
   orchestrator is a separate plain-Java CLI because an agent runtime that writes files and runs builds must
   not be deployed inside the service.
2. **URL shortener** (Spring Boot 4.1, JDBC + Flyway on H2 in PostgreSQL mode): create/read/disable links,
   302 redirects, click analytics, idempotent creation, collision-safe code generation, RFC 9457 errors,
   hand-written OpenAPI with a drift test.
3. **Engine core first, tested in isolation** with synthetic capabilities: DAG, state machine, coordinator +
   worker pool, gates, policy hook, human checkpoints, retries, fallback, rework, rollback, safe stop,
   selective invalidation.
4. **Governance and infrastructure**: change policy, plan validator, gate catalogue, isolated workspace with
   pre-image rollback, static codebase indexer and impact analyzer, Maven build verifier.
5. **Vertical slice**: the brownfield scenario end to end against the real shortener, which surfaced real
   integration bugs (a race between a review and a concurrent build, persistence, attribution) before the
   other scenarios were written.
6. **Scenarios, tests, documentation, adversarial self-review.** The review led to a hardening pass: approvals
   bound to the exact change and tree they were shown, capability roles and scope roots in plan validation, a
   fault barrier on the coordinator, canonical-path and symbolic-link containment in the workspace, stricter
   policy patterns, an allow-listed build environment, and tests for every engine property checked with
   mutation probes during development. A second pass closed the gaps an independent review found: crash
   recovery after a rollback or write that was not yet saved, mandatory and recursive API compatibility review,
   verification coverage of every change, migration and configuration rules that cannot be side-stepped by moving
   files, the complete diff behind every approval, and a second recorded direction for the ambiguous scenario.

## Artifacts delivered

| Artifact | Location |
|---|---|
| URL shortener service, API contract, migrations, tests | `shortener/` |
| Orchestration engine, governance, capabilities, CLI | `orchestrator/src/main/java/com/example/sdlc` |
| Scenario definitions and recorded reasoning | `scenarios/{greenfield,brownfield,ambiguous}` |
| Architecture, scenarios, testing documentation | `docs/` |
| Per-run outcome: patch, evidence, summary, metrics, audit log | `runs/<run-id>/` (generated) |

## Validation

- Shortener: 146 unit and integration tests (HTTP contract, persistence, analytics, idempotency, collisions,
  OpenAPI drift, `Location` independent of request headers), run on every build and again inside every scenario
  workspace as the baseline.
- Orchestrator: 405 tests by default - engine-semantics tests for each orchestration property, crash recovery,
  capability, policy, workspace, plan validation, gate, codebase analysis, build verification, reasoning replay,
  metrics, persistence, lineage and CLI tests, and deterministic scenario tests - plus real-build end-to-end tests
  for every scenario path (`-Pe2e`, 410 in total). See [testing](testing.md) for the property-to-test mapping.
- Each scenario's outcome patch is verified by the shortener's real test suite (baseline plus the feature's
  new tests) before the release checklist and human sign-off; a produced patch applies cleanly to the
  repository with `git apply`.

### Scenario results

One run of each scenario with real builds on the development machine (warm Maven cache; timings vary). All
figures come from `./sdlc metrics <run-id>`, which derives them from the run's event log.

| | Greenfield | Brownfield | Ambiguous (`answers.yaml`) | Ambiguous (`answers-global-ttl.yaml`) |
|---|---|---|---|---|
| Outcome | COMPLETED, READY | COMPLETED, READY | paused for clarification, then COMPLETED, READY | paused for clarification, then COMPLETED, READY |
| Tasks succeeded / failed | 15 / 0 | 14 / 0 | 14 / 0 | 13 / 0 |
| Attempts / retries | 23 / 0 | 20 / 2 (plan rejected by governance; proposal denied by policy) | 15 / 0 | 14 / 0 |
| Reworks | 1 (reviewer's change request to the design) | 1 (real test failure) | 0 | 0 |
| Policy evaluations / denials | 10 / 0 | 9 / 1 | 6 / 0 | 4 / 0 |
| Human checkpoints | 6 | 5 | 5 (including the clarification) | 4 (including the clarification) |
| Change approvals (rules) | storage [CC-02, CC-09], new endpoint [SEC-06] | capture [CC-02, SEC-04, CC-09], statistics [SEC-04] twice | storage [CC-02], lifecycle [SEC-04] | lifetime [CC-09] |
| Change sets applied / rolled back | 8 / 3 (selective re-planning) | 5 / 1 (rework) | 4 / 0 | 3 / 0 |
| Failure-forced rollback rate | 0 | 0 | 0 | 0 |
| MTTR | - (no failures) | 1.7 s over 3 recoveries | - (no failures) | - (no failures) |
| End-to-end latency | 15.7 s | 14.9 s | 24.6 s (resumed right away) | 23.8 s (resumed right away) |
| Max concurrency | 3 | 3 | 3 | 3 |
| Service tests after the change (baseline 146) | 202 | 157 | 175 | 159 |

## Risks and trade-offs

| Risk / trade-off | Mitigation or rationale |
|---|---|
| Recorded reasoning only demonstrates the recorded paths | Honest labelling; recordings contain realistic mistakes so governance and recovery are exercised by real behaviour; two different clarification answers are recorded and lead to different specifications, plans and code; a live provider only needs to implement `ReasoningProvider`. Human answers outside the recorded sets stop the run instead of improvising |
| Regex/token static analysis over-approximates (type-level, not method-level) | Good enough to scope change control and select affected tests; unknown components are rejected rather than guessed; limitation documented |
| Real builds make scenarios take tens of seconds and depend on a local JDK and cached dependencies | `--verification static` gives a fast degraded mode that can never be released; real-build tests are opt-in |
| Rework attribution is heuristic | Ordered, explainable evidence (compiler paths; per failing test, the changed resources it loads together with the symbols its message quotes or the types it references); unknown means "rework every verified task"; suspects outside the verifier's coverage stop the run instead of reworking unrelated work; rework is budget-bounded |
| Single coordinator thread limits throughput | Deliberate: correctness and a total event order matter more than throughput for an SDLC run; long work runs on workers |
| File-based persistence assumes one writer per run | Adequate for a CLI; a service would need a transactional store and leases |
| Scripted reviewer could be mistaken for automation of human judgement | Scripted decisions are attributed to `scenario-reviewer` in the audit log; `--reviewer interactive` and `--reviewer deferred` put a real person in the loop |
| Policy rules are pattern-based | They are guardrails, not a proof; they are tested against realistic bypass shapes (relocated SQL, configuration that redirects migrations, patterns split across lines), the aggregate security review re-applies them and cross-checks approvals, and humans sign off. Known gaps: personal data in numeric columns is not recognised, and a comment that mentions a forbidden API is flagged |
| Verification builds and runs proposed code (including tests) on the host | The build gets an allow-listed environment, test code that starts processes needs approval (SEC-05), and toolchain files cannot be changed (SEC-03). This limits leakage but is not a sandbox; with a live model the orchestrator must run in a container or VM without network access |

## Assumptions

- Java 21 language level; the development machine used JDK 25 (Spring Boot 4.1 supports both).
- The shortener is anonymous and unauthenticated by design for this prototype; authentication and rate
  limiting are expected at an API gateway.
- Scenario requirements are single-module changes to the shortener; multi-repository changes are out of scope.
- A human reviewer is available asynchronously; a paused run can wait indefinitely.

## Limitations

- No live model provider is included; see the reasoning boundary in [architecture](architecture.md).
- Verification is not sandboxed (see risks above).
- Recorded reasoning is replayed by task and execution count, so a recording only matches the path it was
  recorded on; any other path stops the run with `REASONING_UNAVAILABLE` instead of improvising.
- Crash recovery reconciles the applied changes with the disk (writes are recorded before they happen; rollbacks
  and interrupted writes are recognised on resume), but anything that does not match a state the engine itself
  could have produced - for example a hand edit - is never repaired: the run stops for manual attention.
- The engine does not enforce per-attempt time limits on arbitrary handlers; the only long-running handler
  (build verification) has its own hard timeout.
- The orchestrator produces a patch and evidence; merging, deployment and post-release monitoring are not
  automated.

## Next steps

1. Run verification in a sandbox (container or VM, no network, only the workspace writable) - a prerequisite
   for a live model.
2. Add a live `ReasoningProvider` (JSON-schema-constrained responses, prompt/response recording to produce
   new recordings), keeping the recorded provider as the test double and fallback.
3. Method-level static analysis (or a proper Java parser) to sharpen impact analysis and data flows.
4. A `plan diff` approval for re-plans that add high-impact work, and policy configuration per repository.
5. Persist runs in a transactional store to allow concurrent operators and a web view of the event stream.
