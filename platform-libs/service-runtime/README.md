# service-runtime

Common service bootstrap and configuration; health and degraded readiness (FR-AH.17); the uniform diagnostic endpoint (FR-AE.82); telemetry and trace context — `correlation_id`, `causation_id` — propagated across calls and logs and supplied to the event envelope (§11.6); contract clients with deadline propagation and the per-edge policy of §36.6 (FR-AH.7, FR-AH.8); workload identity (FR-AH.6); the replica mechanism for structure, authority and policy (AD-26, FR-AI.3); the platform-event schemas (AD-27, FR-AH.15); the job contract (FR-AG.62) and the connection contract (§35.13); and the closed set of binding interfaces of §12.1

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every back-end service — every service but `console-web` |
| Part used | All; `control-plane-svc` uses the bootstrap, bindings and telemetry, not the replicas |
| Uses | the other L1 modules its service embeds (composition root) |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## Composition root (INV-T-2875; INV-Q-3710, INV-Q-3730, INV-Q-3732)

`ServiceRuntimeConfiguration` wires each L1 module the service embeds, and only those: a module is wired when its
impl half is on the service's classpath (@ConditionalOnClass). Every bean below is @ConditionalOnMissingBean on its
port, so a service overrides one by declaring its own. The service declares its inputs in its own `bootstrap`
configuration; an implementation is created only when all of its inputs are present.

The composition is order-independent (INV-T-3881; rulings INV-Q-3877, INV-Q-3889). The module wiring is not in
ServiceRuntimeConfiguration's `@Import`. It is its own auto-configuration, `L1CompositionAutoConfiguration`, ordered
after ServiceRuntimeConfiguration, Spring Boot's DataSourceAutoConfiguration and ProductRuntimeConfiguration. So its
conditions see the application DataSource from `spring.datasource.*` (TPL-16) and product-runtime's
VariationPointCatalogue whichever way the two roots are registered: TPL-18's `@Import` order, the reverse order, or
auto-configuration only.

Service inputs:
- **One application DataSource** (INV-T-3981, owner ruling INV-Q-3955): the service's `DataSource` (`dataSource` from
  `spring.datasource.*`, or the `@Primary` one) logs in as `<svc>_runtime` and serves every platform-libs adapter; there
  is no role DataSource. Only when `gst.db.event-log-url` names a separate event-log instance (HA, different from
  `spring.datasource.url`) is a second pool created, `eventLogDataSource` (`ServiceDataSources.EVENT_LOG`): the same
  login and credential, at most 3 connections, `service-runtime.event-log-pool.minimum-idle` (default 1, 0..3;
  INV-Q-4348). Otherwise the event-log adapters use the application DataSource.
- **Workload principal:** `svc:<gst.service>` (`WorkloadPrincipal`) is the actor bound for the service's own work (the
  outbox relay, inbox consumers, purges); a scheduled run binds `svc:<svc>/job:<job>` (owner ruling INV-Q-4344 (b)).
  `gst.service` must be a lower-case service name such as `submission-svc`, or start-up fails.
- **Migrations** run only through `MigrationRunner` (INV-Q-3959), a separate entry point (an init container or a
  Job) that logs in as `<svc>_migrate` (`spring.flyway.user`, password from `service-runtime.flyway.credential`) and
  then writes `service-runtime.jobs.retention` into `job_retention`. The service itself never runs Flyway
  (`spring.flyway.enabled=false` by default).
- **Properties:** `gst.service` (TPL-16), `gst.instance-id` (default `HOSTNAME`), `service-runtime.modules.schema`
  (the service's own schema), `service-runtime.modules.evidence.ledger-table`, `.legal-hold-table` and `.bucket`.
- **§12.1 selection:** `gst.bindings.event-binding` (`postgres`), `gst.bindings.object-store` (`s3-compatible`),
  `gst.bindings.secret-store.implementation` (`kubernetes`). Each names the one implementation platform-libs ships
  and is the default.

| Module | Port | Implementation | Created when the service declares |
| --- | --- | --- | --- |
| tenancy-kernel | `TenantContext`, `PlatformTenantBinding` | `TenantContextImpl` | nothing |
| tenancy-kernel | `TransactionRunner`, `CurrentConnection` | `TransactionRunnerImpl` over `TenantAwareDataSource` | application `DataSource` |
| tenancy-kernel | `LicenceCheck` (§12.1) | the service's base check, wrapped in `NonBlockingLicenceCheck` | its base `LicenceCheck` (tenancy-svc, INV-REQ-1076) |
| outbox-and-queue | `TenantScope` | `TenantScope` | (a `TransactionRunner`) |
| outbox-and-queue | `OutboxWriter` | `JdbcOutboxWriter` | `EventCatalogue`, `gst.service`, schema |
| outbox-and-queue | `EventBinding` (§12.1) | `PostgresEventBinding` | `eventLogDataSource` if present, else the application `DataSource` |
| outbox-and-queue | `OutboxRelay` | `JdbcOutboxRelay` | application `DataSource`, schema |
| outbox-and-queue | `EventLogReader`, `EventLogLag` | `PostgresEventLogReader` | `eventLogDataSource` if present, else the application `DataSource` |
| outbox-and-queue | `LaneRateLimiter`, `WorkQueue` | `LaneRateLimiter`, `JdbcWorkQueue` | `LaneLimits`, application `DataSource`, schema |
| outbox-and-queue | `DeadLetterTriage` | `JdbcDeadLetterTriage` | application `DataSource`, schema |
| outbox-and-queue | `RetentionPurge` | `JdbcRetentionPurge` | `Retention`, application `DataSource`, schema |
| outbox-and-queue | `EventLogPurge` | `JdbcEventLogPurge` | `Retention`, `EventCatalogue`, `eventLogDataSource` if present, else the application `DataSource` |
| outbox-and-queue | `StalenessMonitor` | `JdbcStalenessMonitor`, with `StalenessGauges` (see "Consumer staleness") | `service-runtime.staleness.rules` or `StalenessRule`s, `EventCatalogue`, application `DataSource`, schema (a `StalenessBreachSink` is optional) |
| outbox-and-queue | `InboxConsumer` | `JdbcInboxConsumer` (one; a service with several declares its own) | `ConsumerSubscription`, `EventCatalogue`, `EventHandler`, `RebuildRequiredSink`, application `DataSource` |
| outbox-and-queue | `ReplayDelivery` | `JdbcReplayDelivery` | (a `WorkQueue` and a `JdbcInboxConsumer`) |
| audit-log | `AuditWriter` | `OutboxAuditWriter`, or `LocalAuditWriter` without an outbox (INV-Q-2958) | (an `OutboxWriter`), else `gst.service`, schema |
| audit-log | `AuditedOperations` | `TransactionalAuditedOperations` | (a `TransactionRunner`, an `AuditWriter`) |
| audit-log | `OverrideService`, `OverrideRegister` | `JdbcOverrideService` | `ErrorCatalogue` (an `OverridePolicy` if it has one), schema |
| config-resolver | `PolicyReader`, `PolicyHistory`, `ConfigPurge`, `ResolutionRecorder` | `Jdbc*` | `VariationPointCatalogue` (product-runtime's), schema; form from `gst.form`. The wiring is order-independent: TPL-18's `@Import` in either order, or auto-configuration only (`L1CompositionAutoConfiguration`, INV-T-3881, INV-Q-3877) |
| config-resolver | `PolicyWriter` | `JdbcPolicyWriter` | as above, plus `DelegationProbe`, `BackdatingFloor`, `ValueConstraints` if it has them |
| evidence-store | `EvidenceLedger`, `LegalHoldRegistry` | `JdbcEvidenceLedger`, `JdbcLegalHoldRegistry` | schema and the table names |
| evidence-store | `ObjectStore` (§12.1) | `S3ObjectStore` | `S3Client` (AWS SDK on its classpath), bucket |
| adapter-runtime | `SecretStore` | adapted to service-runtime's bound `SecretStore` (`MountedFileSecretStore`) | nothing |
| adapter-runtime | `ConnectionStatusReporter` (version-keyed) | `InMemoryConnectionStatusReporter`, a local status bean, **not bridged**; its UNKNOWN is never published (INV-Q-3730) | nothing |
| adapter-runtime | `DeadLetterSink` | `InMemoryDeadLetterSink` | nothing |
| crypto | `DetachedSigner`, `DetachedSignatureVerifier` | `BouncyCastleCms` | nothing |
| rulepack-kernel | `PackVerifier`, `PackContentValidator`, `PackRegistry` | `BouncyCastlePackVerifier`, `JacksonPackContentValidator`, `InMemoryPackRegistry` | nothing |
| service-runtime | `SecretStore` (§12.1) | `MountedFileSecretStore` | nothing |
| service-runtime | `OperationRunner` | `TransactionalOperationRunner` | (a `TransactionRunner` with its `CurrentConnection`, an `AuditWriter`) |
| service-runtime | `ConnectionStatusReporter`, the only `ConnectionStatusChanged` publisher (INV-REQ-836 b4) | `OutboxConnectionStatusReporter` | (an `OutboxWriter`) |
| service-runtime | `EvidenceArtefactRecorder` | `OutboxEvidenceArtefactRecorder` | (an `OutboxWriter`) |
| service-runtime | `JobCommands` | `JobRuntime` | `JobCommandAuthorizer`, `DeadlineSource`, application `DataSource`, `gst.service`, schema, `service-runtime.jobs.retention` (default 30 days; the runtime refuses to start when `job_retention` does not hold it); optional `jobRunNowExecutor` |
| service-runtime | runJobNow / pauseJob / resumeJob servlet, `IdempotencyStore` | `JobCommandServlet` | `JobCommands`, `EndUserPrincipalResolver`, `TenantScope`, `service-runtime.idempotency.schema`; a tenantless token gets 403 unless the target is a platform job |
| service-runtime | API-15 key purge, a ShedLock-guarded platform job (INV-Q-3798) | `IdempotencyKeyPurgeJob` (`idempotency-key-purge`, hourly, batched through the `idempotency_key_purge_expired` definer function) | a `JobRuntime`, `gst.service`; `service-runtime.idempotency.retention` (default 24 hours, below 24 hours refused at start-up) |

Left for the service, with no platform-libs implementation: the §12.1 `PackSource` (rules-svc, INV-ADR-2773,
INV-REQ-1079), the base `LicenceCheck` (tenancy-svc, INV-REQ-1076), `MessageDelivery` (INV-REQ-817),
`IdentityProvider` (INV-REQ-882) and `TelemetryExport` (INV-REQ-1104); `JobCommandAuthorizer`, `DeadlineSource`
and `EndUserPrincipalResolver`; and the per-edge or per-adapter `ResilientExecutor`, `VersionedAdapterRouter` and
`SigningKey`. No other L1 module depends on service-runtime (`CompositionRootDirectionTest`).

## Database credentials (INV-T-3913; STK-29, INV-REQ-1231, ruling INV-Q-3909)

Every database password is read through `SecretStore` (STK-29: mounted files only, never a property or an environment
variable) by `SecretStoreCredentialsProvider`, a HikariCP credentials provider. HikariCP calls it for each new physical
connection, and it reads the mounted file each time, with no cache.

| Pool | How | Username |
| --- | --- | --- |
| Spring Boot's application pool | `service-runtime.datasource.credential: <SecretRef>` | `spring.datasource.username` |
| Flyway's owner role (STK-16) | `service-runtime.flyway.credential: <SecretRef>` | `spring.flyway.user` |
| The HA event-log pool (`eventLogDataSource`) | the application pool's credentials provider, reused | `spring.datasource.username` |

With the property set, start-up fails if `spring.datasource.password` (or `spring.datasource.hikari.password`), or
`spring.flyway.password`, is set as well. Without it nothing changes.

**Rotating a database role's password:** change the role's password (`ALTER ROLE ... PASSWORD`) and the mounted secret.
Connections already open keep working until the pool retires them (`maxLifetime`). Each new connection logs in with
the value mounted at that moment, so no restart is needed.

## Consumer staleness (INV-T-3005; INV-REQ-1165, INV-AC-2150, INV-Q-3030 (a))

A service lists one rule per consumer and event type it consumes:

```yaml
service-runtime:
  staleness:
    check-interval: 30s          # default; how often every rule is measured
    rules:
      - consumer: submission-svc
        event-type: com.dynokore.gst.canonical.CanonicalVersionCreated.v1
        # threshold: 3m          # optional; may only be shorter than the provisional value
```

Without a threshold a rule takes its **provisional** value (`ProvisionalStalenessThresholds`, INV-Q-2991): 5 minutes
for payload-svc and submission-svc on CanonicalVersionCreated, PayloadReady, PayloadRejected and DocumentReleased,
30 minutes for every other consumer and event type. They are provisional until the SDS §13.7 performance tests
(INV-Q-2987) and travel with this module's release train. An installation may only tighten them: a longer value
stops start-up, naming the rule (INV-REQ-1165 b4).

Every instance exports, per rule, the gauges `gst.platform.consumer.staleness.lag` (age of the oldest unconsumed
event, zero with none) and `gst.platform.consumer.staleness.threshold`, in seconds, labelled `consumer` and
`event_type` only. The incident is raised by the observability bundle's alert rules (INV-T-3035): lag above
threshold, and a lag gauge that stops being reported. When a measurement fails the lag gauges are withdrawn until
the next success. A breach is also logged at WARN unless the service declares its own `StalenessBreachSink`.
