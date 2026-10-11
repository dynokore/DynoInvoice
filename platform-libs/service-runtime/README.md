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

Service inputs:
- **Role DataSources**, by bean name (`ServiceDataSources`): the application role is the service's `DataSource`
  (`dataSource`, or the `@Primary` one); `workerDataSource`; `eventLogRelayDataSource`; `eventLogClaimDataSource`;
  `eventLogReaderDataSource`; `eventLogPurgeDataSource`.
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
| outbox-and-queue | `EventBinding` (§12.1) | `PostgresEventBinding` | `eventLogRelayDataSource` |
| outbox-and-queue | `OutboxRelay` | `JdbcOutboxRelay` | `workerDataSource`, schema |
| outbox-and-queue | `EventLogReader`, `EventLogLag` | `PostgresEventLogReader` | `eventLogClaimDataSource`, `eventLogReaderDataSource` |
| outbox-and-queue | `LaneRateLimiter`, `WorkQueue` | `LaneRateLimiter`, `JdbcWorkQueue` | `LaneLimits`, `workerDataSource`, schema |
| outbox-and-queue | `DeadLetterTriage` | `JdbcDeadLetterTriage` | `workerDataSource`, schema |
| outbox-and-queue | `RetentionPurge` | `JdbcRetentionPurge` | `Retention`, `workerDataSource`, schema |
| outbox-and-queue | `EventLogPurge` | `JdbcEventLogPurge` | `Retention`, `EventCatalogue`, `eventLogPurgeDataSource` |
| outbox-and-queue | `StalenessMonitor` | `JdbcStalenessMonitor` | `StalenessRule`s, `StalenessBreachSink`, `EventCatalogue`, `workerDataSource`, schema |
| outbox-and-queue | `InboxConsumer` | `JdbcInboxConsumer` (one; a service with several declares its own) | `ConsumerSubscription`, `EventHandler`, `RebuildRequiredSink`, `workerDataSource` |
| outbox-and-queue | `ReplayDelivery` | `JdbcReplayDelivery` | (a `WorkQueue` and a `JdbcInboxConsumer`) |
| audit-log | `AuditWriter` | `OutboxAuditWriter`, or `LocalAuditWriter` without an outbox (INV-Q-2958) | (an `OutboxWriter`), else `gst.service`, schema |
| audit-log | `AuditedOperations` | `TransactionalAuditedOperations` | (a `TransactionRunner`, an `AuditWriter`) |
| audit-log | `OverrideService`, `OverrideRegister` | `JdbcOverrideService` | `ErrorCatalogue` (an `OverridePolicy` if it has one), schema |
| config-resolver | `PolicyReader`, `PolicyHistory`, `ConfigPurge`, `ResolutionRecorder` | `Jdbc*` | `VariationPointCatalogue`, schema; form from `gst.form` |
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
| service-runtime | `JobCommands` | `JobRuntime` | `JobCommandAuthorizer`, `DeadlineSource`, `workerDataSource`, `gst.service`, schema; optional `jobRunNowExecutor` |
| service-runtime | runJobNow / pauseJob / resumeJob servlet, `IdempotencyStore` | `JobCommandServlet` | `JobCommands`, `EndUserPrincipalResolver`, `service-runtime.idempotency.schema` |

Left for the service, with no platform-libs implementation: the §12.1 `PackSource` (rules-svc, INV-ADR-2773,
INV-REQ-1079), the base `LicenceCheck` (tenancy-svc, INV-REQ-1076), `MessageDelivery` (INV-REQ-817),
`IdentityProvider` (INV-REQ-882) and `TelemetryExport` (INV-REQ-1104); `JobCommandAuthorizer`, `DeadlineSource`
and `EndUserPrincipalResolver`; and the per-edge or per-adapter `ResilientExecutor`, `VersionedAdapterRouter` and
`SigningKey`. No other L1 module depends on service-runtime (`CompositionRootDirectionTest`).
