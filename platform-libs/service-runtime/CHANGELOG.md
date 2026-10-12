# Changelog

All notable changes to `service-runtime` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [3.3.0] - 2026-10-12
### Added
- Database credentials through SecretStore, rotated without restart (INV-T-3913; STK-29, INV-REQ-1231; coordinator
  ruling INV-Q-3909, gap G10): `SecretStoreCredentialsProvider`, a HikariCP `HikariCredentialsProvider` from a
  `SecretStore`, a username and a password `SecretRef`. `getCredentials()` reads the SecretRef on every call and never
  caches, so each new physical connection logs in with the password mounted at that moment; open connections keep
  theirs until the pool retires them. A role pool sets it as its `credentialsProvider` (TPL-18).
- `service-runtime.datasource.credential`: Spring Boot's application HikariDataSource takes the provider, with
  `spring.datasource.username`, before its pool starts. `service-runtime.flyway.credential`: Flyway's owner-role
  connection (`spring.flyway.user`) reads its password through SecretStore when the migration runs. Start-up fails if
  `spring.datasource.password` / `spring.datasource.hikari.password` or `spring.flyway.password` is set as well.
  Without the properties nothing changes. No password reaches a log, an exception message or `toString` (CS-24).
- Tests: rotation on PostgreSQL 17 and 18 in one TPL-18 context (application pool and role pool; the revoked password
  is refused, the new one used after `softEvictConnections`), Flyway's migration through SecretStore, and the wiring.

## [3.2.1] - 2026-10-12
### Fixed
- The L1 composition depended on registration order under TPL-18. This is a defect in INV-T-2875 against INV-Q-3710,
  found in the review of INV-T-2876 (coordinator rulings INV-Q-3877 and INV-Q-3889, INV-T-3881).
  - ServiceRuntimeConfiguration imported the module wirings, which decide by registration-time `@ConditionalOnBean`.
    Under TPL-18's `@Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class)` both roots are
    user configuration, registered before every auto-configuration.
  - The application `DataSource` that Spring Boot's DataSourceAutoConfiguration creates from `spring.datasource.*`
    (TPL-16, TPL-14) was therefore not yet registered. The service got no `TransactionRunner` or `CurrentConnection`,
    and nothing that depends on them: `TenantScope`, `OutboxWriter`, the audit writers, `OperationRunner`,
    config-resolver, evidence-store and the job command HTTP wiring.
  - With service-runtime imported first, product-runtime's `VariationPointCatalogue` was not registered yet either,
    so the service got no config-resolver ports.
  - Fix: the wirings and `JobCommandHttpAutoConfiguration` move into their own auto-configuration,
    `L1CompositionAutoConfiguration`. It is ordered after ServiceRuntimeConfiguration, DataSourceAutoConfiguration and
    ProductRuntimeConfiguration (by name) and listed in `AutoConfiguration.imports`, so the composition is the same
    with TPL-18's order, the reverse order or auto-configuration only.
  - No API change. Each bean keeps its conditions and its `@ConditionalOnMissingBean` override.
  - New tests: `ConfigResolverImportOrderTest` and `Tpl16ServiceCompositionTest` compose a stand-in product-runtime
    and a `spring.datasource.*` service as TPL-18 does. The implTest classpath gains `spring-boot-starter-jdbc`.

## [3.2.0] - 2026-10-12
### Added
- rebuildConsumer (INV-T-3385; INV-API-3656; INV-REQ-1205 b4, b6; rulings INV-Q-3599, INV-Q-3663, INV-Q-3665,
  INV-Q-3667, INV-Q-3671): `ConsumerRebuildCommands` / `ConsumerRebuildRuntime`. Platform operator only (EVL-002);
  EVL-001 topic not consumed, EVL-003 not in rebuild required, EVL-004 another replay pending for the pair; a repeat
  with the same mode and scope returns the pending replay and writes nothing. The head move, ReplayRequested and the
  AuditEntryRecorded commit in one transaction (INV-AC-3657). A full sync runs on the worker role bound to the platform
  tenant (`FullSyncWiring`, INV-Q-3786); one with no `ReadModelClear` for an addressee is refused.
- A topic with several catalogue producers (owner ruling INV-Q-3788 A; INV-AC-3817): one call addresses all of
  them, one head move and one ReplayRequested per producer under one replay id; the repeat rule and EVL-004 hold over
  that set of pairs; `ReplayState.producers` lists every addressee and `producer` is 'several producers'
  (`ReplayState.SEVERAL_PRODUCERS`; service-runtime contract 1.2.0, INV-T-3819); the audit scope names every
  addressee. A full sync addresses every producer of the topics it moves, until no moved topic has a producer left
  out, runs each addressee's clear and deletes each addressee's docsequence rows (coordinator ruling INV-Q-3843;
  none for 'every service', INV-Q-3805). A replay stays pending until its incident closes, also after every
  producer completed (coordinator ruling INV-Q-3845): a same mode and scope call returns it, any other call touching
  one of its pairs is EVL-004, and EVL-003 applies only after the close.
- The inbox consumer bean takes the `EventCatalogue` (record topics, INV-Q-3809). The tests' pinned
  `service-runtime/openapi.yaml` is platform-contracts 0b685b5 (service-runtime contract 1.2.0).
- The tests' pinned `catalogue/errors/error-codes.yaml` and error envelope are platform-contracts fadead4
  (gst-catalogue 1.14.0), which carries SRT-011; `ProblemsTest` checks SRT-011 against its row like the others.

## [3.1.0] - 2026-10-12
### Added
- Consumer staleness gauges (INV-T-3005; INV-REQ-1165, INV-AC-2150; coordinator ruling INV-Q-3030 (a)): the
  composition root runs outbox-and-queue's `JdbcStalenessMonitor` every `service-runtime.staleness.check-interval`
  (default 30 s, a technical setting) and exports per (consumer, event type), labelled `consumer` and `event_type`
  only (CS-36), the gauges `gst.platform.consumer.staleness.lag` and `gst.platform.consumer.staleness.threshold`
  (Prometheus: `gst_platform_consumer_staleness_lag_seconds`, `gst_platform_consumer_staleness_threshold_seconds`).
  The incident is raised by the observability bundle's alert rules on them (INV-T-3035). A failed measurement
  withdraws the lag gauges until the next success, so it reads as absent, never as zero. The meter names follow ruling
  INV-Q-3831 (A, HIGH: `gst.platform.*` for this library-owned meter under CS-36).
- Staleness rules as `service-runtime.staleness.rules[n].consumer|event-type|threshold` (CS-27), alongside any
  `StalenessRule` beans. A rule without a threshold takes `ProvisionalStalenessThresholds`: 5 minutes for
  payload-svc and submission-svc on CanonicalVersionCreated, PayloadReady, PayloadRejected and DocumentReleased,
  30 minutes otherwise (INV-Q-2991), **provisional until the SDS §13.7 performance tests** (INV-Q-2987). An
  installation may only tighten: a longer value stops start-up with a message naming the rule (INV-REQ-1165 b4).
  Not variation points (ARC-12).

### Changed
- `StalenessBreachSink` is optional: without one the monitor logs each breach at WARN (`LoggingStalenessBreachSink`).
## [3.0.0] - 2026-10-12
### Changed (breaking: 2.x -> 3.0.0; no consumer exists yet, product-runtime binds these in INV-T-2876)
- `AuthorityReplica` keys grant state per (tenant, principal, entityRef) (INV-Q-3322 (b), INV-T-3335).
  `AuthorityKey` gains `entityRef`. `AuthorityChanged` carries `authorityVersion` and one `AuthorityRecord`:
  a `GrantRecord` (one entity's grants, replaced whole and held with its version as `GrantState`; an empty
  list is a tombstone), a `ConsentRecord` per (tenant, client entityRef) (a CONSENT grant decides only with
  a live consent, so a withdrawal denies at the next check with no grant event; INV-REQ-853, INV-REQ-865,
  INV-Q-3136), or a `PrincipalRecord` (deleted or revoked: voids every grant record of the principal with a
  lower version, so a reinstated principal gets no old grant back). A change at or below the version held
  for its key is ignored. An event with no record is never kept: the principal's grant records are dropped (each key keeps
  its version as a tombstone),
  lower versions voided, and decisions deny (INV-REQ-1214). `PrincipalAuthority` is removed.
- `Grant` loses `entityRef` (the record's key) and gains `scopeLevel` (`ScopeLevel`: TENANT, GROUP, ENTITY,
  ENTITY_PERIOD, CONSENT; an unrecognised wire value is UNKNOWN and matches nothing), `scopeRef`, and for
  ENTITY_PERIOD the granted period as a date span (`periodFrom`, `periodTo`). `AuthorizationRequest` gains
  `resourcePeriod` (`DateSpan`), the GST accounting period acted on; an ENTITY_PERIOD grant matches only
  when the whole period lies inside its span, checked apart from the validity window, and never without a
  period; an ENTITY grant covers every period (INV-Q-3339 (a), INV-REQ-864 b1, INV-AC-1911).
  `DecisionReason` gains `NO_LIVE_CONSENT` and `CONSENT_SCOPE_MISSING`.
- `AuthorizationRequest` gains an optional `requiredScopeItem` (`ConsentScopeItem`, the VP-10 keys), set by the
  owning service: under a CONSENT grant the live consent must list it, an unknown item never matches, and a
  request without it is decided on the grant and a live consent alone; `ConsentRecord.scopeItems` is a
  `Set<ConsentScopeItem>` (INV-Q-3734, INV-REQ-912, INV-REQ-913, INV-AC-1946).
- `StructureReplica` replaces whole nodes from either structure event or a `getEntityStructure` fill,
  instead of merging deltas (INV-Q-3324 (a)). `StructureNode` is the full node state (kind, parentRef,
  retired, name, code, effective-dated `ProfileEntry` list, structureVersion); `attribute(name, onDate)`
  serves a profile attribute by its effective date. `OrganisationStructureChanged` and
  `EntityProfileChanged` carry the node; `StructureChange` gains UPDATED and UNKNOWN, and every changeType
  applies the full node (INV-Q-3337). `StructureNodeKind` gains UNKNOWN.
- `EventFedReplica` takes an optional `versionOf`: a fill then keeps its returned version and never
  overwrites a newer state held; it also gains `tombstoneIf`, which drops values but keeps each key's
  version, so versions never go backwards.

- Removed: service-runtime's placeholder `binding.EventBinding`. It had no implementation, and outbox-and-queue's
  `EventBinding` (implementation `PostgresEventBinding`) is the one §12.1 EventBinding, bound by the composition
  root (coordinator ruling INV-Q-3775). No consumer existed.

### Added
- The default `EndUserPrincipalResolver`, `JwtEndUserPrincipalResolver` (owner ruling INV-Q-3716, INV-REQ-1202 b4;
  port from INV-T-2875). It verifies the end-user token against the `IdentityProvider` binding's issuer
  (signature, issuer, expiry, and the audience `service-runtime.end-user-identity.audience`). It reads `sub` as
  the actor and `tenant_id` as the tenant; a platform operator has no tenant and no entities. The entity set is
  `AuthorityReplica.heldEntities`: the entities where the principal holds a grant that is currently valid and
  not voided, with a recognised scope and, for CONSENT, a live consent. A missing or invalid token, or an
  unreadable `tenant_id`, resolves to null (401). It is wired in `ServiceRuntimeConfiguration` through
  `EndUserIdentityAutoConfiguration` when an `IdentityProvider` is bound, with `@ConditionalOnMissingBean`.
  `EventFedReplica` gains `heldWhere`.
- During authority replica catch-up (lag above the bound, or no version yet), `EndUserPrincipal.entitySetCurrent`
  is false. The job-command servlet's entity check then asks `AuthorityReplica.decide`, which goes to the
  access-svc fallback, for `job.operate` on that entity (§22.3, FR-AG.64). It is never refused for an empty set
  and never skipped; it is denied only when the fallback refuses or fails (coordinator rulings INV-Q-3771,
  INV-Q-3782).
- If the token issuer is unreachable, tokens keep verifying against the last keys obtained (`LastKnownKeySource`).
  When no cached key verifies a token, the resolver throws `TokenIssuerUnavailableException`, and the job-command
  servlet answers 503 with the new `ClosedErrorCode.ISSUER_UNAVAILABLE` (SRT-011), never 401 or 500 (coordinator
  ruling INV-Q-3773). SRT-011's catalogue row comes with INV-T-3762; until that pin moves, `ProblemsTest` lists the
  code as pending.

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
  Every coded response carries `type` `https://errors.invoice.internal/<code>` (API-20, coordinator ruling
  INV-Q-3796; the host is the one constant `Problems.ERROR_TYPE_HOST`, pending owner question INV-Q-3800). The
  auto-configured store gets the worker role, and `IdempotencyKeyPurgeJob` registers `idempotency-key-purge` in
  the service's `JobRuntime`: hourly, under ShedLock, deleting expired keys in batches on the worker role; key
  retention `service-runtime.idempotency.retention` is per installation, default 24 hours, and a shorter one is
  refused at start-up (coordinator ruling INV-Q-3798).
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
