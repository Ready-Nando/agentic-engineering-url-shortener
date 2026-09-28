# Scenarios

Three scenarios exercise the same orchestration engine against the same target (`shortener/`). They differ in
the kind of requirement, and therefore in the shape of the plan and in which controls come into play.

| | Greenfield | Brownfield | Ambiguous |
|---|---|---|---|
| Requirement | New capability: abuse reports and automatic takedown | Enhance an existing data flow: unique-visitor analytics | "Short links should expire." |
| Codebase reasoning | Integration points for a new package | Impact across controller -> service -> repository -> table, affected tests | Impact after clarification |
| Plan shape | Three parallel branches joined by the API work | Sequential capture -> stats, documentation in parallel | Decided only after the requirement is clarified |
| Human checkpoints | Design sign-off (HIGH-impact decision), migration and configuration approval, new-endpoint approval, release change request, release sign-off | Design sign-off, migration plus request-handling and configuration approval, response-change approval, release sign-off | Clarification (run pauses), design sign-off, approvals that depend on the answers, release sign-off |
| Recovery shown | Selective re-planning after a release change request | Plan rejected by governance and re-proposed; policy denial and retry; real test failure, rollback and targeted rework | Pause and resume across processes without redoing finished work; the human's answers steer everything after the pause |
| Outcome | `COMPLETED`, verdict `READY`, patch | `COMPLETED`, verdict `READY`, patch | paused, then `COMPLETED`, verdict `READY`, patch - for either recorded set of answers |

Every scenario runs the shortener's real test suite at least twice inside an isolated workspace (baseline and
final), so each takes roughly 10–20 seconds once Maven's cache is warm. Every completed run leaves a patch
that applies to this repository with `git apply`; the patched service passes its own test suite (157 tests for
brownfield, 202 for greenfield, 175 and 159 for the two ambiguous directions, up from 146). Reasoning outputs are
replayed from `scenarios/<id>/recordings/` (see [architecture](architecture.md#reasoning-boundary)); everything
else is executed for real.

```bash
./mvnw -q -DskipTests package
```

## Greenfield: abuse reporting and automatic takedown

```bash
./sdlc run greenfield
```

**Requirement.** Anyone can report a short link (SPAM, PHISHING, MALWARE, OTHER); reports from 3 distinct
reporters within 24 hours disable the link automatically (redirect answers 410); the same reporter counts once;
reporter identity is only stored as a keyed hash; moderator tooling is out of scope.

**What to look for**

1. *Requirement understanding*: the normalised specification lists acceptance criteria, constraints, risks
   and an explicit out-of-scope item.
2. *Decomposition*: the plan puts storage (`impl-store`), the takedown rule (`impl-policy`) and documentation
   (`docs`) on parallel branches; `impl-api` joins storage and rule. The console shows the three branches
   starting together (`in flight: 1/2/3`), and `impl-api` starting only after both of its prerequisites.
3. *Governance*: the design contains a HIGH-impact decision (automated action against customers' links), so
   design sign-off is required. The storage change needs one approval covering the new table (CC-02) and the new
   key setting in `application.yml` (CC-09); the new public endpoint needs its own approval (SEC-06). Each approval
   request points to the complete diff it covers (`approvals/<request-id>.patch`).
4. *Validation*: the real test suite runs on the combined change; every acceptance criterion is traced to a
   passing test; security and API reviews run after all changes.
5. *Human oversight and re-planning*: at release sign-off the reviewer requests a change to the **design**
   ("the takedown threshold and window must be configurable"). The design re-runs; only decision `D-2`
   changes, the other design artifacts are republished unchanged ("early cutoff"). Only the work that
   consumed `D-2` - the takedown rule, its documentation and the tests - is rolled back and redone, plus the
   checks that read that work. Storage and API work are preserved (one execution each). The release is then
   signed off.

```bash
./sdlc status <run-id>
```

```bash
./sdlc lineage <run-id> changes/impl-api
```

`status` shows executions per task (`impl-store` and `impl-api` ran once, `impl-policy` twice); `lineage` shows
that the API change set was never derived from decision `D-2`.

## Brownfield: privacy-preserving unique-visitor analytics

```bash
./sdlc run brownfield
```

**Requirement.** Report the number of distinct visitors per link without storing IP addresses or user agents
and without cookies (a keyed, day-scoped fingerprint), keeping existing statistics unchanged.

**What to look for**

1. *Codebase reasoning*: the analysis names where the change starts; the impact analyzer computes the rest
   from the real source: impacted components with distance, the two endpoints, the `click_event` table, data
   flows from `GET /{code}` and `GET /api/v1/links/{code}/stats` down to the table, and the existing tests
   that exercise them. Editing an existing file outside this set would need approval (CC-06).
2. *Plan governance*: the planner's first proposal forgets that the documentation change also needs the
   security review; the plan validator rejects it with that exact violation and the planner's second proposal
   is accepted.
3. *Compliance guardrail*: the first implementation proposal stores the raw client IP and user agent. Policy
   denies it (CMP-01) and records that the proposer's self-declared "LOW" risk was ignored (GOV-01). The
   violations go back as feedback; the second proposal stores a keyed hash in a new nullable column.
4. *Approvals*: design sign-off (data model change); one approval for the capture change set, which adds a
   migration (CC-02), changes the existing redirect controller (SEC-04) and adds the hashing-key setting to
   `application.yml` (CC-09); and one for the statistics
   change, which alters an existing API response type (SEC-04). A scripted rule approves a request only if it
   names every rule in it, and an approval covers exactly the change it was shown.
5. *Real verification, rollback and rework*: the statistics change has a genuine bug (it counts visitors
   outside the requested window). The real test suite fails on the new acceptance test; the failure message
   names `uniqueVisitors`, a symbol introduced by the `impl-stats` change set, so only that task is rolled back
   and redone with the failing test as feedback. The capture-side change is not touched. The second run of the
   suite passes; the dependent reviews re-run because their inputs changed.
6. *Existing tests*: the impact analysis lists `RedirectIntegrationTest` as affected; it pins the exact set of
   stored click columns, so the test task updates it (the new column still holds no personal data).

```bash
./sdlc events <run-id> --type POLICY_EVALUATED
```

```bash
./sdlc lineage <run-id> changes/impl-stats
```

## Ambiguous: "short links should expire"

```bash
./sdlc run ambiguous
```

The command ends with exit code 3: the run is **paused**, waiting for the product owner. The requirement
analysis asks two questions itself (which links expire and how; what an expired link returns). It also states
an overconfident, high-impact assumption ("existing links also expire retroactively") and copies the vague
wording "handled appropriately" into an acceptance criterion - the requirement-quality gate turns both into
questions of its own. Meanwhile the independent discovery work (codebase scan, baseline build) completes.

```bash
./sdlc resume <run-id> --answers scenarios/ambiguous/answers.yaml
```

The answers are recorded as an artifact produced by `human:product-owner`; the requirement is re-analysed with
them (the analysis that stopped at its gate was never published, and the accepted specification records the
answers as its input), and the run continues through impact analysis, planning, design sign-off,
migration approval, implementation (with SEC-04 approval, since existing request-handling classes change),
the real test suite, reviews and release sign-off - without repeating the codebase scan or the baseline build.

### A different answer, a different direction

Two sets of answers are recorded for the same paused run. Everything after the pause depends on which one the
product owner gives:

| | `answers.yaml` | `answers-global-ttl.yaml` |
|---|---|---|
| Answers | Q-1 `per-link`, Q-2 `410`, A-1 `reject` (existing links never expire) | Q-1 `global-ttl`, Q-2 `404`, A-1 `confirm` (existing links expire too) |
| Specification | Optional per-link expiry chosen at creation (`expiresAt`) | A configured lifetime (`365d`) for every link, applied retroactively; no API change |
| Design | Nullable `expires_at` column; expired links answer 410 like disabled ones | No schema change; HIGH-impact decision "retroactive expiry of existing links" (triggers design sign-off); expired links answer 404 like unknown codes |
| Plan | `impl-storage` (migration) then `impl-lifecycle` (API and redirect), docs, tests | A single `impl-lifetime` task (configuration and redirect check), docs, tests |
| Approvals | Migration (CC-02), request-handling changes (SEC-04) | Configuration change (CC-09) |
| Outcome | `READY`; patched service passes 175 tests | `READY`; patched service passes 159 tests |

```bash
./sdlc resume <run-id> --answers scenarios/ambiguous/answers-global-ttl.yaml
```

What this proves: the human's answers are an input - visible in lineage - of the specification, the impact
analysis, the plan, the design and every change set, so the same paused run is steered in materially different
directions by the decision a person makes. The discovery work done before the pause is not repeated in either
case. Recordings are selected by the exact option answers (`recordings/variants/<name>/variant.yaml`); any
combination nobody recorded (for example Q-1 `both`) makes the offline provider stop with
`REASONING_UNAVAILABLE` before planning, rather than replay an analysis made for different decisions.

## Variations worth trying

| Command | What it shows |
|---|---|
| `./sdlc run brownfield --reviewer interactive` | You answer every checkpoint in the terminal (approve, reject, or request changes, including to an upstream task) |
| `./sdlc run brownfield --reviewer deferred` | Every checkpoint pauses the run; continue with `./sdlc resume <run> --approve <request>` (the reviewer mode is kept across resumes) |
| `./sdlc resume <run> --request-changes <request> --target design --comment "..."` | An asynchronous change request to an upstream task |
| `./sdlc cancel <run> --reason "..."` | Human-initiated safe stop of a paused run: checkpoints cancelled, every change compensated, workspace verified against baseline |
| `./sdlc run brownfield --verification static` | Real builds disabled: verification falls back to degraded static checks, the release checklist refuses to declare the outcome ready, and all changes are compensated |
| `./sdlc metrics` | Reliability metrics aggregated across all runs |

## Command reference

| Command | Purpose |
|---|---|
| `./sdlc scenarios` | List the scenarios |
| `./sdlc run <scenario> [--reviewer scripted\|interactive\|deferred] [--verification build\|static] [--parallelism N] [--verbose]` | Start a run (`--parallelism`: maximum concurrently running tasks, default 4; `--verbose`: print every event) |
| `./sdlc resume <run> [--answers <file>] [--approve <id>] [--reject <id>] [--request-changes <id> [--target <task>]] [--comment "..."] [--as <name>] [--reviewer ... [--allow-scripted-reviewer]]` | Record decisions for a paused run and continue it |
| `./sdlc cancel <run> --reason "..." [--as <name>]` | Human-initiated safe stop: checkpoints cancelled, every change compensated |
| `./sdlc status <run>` | Plan, task states, executions and pending checkpoints |
| `./sdlc lineage <run> <artifact>[@vN]` | What produced an artifact, what it was derived from, what depends on it |
| `./sdlc events <run> [--type <EVENT>] [--task <task>] [--json]` | The audit log, filtered, or as raw JSON lines |
| `./sdlc metrics [<run>]` | Reliability metrics for one run, or aggregated over all runs |
| `./sdlc runs` | List persisted runs |
| `./sdlc governance` | Policy rules, gates and capability catalogue |

Global options: `--runs-dir <dir>` stores and reads runs somewhere other than `runs/`; `--repo <dir>` points at the
repository root when it cannot be detected from the working directory.

Exit codes: `0` the run completed (or the command succeeded); `3` the run is paused, waiting for a human; `2` the
run halted (safe stop, verdict `NOT_READY`) or the command was used incorrectly - for example an unknown run id,
which prints a one-line message pointing at `./sdlc runs`.
