# URL Shortener

A Spring Boot service that creates short links, redirects visitors to their targets and reports
click statistics.

## Running

```bash
cd shortener && ../mvnw spring-boot:run     # http://localhost:8080
cd shortener && ../mvnw test                # full test suite, in-memory database
```

Data is kept in an H2 file database under `shortener/data/`. Delete that directory to start from
scratch.

| Property                                  | Default                 | Meaning                                              |
|-------------------------------------------|-------------------------|------------------------------------------------------|
| `shortener.base-url`                      | `http://localhost:8080` | Public origin used to build `shortUrl`               |
| `shortener.code-length`                   | `7`                     | Length of generated codes (4-32)                     |
| `shortener.max-code-generation-attempts`  | `5`                     | Collision retries before answering 503               |
| `shortener.stats.default-days`            | `7`                     | Statistics window when `days` is omitted             |
| `shortener.stats.max-days`                | `90`                    | Largest accepted statistics window                   |

## API

The full contract is in [`src/main/resources/static/openapi.yaml`](src/main/resources/static/openapi.yaml),
served at `GET /openapi.yaml`. `OpenApiContractTest` fails if the document and the controllers disagree
on the set of endpoints.

| Method   | Path                              | Purpose                                         |
|----------|-----------------------------------|-------------------------------------------------|
| `POST`   | `/api/v1/links`                   | Create a link (201; 200 on idempotent replay)   |
| `GET`    | `/api/v1/links/{code}`            | Read a link                                     |
| `DELETE` | `/api/v1/links/{code}`            | Disable a link (204, idempotent)                |
| `GET`    | `/api/v1/links/{code}/stats`      | Click statistics (`?days=1..90`, default 7)     |
| `GET`    | `/{code}`                         | Redirect (302)                                  |

Actuator exposes `/actuator/health`, `/actuator/info` and `/actuator/metrics` (including
`shortener.links.created`, `shortener.redirects{outcome}` and `shortener.analytics.dropped`).

```bash
# Create with a generated code
curl -i -X POST localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.org/articles/2026/launch"}'

# Create with a custom alias; safe to retry thanks to the Idempotency-Key
curl -i -X POST localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: launch-post-1' \
  -d '{"url": "https://example.org/articles/2026/launch", "alias": "launch-2026"}'

# Read it
curl localhost:8080/api/v1/links/launch-2026

# Follow it (302 with Location and Cache-Control: no-store)
curl -i -H 'Referer: https://news.example.com/today' localhost:8080/launch-2026

# Statistics for the last 30 days
curl 'localhost:8080/api/v1/links/launch-2026/stats?days=30'

# Disable it; the redirect then answers 410 Gone
curl -i -X DELETE localhost:8080/api/v1/links/launch-2026
```

### Errors

Every error is an RFC 9457 problem document (`application/problem+json`) with `type`, `title`,
`status`, `detail` and `instance`; stack traces are never returned. Application problems use these
stable types under `https://example.com/problems/`:

| Type                      | Status | When                                                           |
|---------------------------|--------|----------------------------------------------------------------|
| `validation-failed`       | 400    | Body fails bean validation; includes `errors: [{field, message}]` |
| `malformed-request`       | 400    | Body missing or not JSON                                       |
| `invalid-parameter`       | 400    | Query parameter of the wrong type                              |
| `invalid-target-url`      | 400    | URL not absolute http(s), has user info, no host, too long, or points at the shortener |
| `invalid-alias`           | 400    | Alias not `[A-Za-z0-9_-]{4,32}` or a reserved word             |
| `invalid-idempotency-key` | 400    | Key not `[A-Za-z0-9_-]{1,64}`                                  |
| `invalid-stats-window`    | 400    | `days` outside 1..`max-days`                                   |
| `link-not-found`          | 404    | Unknown code                                                   |
| `alias-unavailable`       | 409    | Alias already used, including by a disabled link               |
| `link-gone`               | 410    | Redirect to a disabled link                                    |
| `idempotency-key-reuse`   | 422    | Key reused with a different payload                            |
| `code-generation-failed`  | 503    | No free code found within the retry budget; safe to retry      |
| `internal-error`          | 500    | Anything unexpected (details are only logged)                  |

Errors that mean nothing beyond their HTTP status (unknown route, wrong method, unsupported media
type) omit `type`, which RFC 9457 defines as `about:blank`.

## Design notes

**Code generation and collisions.** Generated codes are 7 random base62 characters from a
`SecureRandom` (about 3.5 * 10^12 combinations), so they cannot be enumerated. The service does not
check for a free code before inserting, because that check would race with concurrent requests.
Instead the `uk_short_link_code` unique constraint decides: on a `DuplicateKeyException` a new code is
drawn, up to `max-code-generation-attempts` times, and after that the request fails with 503. Custom
aliases share the same namespace and are never retried; a taken alias is a 409. Aliases are
case-sensitive, and reserved words (`api`, `admin`, `actuator`, ...) are rejected in any casing so that
top-level routes cannot be shadowed; a generated code that happens to spell one is discarded like a
collision. Link creation deliberately runs without a surrounding
transaction: PostgreSQL aborts a transaction after its first failed statement, which would make
retrying the insert impossible.

**Idempotency.** `POST /api/v1/links` accepts an optional `Idempotency-Key`. The key and a SHA-256
hash of the payload (URL and alias) are stored on the link row, under a unique constraint. A repeat
with the same key and payload returns the original link with `200` and `Idempotent-Replayed: true`,
without creating anything. A repeat with a different payload is rejected with 422. When two requests
with the same key race, the loser's insert hits the unique constraint; the service then re-reads the
winner's row and answers as a replay (or with 422). Keys do not expire.

**302 instead of 301.** Browsers and intermediaries cache a 301 without limit, so later clicks would
never reach the service. Analytics would undercount without any sign of it, and a disabled link would
keep working for everyone who had already followed it. Redirects are therefore `302 Found` with
`Cache-Control: no-store`. HEAD requests are redirected but not counted, because link-preview bots and
uptime checks use them.

**Analytics privacy.** Each click stores only the link, a timestamp and the lower-cased referrer
*host*. Referrer paths and query strings often contain search terms or personal identifiers, and IP
addresses and user agents are personal data that the statistics do not need, so none of these are
stored. Statistics use UTC calendar days. The daily series is zero-filled, and the top 5 referrers are
ordered by clicks, with ties broken by host name.

**Analytics failure isolation.** The redirect is what users see; analytics are best effort.
`ClickRecorder` catches any `DataAccessException` while writing a click, logs a WARN with only the
link id and exception types (never request data), increments `shortener.analytics.dropped` and lets
the redirect proceed.

**Soft delete.** `DELETE` only marks a link `DISABLED` and records `disabledAt`. The row stays, so
statistics remain available and the code is never handed out again. A new link could otherwise
inherit old traffic and bookmarks.

**Persistence.** Plain SQL through Spring's `JdbcClient`, with the schema managed by Flyway
(`db/migration`). The tables are small, the queries are simple, and explicit SQL keeps them easy to
review. H2 runs in PostgreSQL compatibility mode, and the migrations and queries avoid H2-only syntax:
identity columns, named constraints, `LIMIT`, `EXTRACT(EPOCH ...)` for UTC day buckets, and timestamps
bound as UTC `OffsetDateTime`, which both JDBC drivers support. Moving to PostgreSQL should mostly be
a matter of adding the `org.postgresql:postgresql` and `flyway-database-postgresql` dependencies and
changing `spring.datasource.*`. Clicks are indexed on `(link_id, occurred_at)` for the per-link range
queries.

### Known limitations

- No authentication or authorization: anyone who can reach the API can create or disable any link.
  Deployments must put it behind a gateway.
- No rate limiting or abuse protection. That belongs in the gateway or edge layer in front of the
  service.
- Idempotency keys are global, because there is no client identity to scope them by: two clients
  that pick the same key share it (the second gets a replay or a 422).
- A click is written synchronously within the redirect request. A slow database therefore slows
  redirects, even though a failing one does not break them. A queue or buffered writer would decouple
  the two at the cost of possible loss on crash.
- Statistics are computed on read from raw click rows. That is fine at this scale, but high-traffic
  links would need pre-aggregated daily counts.
- Target hosts must be ASCII (internationalized domain names need their punycode form).
- Links do not expire, visitors are not de-duplicated, and there is no abuse reporting.
