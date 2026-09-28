# Scenario: ambiguous requirement

Requirement: *"Short links should expire. Expired links should be handled appropriately."*

```bash
./sdlc run ambiguous                                              # exit code 3: paused for clarification
./sdlc resume <run-id> --answers scenarios/ambiguous/answers.yaml # continues to COMPLETED / READY
# or, from another paused run, the second recorded direction:
./sdlc resume <run-id> --answers scenarios/ambiguous/answers-global-ttl.yaml
```

The first command stops at the `requirement-quality` gate with four questions:

| Id | Raised by | Why |
|---|---|---|
| Q-1 | requirement analysis | How a link gets its expiry (per link, global lifetime, or both) |
| Q-2 | requirement analysis | What following an expired link returns (410, 404 or a notice page) |
| A-1 | the gate | Analysis treated "existing links expire retroactively" as settled: HIGH impact, unconfirmed |
| AC-3 | the gate | "Expired links are handled appropriately" cannot be verified by a test |

Codebase scan and baseline build do not depend on the requirement, so they finish before the pause
and are not repeated after resuming.

The answers are passed to every reasoning step after the pause and recorded as an input of the
specification, impact analysis, plan, design and change sets. Two sets of option answers are recorded:

| Answers | Q-1 | Q-2 | A-1 | Recordings |
|---|---|---|---|---|
| `answers.yaml` | `per-link` | `410` | `reject` | `recordings/` (`requirements.2.yaml` pins them under `expect`) |
| `answers-global-ttl.yaml` | `global-ttl` | `404` | `confirm` | `recordings/variants/global-ttl/` (selected by its `variant.yaml`) |

Any other combination makes the offline provider stop with `REASONING_UNAVAILABLE` before planning,
rather than replay an analysis made for different decisions. The free-text answer to AC-3 is not
checked.

**Per-link expiry (`answers.yaml`).** After resuming, the run designs the change (sign-off required
for the data model change), adds migration `V2__add_expires_at_to_short_link.sql` (approval under
CC-02), implements validation and the 410 path (approval under SEC-04, because existing
request-handling classes change: `LinkController`, `CreateLinkRequest` and `LinkResponse` gain
`expiresAt`, and `ApiExceptionHandler` gets the new `invalid-expiry` mapping and the `link-gone`
title, generalised from "Link disabled" to "Link gone"), documents `expiresAt` in `openapi.yaml` and
the README, adds tests for AC-1..AC-5 and updates the existing tests impact analysis selected
(including `RedirectIntegrationTest`, which calls the redirect endpoint and pins that title), runs the
real test suite and the security and API reviews, and asks for release sign-off.

**One configured lifetime (`answers-global-ttl.yaml`).** The specification changes to a lifetime for
every link, existing links included, with 404 `link-not-found` for expired links and no API field. The
design has no data model change, but its retroactive-expiry decision (D-2) is HIGH impact and needs
sign-off. The plan has a single implementation task, `impl-lifetime`, instead of `impl-storage` and
`impl-lifecycle`: it adds `shortener.link-lifetime` (365 days) to `ShortenerProperties` and
`application.yml` (approval under CC-09) and enforces it in `LinkService`. The tests task updates `LinkServiceTest` and
`TargetUrlValidatorTest`, which construct `ShortenerProperties`, and the run ends READY after the real
test suite, reviews and release sign-off.
