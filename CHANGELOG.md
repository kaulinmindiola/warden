# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-11-02

Milestone M5: release 1.0. Warden runs with one command next to its dependencies, simulated
providers, Prometheus and Grafana; its telemetry carries no secret; every merge to `main` publishes
an image; and every requirement of release 1.0 is verified, with the scope moved to release 1.1
recorded in the SRS.

### Added

- Metrics on the management port: logins, refreshes and detected reuses, revoked sessions by cause,
  authorization denials by status and rate-limit rejections; deliveries by status and scope,
  attempts, latency and result per provider, circuit breaker state per provider, pending outbox
  events and outbox lag. Labels take fixed values only.
- Structured JSON logs with `traceId`, `userId`, `workspaceId` and, in the workers, the
  `originTraceId` of the request that produced the event. Refresh and link tokens, JWTs and e-mail
  addresses are masked in every value; request details are never logged.
- Telemetry scan (FF-09): after a flow that issues every kind of secret, no log line or metric
  label holds a secret, an e-mail address, an identifier or an IP address.
- Graceful shutdown: requests in flight complete within 30 s per phase, new ones are refused, and
  the workers stop claiming events and deliveries as soon as shutdown starts.
- Container image: multi-stage and layered on Temurin 21, base images pinned by digest, unprivileged
  user 10001, heap limited to 60 % of the container memory and exit on out-of-memory. Its entry
  point generates the keys of the reference environment into a volume on the first start.
- Reference environment: `docker compose up -d --wait` starts Warden, PostgreSQL, Redis, two
  simulated e-mail providers, Prometheus and Grafana, every image pinned by digest; the `scale`
  profile adds a second instance on port 8082. The management port is never published.
- Prometheus with five alert rules tested with `promtool`, and the provisioned Grafana dashboard
  "Warden — Access & Delivery".
- CI: every push to `main` publishes `ghcr.io/kaulinmindiola/warden:<commit>` with an SBOM and a
  provenance attestation; pull requests run a dependency review that blocks vulnerabilities of high
  severity; Dependabot proposes Maven, actions, Docker and Compose updates every week; every build
  writes a licence report of the runtime dependencies.
- OpenAPI document generated from the code and kept in step by the build; Bruno collection of 45
  requests over the 31 operations of the API; local environment guide for PowerShell and POSIX
  shells; log correlation guide.
- Build checks for the problem type catalogue against the code and for the checksums of merged
  migrations (REQ-CM-003).
- Threat model v4 covering telemetry, the image, the reference environment, the supply chain and
  graceful shutdown; final problem type catalogue with its stability rules.
- README with the guarantees and the tests that prove them; complete verification matrix; ADR-018
  accepted and the ADR index final, with all twenty decisions accepted.

### Changed

- SRS version 0.2: REQ-FUNC-010, 053 and 054, REQ-PERF-001 to 005, REQ-MAINT-005, REQ-COMP-003, the
  statistical analysis of REQ-SEC-010 and the template-change audit action of REQ-SEC-015 move to
  release 1.1; no requirement text changes.

### Fixed

- Migration V20 creates the partial index on the expiry of sealed link tokens that V18, merged
  empty, never created.

### Security

- Open critical vulnerabilities at release: none (Dependabot alerts, 2026-11-02).

### Known limitations

- Deferred to release 1.1: workspace template customization and versioning, the new-device notice,
  the load-test campaign, the timing analysis of REQ-SEC-010, event upcasting and the OWASP ASVS
  mapping.
- The reference environment is verified on `linux/amd64`; `linux/arm64` is not yet demonstrated.
- Grafana in the reference environment admits anonymous viewers and has a default admin password;
  it is published on `127.0.0.1` only.
- Delivery towards a provider is at-least-once; the simulators do not deduplicate by idempotency
  key.

## [0.5.0] - 2026-11-01

Milestone M4: notifications. Every event of the catalogue becomes deliveries that are rendered and
sent through a primary or a secondary provider, and every delivery ends `SENT`, `DEAD` or
`CANCELLED`, also across crashes, restarts and two running instances.

### Added

- Outbox dispatch every 500 ms in `SKIP LOCKED` batches, in insertion order and in one transaction
  with the notification handler; sealed link tokens are removed when an event is published.
- Global templates for 7 keys in Spanish and English, rendered by a placeholder-only renderer that
  rejects undeclared variables and any other construct and inserts values literally.
- Idempotent fan-out: one delivery per event, recipient and channel, with the template version
  pinned, the recipient's locale and the informational-notice preference. Revoked, replaced or
  accepted invitations and deleted workspaces cancel their unsent deliveries.
- `GET` and `PUT /api/v1/me/notification-preferences`: locale, and `workspace.role-changed` per
  workspace.
- Two HTTP e-mail providers with 2 s connect and 5 s answer timeouts and result classification, a
  circuit breaker per provider and failover within the same attempt; WireMock simulators in
  Compose (ports 8091 and 8092) and in the tests.
- Delivery worker: leased `SKIP LOCKED` claims, 8 concurrent sends on virtual threads, backoff of
  1, 2, 4 and 8 s with ±50 % jitter, `DEAD` after 5 attempts or a permanent rejection, links with
  the token only in the URL fragment and configurable base URLs, and sealed tokens dropped on
  `SENT`, on `CANCELLED` and once expired. Metric `warden_notification_delivery_outcomes_total`.
- `GET /api/v1/workspaces/{workspaceId}/notifications/deliveries` and `POST
  …/deliveries/{deliveryId}/retry` for ADMINs, without message content; retries are audited as
  `DELIVERY_RETRIED`.
- Daily purges: published events after 7 days, single-use tokens 7 days after expiry, and dead
  sessions with their refresh tokens after 30 days.
- Evidence tests: 1,000 events dispatched and sent by two instances at once, session flows
  alternating between instances, business operations during a provider outage, resumption by an
  instance started later, and re-claim after a lapsed lease with the same idempotency key.
- Threat model v3 covering the notification pipeline.
- ADR-008, ADR-009, ADR-015 and ADR-016 accepted.

### Changed

- Integration tests share two in-process WireMock providers serving the mappings of
  `ops/providers`.
- `resilience4j-timelimiter` removed: the JDK HTTP client bounds every provider call.
- `workspace.InvitationAccepted` now cancels the unsent deliveries of the invitation.

### Known limitations

- Workspace template customization (REQ-FUNC-053, 054) and its `TEMPLATE_CHANGED` audit record are
  part of release 1.1; preferences cover only `workspace.role-changed`.
- Delivery towards a provider is at-least-once; the simulators do not deduplicate by idempotency
  key.
- The REQ-SEC-010 timing analysis is still pending.

## [0.4.0] - 2026-10-30

Milestone M3: abuse protection and degradation. The sensitive endpoints are rate limited, Redis
and PostgreSQL failures have a defined, tested behaviour, and the hardening requirements hold.

### Added

- Rate limits with the SRS thresholds on Bucket4j token buckets refilled in full once per window:
  5 failed logins per e-mail in 15 minutes (checked before Argon2, so even the right password is
  rejected), 20 logins and 60 refreshes per IP per minute, 3 reset requests per e-mail and 10 per
  IP per hour, 3 verification resends per account per hour, and 50 invitations per workspace and
  100 per inviter per day. Rejections answer 429 `rate-limited` with `Retry-After`, never reveal
  whether an e-mail has an account and are counted in `warden_ratelimit_rejections_total`.
- Degraded modes: with Redis down, revocation checks go to PostgreSQL and the revocations of the
  last 630 seconds are rewritten before leaving degraded mode, with a reconciliation every 30
  seconds; rate limits use buckets local to the instance. Gauges `warden_revocation_degraded` and
  `warden_ratelimit_degraded`.
- PostgreSQL unavailable: connections time out after 2 seconds and every request that needs the
  database answers 503 `service-unavailable` with `Retry-After: 5`, including the token check when
  Redis is down too; readiness depends only on PostgreSQL and the application state.
- Audit retention: `purge_audit_log`, the only way to delete audit records, never deletes one
  younger than 365 days; a daily job runs it.
- The `prod-like` profile: TLS-only connector, `Strict-Transport-Security` for one year, and
  mandatory keystore and keys.
- Evidence tests: fail-closed authorization with each fault injected on every workspace endpoint,
  one complete audit record without secrets per audited action, a scan of API responses for
  secrets with a gate on every read endpoint, and outage tests with the Redis and PostgreSQL
  containers frozen.
- Threat model v2 covering abuse protection, degraded modes, audit retention and transport.
- ADR-007 and ADR-014 accepted.

### Changed

- Redis commands time out after 200 ms instead of 60 s.
- The workspace authorization interceptor answers 503 with `Retry-After: 5` when memberships cannot
  be read.
- Integration test pools keep one idle connection, and the test PostgreSQL allows 300 connections.

### Known limitations

- Audit records for template changes and dead-letter retries (REQ-SEC-015), templates without
  execution (REQ-SEC-013) and rendered notification content in the response scan (REQ-SEC-014)
  arrive with the notification module in M4; they are recorded as exceptions to the M3 exit
  criteria.
- The REQ-SEC-010 timing analysis is pending; only its mechanism is in place.

## [0.3.0] - 2026-10-28

Milestone M2: multi-tenancy and RBAC. Workspaces, memberships, roles, invitations and notes work
through the API, and tenant isolation holds in three layers that the build itself verifies.

### Added

- Workspaces: creation by users with a verified e-mail, listing with the caller's role, reading,
  and logical, irreversible deletion by an OWNER that revokes pending invitations.
- Roles VIEWER < MEMBER < ADMIN < OWNER resolved from the current membership on every request,
  never from the token: non-members, foreign and deleted workspaces get a 404 identical to a
  random id, and an insufficient role gets 403.
- Member management: listing, role changes with authority rules, removal and leaving, with the
  last OWNER protected under concurrency by a workspace row lock.
- Invitations: creation with a sealed, single-use, 7-day link; re-invitation that replaces the
  previous link; listing without tokens; revocation; and acceptance only by the verified invited
  address, without consuming the token when another account tries it.
- Notes as the reference workspace resource, with optimistic versioning and 409 on stale edits.
- Tenant isolation in three layers: the workspace authorization interceptor, the Hibernate tenant
  filter and forced PostgreSQL Row Level Security with transaction-local context, plus a
  token-holder policy for accepting invitations and column-level privileges on invitations.
- Build gates: every workspace endpoint must be in the isolation registry (R7) and declare its
  minimum role (FF-05); the isolation suite (foreign and deleted workspaces) and the role matrix
  run against every registered endpoint.
- Evidence tests: Row Level Security proven with raw JDBC as the runtime role; 50 repetitions of
  concurrent note edits and of concurrent demotions of two OWNERs; the R7 gate shown failing in
  CI on a disposable pull request; the plaintext-secret scan now includes invitation tokens.
- Workspace events in the outbox (`MemberInvited`, `InvitationRevoked`, `InvitationAccepted`,
  `MemberRoleChanged`, `WorkspaceDeleted`) and audit records for every workspace action.
- Threat model v1 covering tenancy and invitations.
- ADR-004 and ADR-005 accepted.

## [0.2.0] - 2026-10-26

Milestone M1: the identity core. Accounts, sessions and tokens work end to end through the API,
and none of the flows reveals whether an e-mail has an account.

### Added

- Shared kernel: RFC 9457 problem details with a fixed error catalogue, the `/api/v1` prefix,
  pagination, UUIDv7 identifiers, UTC timestamps, a 64 KiB request limit, structured JSON logs
  and deny-by-default security.
- Key material for ES256 signing, AES-256-GCM sealing and HMAC, with ephemeral keys only in the
  local profile.
- Secure 256-bit tokens, SHA-256 token hashes, an append-only audit log and the write side of the
  transactional outbox.
- Registration with e-mail normalization, a password policy with a common-password list and
  bounded Argon2id hashing; identical responses for new and existing e-mails.
- E-mail verification with single-use, 24-hour links, and resending them.
- Login with ES256 access tokens and server-side sessions; strict token validation.
- Session listing, termination of one session and logout, rejected on the next request through
  a Redis denylist with a PostgreSQL fallback.
- Refresh-token rotation with a single conditional update, reuse detection that revokes the
  session with exactly one alert, and expiry without alerts.
- Password reset with single-use, 30-minute links; a reset revokes every session and verifies
  the e-mail.
- A configurable minimum response time for registration and password reset requests.
- Build gate: 80 % line coverage of the domain and application layers.
- Evidence tests: 50 repetitions of four concurrent refreshes with a single winner, and a scan
  of every database column for plaintext secrets.
- Threat model v0 for authentication and sessions.
- ADR-002, ADR-003, ADR-006, ADR-010, ADR-011, ADR-012, ADR-017 and ADR-019 accepted; ADR-008
  proposed.

## [0.1.0] - 2026-10-25

Milestone M0: the project builds reproducibly and its two riskiest design assumptions are proven.
No API endpoint is implemented yet.

### Added

- Spring Boot 4.1.2 application skeleton with five modules (`iam`, `workspace`, `notes`,
  `notification`, `shared`) whose boundaries are verified by Spring Modulith and ArchUnit.
- Build gates in `./mvnw verify`: pinned JDK, Maven and dependency versions, formatting,
  separate unit and integration test phases, and a coverage report.
- Continuous integration on every pull request and every push to `main`; `main` requires a
  passing `verify` check.
- Local PostgreSQL 17 and Redis 8 with Docker Compose, and the database role split: an owner
  role for migrations and a runtime role subject to Row Level Security.
- Flyway baseline migration applied at startup as the schema owner.
- Shared integration test infrastructure on Testcontainers that connects as the runtime role,
  with a controllable test clock.
- POC-001: compatibility of every planned library with Spring Boot 4.1.
- POC-002: tenant context does not leak across pooled database connections.
- ADR-001, ADR-013 and ADR-020 accepted; ADR-005 proposed.

[Unreleased]: https://github.com/kaulinmindiola/warden/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/kaulinmindiola/warden/compare/v0.5.0...v1.0.0
[0.5.0]: https://github.com/kaulinmindiola/warden/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/kaulinmindiola/warden/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/kaulinmindiola/warden/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/kaulinmindiola/warden/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/kaulinmindiola/warden/releases/tag/v0.1.0