# Testing approach

Tests are part of the design: every orchestration property the system claims has a test that would fail if
the behaviour were only printed, and the URL shortener is tested like any production service. All default
tests are deterministic and need no network or API key.

```bash
./mvnw verify
```

```bash
./mvnw -pl orchestrator -Pe2e test
```

The first command runs everything except the real-build scenario tests (shortener: 137 tests, orchestrator:
277 tests). The second runs the orchestrator suite plus the three `*ScenarioE2ETest` classes, each of which runs
the shortener's own test suite inside a scenario workspace (10–20 s per scenario once Maven's cache is warm).

## Layers

| Layer | Where | What it gives confidence in |
|---|---|---|
| Service unit tests | `shortener/src/test/.../link`, `.../analytics` | Code generation, URL and alias validation, referrer minimisation, idempotency race handling |
| Service integration tests | `shortener/src/test` (Spring Boot + MockMvc + in-memory H2) | The HTTP contract end to end: status codes, problem details, idempotent replays, collisions with a scripted random source, redirects and cache headers, analytics failure isolation, statistics with a fixed clock, OpenAPI drift (request and response bodies derived from the real handler mappings) |
| Engine semantics | `orchestrator/.../engine/*Test` | The orchestration properties below, with synthetic capabilities (`EngineHarness`) so each property is isolated; concurrency is coordinated with latches released by engine events, never with sleeps |
| Capability handlers | `capability/*Test` | The evidence behind release decisions: security review cross-checks approvals per task and rule, the readiness checklist, rework suspects from real Surefire output |
| Component tests | `policy`, `workspace`, `engine/PlanValidatorTest`, `gates`, `codebase`, `verify`, `reasoning`, `metrics`, `report`, `store`, `scenario` | Each governance and infrastructure component on its own, including bypass attempts against the guardrails |
| CLI | `cli/SdlcCliTest` | The commands a reviewer uses, against real scenario runs: deferred approval by a named reviewer, change requests, cancel, reviewer-mode protection on resume |
| Scenario (deterministic) | `scenario/DegradedVerificationScenarioTest` | The brownfield scenario end to end with real builds disabled: governance still runs, verification degrades, readiness refuses release, all changes are compensated |
| Scenario (real build, `-Pe2e`) | `scenario/{Greenfield,Brownfield,Ambiguous}ScenarioE2ETest` | Each scenario end to end, including the shortener's real test suite on the changed code |

## Orchestration properties and the tests that prove them

| Property | Test |
|---|---|
| Dependencies execute in order; dependent work waits for all prerequisites | `WorkflowEngineTest.dependentWorkWaitsForAllPrerequisitesAndIndependentWorkRunsConcurrently` (event sequence numbers) |
| Independent work runs concurrently; synchronisation at the join | same test: two branches must meet at a `CyclicBarrier`, which times out unless they run at the same time; `GreenfieldScenarioE2ETest` (three branches in flight, the join starts after both prerequisites) |
| Nothing runs while an ancestor is being redone | `ConcurrencyInvariantsTest.readyTaskIsNotDispatchedWhileAnAncestorIsBeingRedone`; `WorkflowEngineTest.upstreamChangeInvalidatesOnlyConsumersOfTheChangedArtifact` |
| Results computed on inputs that changed meanwhile are discarded | `ConcurrencyInvariantsTest.siblingStillRunningWhenReworkRetractsItsInputIsDiscardedAndRerunsOnCurrentInputs`; `PlanRevisionTest.attemptWhoseSpecIsRevisedWhileItRunsIsDiscardedAndRerunsWithTheNewSpec` |
| Deterministic scheduling | `ConcurrencyInvariantsTest.theSameRunTwiceProducesTheSameEventSequence` (single worker; with several workers completion order is inherently racy) |
| Entry gates enforced | `FaultBarrierTest.failingEntryGateKeepsTheHandlerFromRunningAndBlocksDependents`, `...entryGateDeclaredByThePlanIsEnforcedToo`; `StandardGatesTest.workspaceIntegrityDetectsOutOfBandEdits` |
| Exit gates enforced; failure retried with feedback | `WorkflowEngineTest.failingExitGateTriggersRetryWithFeedback` |
| Retry budgets are bounded; exhaustion safely stops the run; failed prerequisites block dependents | `WorkflowEngineTest.retriesAreBoundedAndExhaustionSafelyStopsTheRun`; `PlanRevisionTest.reviewerWhoKeepsRequestingUpstreamChangesExhaustsTheRePlanBudget` |
| Fallback | `WorkflowEngineTest.fallbackCapabilityTakesOverAfterPrimaryIsExhausted`; `DegradedVerificationScenarioTest` (unavailable build → static fallback, not retried) |
| Rollback restores state, in reverse order, never destroying other work | `WorkflowEngineTest.safeStopCompensatesAppliedChangesAndRestoresTheBaseline` (workspace hash equals baseline hash); `LineageAndRecoveryTest.reworkHardInvalidatesALaterEditOfTheSameFileAndRollsItBackFirst`; `WorkspaceTest.applyThenRollbackRestoresTheExactBaseline`, `...rollbackRefusesToDestroyLaterModifications` |
| Engine errors become a safe stop, not a stuck run | `FaultBarrierTest.entryGateThatThrowsFailsItsTaskAndHaltsSafelyWithChangesCompensated`, `...rollbackRefusedDuringReworkHaltsTheRunWithAClearReasonInsteadOfEscaping` |
| Rework of defective upstream work, bounded and targeted | `WorkflowEngineTest.codeDefectFoundByVerifierSendsOnlyTheVerifiedWorkBackForRework`, `...reworkIsBoundedByTheTargetsAttemptBudget`; `BuildVerificationHandlerTest` (suspects from compiler errors, introduced symbols, referenced types); `BrownfieldScenarioE2ETest` (only the defective task is reworked after a real test failure) |
| High-impact actions require approval; approval pauses and resumes | `WorkflowEngineTest.highImpactChangePausesForApprovalAndResumesAfterApproval` |
| Approvals are bound to what the reviewer saw | `ChangeGovernanceTest.approvalOfAProposalWhosePostImageChangedSinceItWasShownAsksAgainInsteadOfApplying`, `...approvedChangeIsReEvaluatedByPolicyBeforeItIsApplied`, `...gateApprovalDoesNotCompleteATaskOnATreeThatChangedWhileWaiting`; `ScriptedReviewerTest` (a rule must cover every rule in the request) |
| Rejected or change-requested actions never execute | `WorkflowEngineTest.rejectedChangeIsNeverAppliedAndTheRunStopsSafely`; `ChangeGovernanceTest.requestedChangesOnAChangeSetAreNeverAppliedAndTheNextAttemptAsksAgain` |
| Decisions must fit the checkpoint | `WorkflowEngineTest.decisionsMustFitTheKindOfCheckpoint`; `ChangeGovernanceTest.answersThatDoNotFitTheCheckpointAreRejectedByTheRun`, `...invalidAnswerFromAnImmediateReviewerIsIgnoredAndTheCheckpointStaysOpen` |
| Withdrawn checkpoints are cancelled, never applied later | `HumanCheckpointTest` (withdrawn by invalidation, by an earlier answer, by a safe stop) |
| Policy denial is never applied and is fed back | `WorkflowEngineTest.policyDenialIsNeverAppliedAndIsRetriedWithTheViolationsAsFeedback`; `ChangePolicyTest` (every rule, bypass shapes, self-declared risk ignored) |
| Reasoning cannot bypass governance | `ChangePolicyTest.selfDeclaredLowRiskDoesNotRelaxTheDecision`; `PlanValidatorTest` (unknown capabilities, scopes per capability, checks after every change, missing verification/security/release, fallbacks, retry caps); `StandardGatesTest.stopsForBlockingQuestionsOverconfidentAssumptionsAndUnmeasurableCriteria`; `WorkspaceTest` (path aliases, case aliases, symbolic links) |
| Upstream change invalidates only affected descendants; unrelated work preserved | `WorkflowEngineTest.upstreamChangeInvalidatesOnlyConsumersOfTheChangedArtifact` (execution counts, early cutoff); `PlanRevisionTest.revisedPlanPreservesUnchangedWorkRerunsChangedTasksAndCompensatesRemovedOnes`, `...identicalReproposalKeepsThePlanVersionAndIsNotCountedAsARePlan`; `GreenfieldScenarioE2ETest` |
| Ambiguous requirements trigger human interaction; answers feed the next attempt | `WorkflowEngineTest.clarificationPausesTheRunAndAnswersFeedTheNextAttempt`; `HumanCheckpointTest.twoRoundClarificationAccumulatesAnswersAndTheFinalAttemptUsesTheirValues`; `AmbiguousScenarioE2ETest` |
| Pause/resume across processes; interrupted attempts recovered | `RunStoreTest.pausedRunSurvivesARoundTripThroughDiskAndResumesWhereItStopped`; `AmbiguousScenarioE2ETest` (second runner instance); `LineageAndRecoveryTest.orphanedAttemptThatAppliedAChangeSetIsCompensatedAndReplaysAsTheSameInvocation`; `WorkflowEngineTest.attemptsOrphanedByAnInterruptedProcessRunAgainOnResume` |
| Lineage recorded by the engine and inspectable | `LineageAndRecoveryTest.artifactsOfNonAncestorsAreInvisibleAndNeverRecordedAsInputs`; `WorkspaceFactsTest`; `LineageTest`; `SdlcCliTest` (`lineage` command) |
| Metrics reflect actual execution | `ReliabilityMetricsTest` (success and rollback rates, rollbacks by cause, MTTR, latency with pauses and human wait, concurrency); `SdlcCliTest` (`metrics` on a real run) |
| Release readiness prevents an unsafe or incomplete outcome from being declared ready | `ReleaseAssessmentHandlerTest` (tree identity, lost or regressed baseline tests, degraded verification); `SecurityReviewHandlerTest` (approvals per task and rule, aggregate findings); `DegradedVerificationScenarioTest`; `StandardGatesTest.releaseChecklistBlocksWhenAnyItemFails` |
| Deterministic reasoning replay refuses to improvise | `RecordedReasoningProviderTest` (missing recording, recording made for different human answers, contract violation, content files outside the recording directory) |

### Checking that the tests can fail

The engine, capability and gate tests were checked with **mutation testing**: a deliberate bug is introduced in
a copy of the code (for example "check only direct dependencies instead of all ancestors", "roll back in
application order", "apply an approved change without re-evaluating it", "skip the stale-input check") and the
test that claims the property must fail. Around 55 targeted mutations were run this way, and each was caught
by the test named for its property. One property (an answered checkpoint that was withdrawn is never applied)
is guarded twice, and the test fails only when both guards are removed. Writing these tests also found one
real bug, now fixed: a safe stop left answered-but-unapplied checkpoints in the ANSWERED state instead of
cancelling them. The mutation tooling is not part of the build.

## Codebase reasoning and verification infrastructure

- `IndexerTest`, `ImpactAnalyzerTest`, `ArchitectureRulesTest` run against small fixture modules under
  `orchestrator/src/test/resources` (controllers with class and method mappings, repositories with SQL in
  text blocks, migrations that later add columns, a test that reaches an endpoint only through its HTTP path,
  a deliberately layer-violating module).
- `MavenBuildVerifierTest` fakes the process boundary to cover every outcome (pass, test failure,
  compilation failure, infrastructure error, timeout, missing wrapper, stale reports);
  `ProcessCommandRunnerTest` runs real processes, including a timeout that must kill the process tree and a
  check that the child sees only allow-listed environment variables; `SurefireReportsTest` parses real report
  formats and ignores XXE payloads.

## What is not covered

- The recorded reasoning provider is the only provider; there is no live model integration to test.
- Real-build scenario tests depend on a working JDK and the Maven dependencies cached by the outer build;
  they are opt-in so the default build stays fast and hermetic.
- The static codebase analysis is regex/token based and tested on Spring-style code; other code styles
  are out of scope.
- The policy rules are pattern-based. They are tested against realistic bypass shapes, not proven complete.
- Load, soak and multi-process concurrency (two orchestrators on one run directory) are not tested; runs
  assume a single writer.
