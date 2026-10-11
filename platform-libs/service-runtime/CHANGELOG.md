# Changelog

All notable changes to `service-runtime` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [2.2.0] - 2026-10-12
### Added
- Platform events (INV-REQ-1226, INV-REQ-1163; INV-T-2875): `PlatformEventTypes` (the seven platform types and
  the PolicyChanged, ConnectionStatusChanged and EvidenceArtefactRecorded topics), `EvidenceArtefact` and
  `EvidenceArtefactRecorder` (INV-API-3310: artefact type, reference, SHA-256 hash; the impl half's
  `OutboxEvidenceArtefactRecorder` writes it in the caller's transaction), and the impl half's tolerant
  `PlatformEventReader` for PolicyChanged (INV-API-3306: read straight from the event log, ordered by offset,
  no inbox), ConnectionStatusChanged (INV-API-3308: one report per instance) and EvidenceArtefactRecorded. It
  ignores unknown envelope attributes and payload fields and accepts any minor version of major 1
  (INV-AC-2148); another major or a missing field is a `ContractViolation`. A received connection state outside
  up, degraded and down reads as `ReceivedConnectionState.UNKNOWN` (owner ruling INV-Q-3718), and
  `ConnectionStatusBoard` keeps the worst instance state per connection (down > unknown > degraded > up), the
  latest report per instance winning.
- The operation contract (INV-REQ-957, INV-REQ-1010): `DeclaredOperation` (role and scope check, state
  precondition, repeat key and prior result, effect, audit, reversibility UNDOABLE, COMPENSABLE or
  IRREVERSIBLE), `OperationRegistry` and the impl half's `TransactionalOperationRunner`, which refuses before
  reading state, serialises same-key commands on a transaction-scoped lock on (operation id, repeat key), checks
  the precondition and the repeat in one transaction and commits the audit entry with
  the change. `JobCommand` now carries its contract reversibility.
- runJobNow, pauseJob and resumeJob over HTTP (INV-API-3023, INV-API-3025, INV-Q-3535): `JobCommandServlet`
  at `/internal/v1/jobs/{job}/run|pause|resume` onto `JobCommands`, the actor (`sub`) and tenant
  (`tenant_id`) from the token and the entity set from the authority replica, through the
  `EndUserPrincipalResolver` port (the service's; owner ruling INV-Q-3716 A, default resolver in INV-T-3335),
  the entityRef held to that entity set, JOB-001..004 as problem responses at their statuses and the closed cases (ARC-19) as
  `ClosedErrorCode` SRT-001..SRT-010 at theirs (owner ruling INV-Q-3720, INV-REQ-827 b3), so no response is
  code-less. `IdempotencyStore` and `IdempotencySchema.ddl` keep API-15 keys per (tenant, principal) for at
  least 24 hours: a replay returns the stored response, a different body 422, a request in flight 409.
  `JobCommandHttpAutoConfiguration` registers them when the service has `JobCommands`, an
  `EndUserPrincipalResolver`, a `TenantScope` and a `DataSource` (`service-runtime.idempotency.schema`).
- The L1 composition root (INV-MOD-2371, TPL-18; coordinator rulings INV-Q-3710, INV-Q-3730, INV-Q-3732):
  `ServiceRuntimeConfiguration` now wires every L1 module the service embeds (`composition/`: tenancy-kernel,
  outbox-and-queue, audit-log, config-resolver, evidence-store, adapter-runtime, crypto, rulepack-kernel, and
  service-runtime's own `OperationRunner`, `ConnectionStatusReporter`, `EvidenceArtefactRecorder` and
  `JobRuntime`), each gated by @ConditionalOnClass and overridable by the service (@ConditionalOnMissingBean).
  Implementations needing service-only inputs are created only when the service declares them
  (`ServiceDataSources` bean names, `service-runtime.modules.*`, `gst.service`, `gst.instance-id`); the README
  lists every port. The §12.1 `EventBinding` and `ObjectStore` are selected by `gst.bindings.event-binding`
  (`postgres`) and `gst.bindings.object-store` (`s3-compatible`), the `SecretStore` by
  `gst.bindings.secret-store.implementation` (`kubernetes`), and adapter-runtime's `SecretStore` reads it. A base
  `LicenceCheck` the service declares is wrapped in `NonBlockingLicenceCheck`. adapter-runtime's
  `ConnectionStatusReporter` is a local bean, not bridged: `ConnectionStatusChanged` is published only by
  service-runtime's reporter. rulepack-kernel, evidence-store, adapter-runtime and crypto are compileOnly.

## [2.1.0] - 2026-10-11
### Added
- The job contract (INV-REQ-1136, INV-REQ-1138, INV-REQ-2405; INV-T-2874). The api half has `JobDefinition`
  (name, owner service, entity scope PLATFORM or ENTITY, cron schedule and zone, criticality STANDARD or
  DEADLINE_CRITICAL, a mandatory tolerance, ShedLock leases defaulting to PT10M and PT30S), `JobTarget`,
  `JobCommands` (run now, pause, resume) with `JobCommandAuthorizer`, the `DeadlineSource` port and its
  `FixedDeadlineSource` fake, and `JobErrorCode` JOB-001..004 (gst-catalogue 1.5.0).
- The impl half's `JobRuntime`: `JobRegistered` once per job at start, one `JobRunRecorded` per run, ShedLock
  leader election on PostgreSQL with database time (one lock per job, or per job and entity), and a sweeper
  that records a run abandoned by a dead instance as FAILED `abandoned` (INV-Q-2948).
- Run now, pause and resume by role (INV-Q-2985, INV-Q-3032): tenant roles act per (job, entity); a
  whole-job command or any command on a PLATFORM-scoped job needs the platform operator role (JOB-002). A
  run-now repeated in its run window, the schedule interval containing the request, returns the first run;
  after FAILED a new attempt runs (INV-Q-3034). Every pause has an end time; a deadline-critical pause is
  refused past the nearest due date minus the safety margin (JOB-003) and lapses at that bound, re-checked
  at every firing (INV-Q-2946). A different end time replaces the old one and is audited with it (INV-Q-3037).
- `JobSchema.ddl`: `job_run` and `job_pause` under forced row-level security with a worker role for
  cross-tenant reads (never BYPASSRLS), and `shedlock`, granted to the worker only. `purge()` deletes runs and
  ended pauses older than the retention window (default 30 days); a window shorter than a job's interval
  plus tolerance is refused at registration.
- The connection contract (INV-REQ-834..836): `ConnectionRecord`, `ConnectionTester` with code-only
  results (INV-Q-2944), `ConnectionHealth`, and `OutboxConnectionStatusReporter`, which publishes
  `ConnectionStatusChanged` per instance when its own breaker-mapped state changes (CLOSED up, HALF_OPEN
  degraded, OPEN down).

### Changed
- service-runtime now depends on outbox-and-queue and audit-log (the architecture test allows them).

## [2.0.0] - 2026-10-11
### Changed (breaking)
- `PolicyReplica` resolves over config-resolver's scope chain (INV-REQ-1242 behaviour #1;
  INV-T-3002, INV-Q-2968 B2): installation, tenant, group, entity, feed, Access Point
  connection, client entity. `PolicyChanged.scope` and `PolicyReplica.valueAt` take
  config-resolver's `PolicyScope`, so INV-REQ-1241's per-AP-connection example works. The
  replica's own `PolicyScope` (entity, tenant, platform) is removed.
- Ordering and lag use the event-log offset per producer topic, not a payload version:
  `PolicyChanged.version` is replaced by `topic` and `offset`; the replica implements the new
  `PolicyFeedSink` (every position given per topic) instead of `ReplicaFeedSink`; `lag()` is the
  worst over the topics known, `lag(topic)` and `appliedOffset(topic)` are new, and the public
  `tracker` is gone. Offsets of different topics are not compared.
- The replica resolves exactly like config-resolver 1.0.0's `PrecedenceResolver` (the resolver sits in config-resolver's
  impl half and needs a catalogue, so it cannot be reused here): an override at any level beats every profile
  value, a profile value beats the default, and within a source the most specific level wins; at one
  level the latest effective date on or before the date, then the later offset.
- New `service-runtime.replica.policy-topics`: the policy replica's lag stays unknown until every listed topic
  has synced.
- service-runtime-api now depends on `config-resolver-api` (INV-MOD-2371: the composition root may
  use the other L1 modules).

## [1.2.0] - 2026-10-11
### Added
- The replica mechanism (INV-REQ-1199, INV-ADR-284; INV-T-2873 / T29), package `replica`:
  - `EventFedReplica`: a generic event-fed local replica with lag measured by
    `ReplicaLagTracker` from the source's monotonic version, an optional lag
    bound, a fallback call to the owning service, cache-miss fill, and
    serve-last-known when the fallback is down. Held in memory; after every restart lag
    stays unknown (so a bounded replica uses its fallback) until the feed reports a
    full sync or snapshot marker (`onSnapshotComplete`).
  - `StructureReplica` (INV-REQ-1200): `OrganisationStructureChanged` and
    `EntityProfileChanged`; a miss is filled from `tenancy-svc`.
  - `AuthorityReplica` (INV-REQ-1201): `AuthorityChanged`; a decision is local at
    lag of at most one second, else one call to `access-svc`; when both fail
    humans and consent-derived agents are denied and machine flows continue.
  - `PolicyReplica` (INV-REQ-1241): `PolicyChanged`, effective-dated, read from the
    most specific scope; event-only, no fallback call.
  - `ReplicaAutoConfiguration`: wires the three; fallbacks run through the
    `tenancy-svc` and `access-svc` resilience edges (500 ms, one retry).
### Changed
- service-runtime now depends on `tenancy-kernel`'s `TenantId`: it is the L1
  composition root and may use the other L1 modules (INV-MOD-2371). Its
  `ArchitectureTest` allows that one import and still refuses the others.

## [1.1.0] - 2026-09-27
### Added
- ARC-08/STK-24's `ResilienceRegistry` (INV-REQ-1204): one `ResilientEdge` per
  internal (§2.5) edge, Resilience4j core composed the same way
  `adapter-runtime`'s own external-edge executor is (INV-T-2862 / T18) — timeout,
  jittered retry only where an edge is configured idempotent (CS-16), circuit
  breaker and a semaphore bulkhead on virtual threads (`Resilience4jResilientEdge`,
  `ResilienceProperties`), every value provisional until its own §13.7
  performance test confirms it.
- `Deadline` (INV-REQ-1203, FR-AH.7) and API-29's `X-Deadline-Ms` propagation:
  `ResilientEdge.execute` binds the caller's remaining budget around the call so
  a service's own generated `RestClient` client automatically carries it
  (`DeadlinePropagationInterceptor`); `DeadlineServerFilter` reconstructs it on
  the callee, with a provisional default budget for a chain's own entry point.
  The budget already being exhausted before a hop is even attempted returns the
  same typed timeout fallback as a real timeout, never an exception.
- Workload identity (INV-REQ-1202, STK-26, INV-AC-2200): `WorkloadTokenSource`/
  `ProjectedServiceAccountTokenSource` reads this pod's own projected
  service-account token fresh on every call (STK-29's own no-cache discipline);
  `WorkloadTokenVerifier`/`JwtWorkloadTokenVerifier` verifies a caller's token
  (signature, issuer, audience) the same Nimbus-based way STK-27's future
  end-user resource server will; `WorkloadTokenAuthenticationFilter` refuses a
  request with no valid token before it reaches any handler.
  `EndUserTokenCaptureFilter`/`EndUserTokenPropagationInterceptor` forward the
  inbound request's own token to every internal call it causes, so the owning
  service can re-check the write (INV-REQ-871).
- `InternalRestClientAutoConfiguration`'s `RestClient.Builder` bean: the
  "generated RestClient wrapper" a service builds every ARC-08 client from
  (TPL-07), pre-wired with deadline, workload-token and end-user-token
  propagation — a generated client needs no interceptor wiring of its own.
- `ServiceRuntimeConfiguration` now also imports `ResilienceAutoConfiguration`,
  `IdentityAutoConfiguration` and `InternalRestClientAutoConfiguration`.

## [1.0.0] - 2026-09-27
### Added
- Spring Boot 4.1 bootstrap auto-configuration (`ServiceRuntimeConfiguration`,
  TPL-18's composition root), activating on the classpath alone
  (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`)
  and still explicitly `@Import`-able the way TPL-18's own template shows.
- The closed set of SDS §12.1 binding interfaces (FR-H.1, INV-ADR-276): `IdentityProvider`,
  `PackSource`, `TelemetryExport`, `EventBinding`, `MessageDelivery` and `SecretStore`
  (marked `Binding`, checked closed by a Konsist test). `LicenceCheck` and `ObjectStore`
  are not redeclared here — they already canonically live in `tenancy-kernel`
  (INV-T-2861 / T17) and `evidence-store` (INV-T-2863 / T19) respectively.
- `SecretStore` port and its mounted-file implementation (`MountedFileSecretStore`,
  STK-29, CS-24): reads mounted files only, never caches (so rotation needs no
  restart), and a service reads only its own secret paths — structurally
  (`SecretRef` refuses to escape the mount root) and operationally (each
  service's own volume mount). A second, narrower `SecretStore` for adapter
  credentials specifically already ships in `adapter-runtime` (INV-T-2862 / T18),
  predating this module's interfaces — flagged in this task's merge request for
  the coordinator to reconcile, not changed here (out of declared scope).
- Liveness/readiness with a `DEGRADED` status distinct from `DOWN` (FR-AH.17):
  `VaultHealthIndicator` (the vault third of "schema, event log and vault"; a
  missing mount reports `DOWN`, failing visibly per §36.7) and the reusable
  `DomainDependencyHealthIndicator` (a domain dependency being unavailable
  reports `DEGRADED`, never `DOWN` — `DegradedStatus`).
- The uniform diagnostic endpoint (`DiagnosticsEndpoint`, FR-AE.82: version,
  health, recent error codes, queue and outbox lag — the latter two `null` until
  `outbox-and-queue` ships its own depth sources) and the per-code error counter
  (`ErrorCodeMetrics`/`MicrometerErrorCodeMetrics`, FR-O.8), proved against
  INV-AC-2172 (25 raises of `RUL-001` for one entity and period increment that
  counter by exactly 25).
- ECS structured logging (`logging.structured.format.console=ecs`, STK-35) with
  an MDC allow-list closed to six keys (`MdcKey`, `MdcContext`) enforced twice
  over: the typed API accepts no other key, and `MdcAllowListTurboFilter` strips
  any other key set some other way — proved by a masked-field test
  (`MdcAllowListTurboFilterTest`) that a raw `MDC.put` of an invoice-content
  field never reaches an emitted log event. `TraceParent` (W3C Trace Context)
  and `CorrelationContext`/`CorrelationId`/`CausationId` (§11.6) are the
  primitives a future platform-event envelope writer reads from.
- Architecture tests (Konsist, STK-41, the adapter-runtime `scopeFromModule`
  pattern): this module imports no other L1 module (it is first in §18.9's
  `L1_MODULE_ORDER`), and every `Binding`-marked interface it declares is one of
  FR-H.1's eight names.
- `platform-libs-bom` gains this module's `api`/`impl` constraint pair, first in
  STK-60's canonical L1 order.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
