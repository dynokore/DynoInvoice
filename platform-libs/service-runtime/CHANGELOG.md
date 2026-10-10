# Changelog

All notable changes to `service-runtime` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.2.0] - 2026-10-11
### Added
- The replica mechanism (INV-REQ-1199, INV-ADR-284; INV-T-2873 / T29), package `replica`:
  - `EventFedReplica`: a generic event-fed local replica with lag measured by
    `ReplicaLagTracker` from the source's monotonic version, an optional lag
    bound, a fallback call to the owning service, cache-miss fill, and
    serve-last-known when the fallback is down. Held in memory; a restart refills.
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
