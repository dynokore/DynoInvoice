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

## Tenant predicate on jOOQ (INV-T-4134; INV-ADR-3968 item 4a, owner rulings INV-Q-4113, INV-Q-4196)

`TenancyJooqWiring` gives every service one `org.jooq.Configuration` and one `DSLContext`, over tenancy-kernel's
`TransactionRunner`, made by `TenancyJooq.configure` (tenant predicate injected and checked, connection only from the
open tenant transaction). Spring Boot's jOOQ auto-configuration backs off; its `DefaultConfigurationCustomizer` is
provided for a service that has it.

- **Classification at start-up.** The service's generated jOOQ schemas are `org.jooq.Schema` beans. `TableClasses.verify`
  runs over them: every table has `tenant_id`, or is declared PLATFORM_SCOPED (its migration carries the table comment
  `platform-scoped: no tenant data`), or REFERENCE. A tenantless table nobody declared, a declared table that is
  missing, or a declaration that disagrees with the columns stops the service. A service declares its own with
  `inv.tenancy.platform-scoped-tables` and `inv.tenancy.reference-tables` (`schema.table`, lists). The libraries
  declare theirs: `ConfigSchema` (`config_installation_setting`), `OutboxSchema` (`consumer_offset`, the replay state
  tables, `lane_rate_window`), `EventLogSchema` (the event log's own tables), `JobSchema` (`shedlock`,
  `job_retention`); a library table is declared when it is in the service's schemas.
- **Schema supply (INV-Q-4815).** A service with a database declares one `org.jooq.Schema` bean per generated schema in
  its bootstrap module. With a `TransactionRunner` and no Schema bean that holds a table (a `Catalog` or `Table` bean
  does not count) start-up fails, naming `org.jooq.Schema`; a service without a database is not checked.
- **No other jOOQ.** `TenancyJooqGuard` fails start-up for any `DSLContext` or `org.jooq.Configuration` bean whose
  connection provider, VisitListeners or last ExecuteListener are not tenancy-jooq's, such as `DSL.using(dataSource)`.
  service-runtime creates no JdbcTemplate, NamedParameterJdbcTemplate, TransactionTemplate or transaction manager.

## Database credentials (INV-T-3913; STK-29, INV-REQ-1231, ruling INV-Q-3909)

Every database password is read through `SecretStore` (STK-29: mounted files only, never a property or an environment
variable) by `SecretStoreCredentialsProvider`, a HikariCP credentials provider. HikariCP calls it for each new physical
connection, and it reads the mounted file each time, with no cache.

| Pool | How | Username |
| --- | --- | --- |
| Spring Boot's application pool | `service-runtime.datasource.credential: <SecretRef>` | `spring.datasource.username` |
| Flyway's owner role (STK-16) | `service-runtime.flyway.credential: <SecretRef>` | `spring.flyway.user` |
| The HA event-log pool (`eventLogDataSource`) | the application pool's credentials provider, reused | `spring.datasource.username` |

Start-up fails if `spring.datasource.password`, `spring.datasource.hikari.password` or `spring.flyway.password` is set,
with or without the credential property, in any property source (the three keys are also read by exact name, so a
source that cannot be enumerated is seen), and if a JDBC URL carries a `password` parameter (`SqlConfigurationKeyGuard`,
coordinator rulings INV-Q-4542, INV-Q-4548, INV-Q-4570). A pool's password comes only from SecretStore:
`PoolConfigurationGuard` fails start-up when any HikariDataSource bean has a password, a credentials provider other than
exactly `SecretStoreCredentialsProvider`, or MBeans on (MBeans expose `setPassword` over JMX; INV-Q-4570, INV-Q-4574).
The HA event-log pool copies only the credentials provider, never a password.

**Rotating a database role's password:** change the role's password (`ALTER ROLE ... PASSWORD`) and the mounted secret.
Connections already open keep working until the pool retires them (`maxLifetime`). Each new connection logs in with
the value mounted at that moment, so no restart is needed.

## Configuration keys that run SQL (INV-T-4544; coordinator ruling INV-Q-4542)

`SqlConfigurationKeyGuard`, an `EnvironmentPostProcessor` registered in `META-INF/spring.factories`, runs before any
bean is created in every service and in the migration runner. It reads every property source in any relaxed form and
fails start-up, naming each offending key and the ruling (never a value), on:

1. any key under `spring.sql`, `spring.jpa`, `spring.r2dbc`, `spring.batch`, `spring.quartz`, `spring.session.jdbc`,
   `spring.integration.jdbc` or `spring.modulith.events.jdbc`;
2. any key whose last segment contains `init-sql`, `init-sqls`, `init-s-q-l`, `initialize-schema`,
   `schema-initialization`, `schema-locations` or `data-locations`;
3. any `spring.datasource` key outside its allowlist (`url`, `username`, `driver-class-name` = `org.postgresql.Driver`
   (INV-Q-4603), `type` =
   `com.zaxxer.hikari.HikariDataSource`, and the Hikari pool sizes, timeouts and `pool-name`; `register-mbeans` is
   refused, INV-Q-4574);
4. any `spring.flyway` key outside its allowlist; `locations` only `classpath:`, `clean-disabled` only `true`, and
   `placeholders.*` values only plain identifiers (`SqlIdentifier`);
5. any JDBC URL parameter in `spring.datasource.url`, `spring.flyway.url` or `gst.db.event-log-url` other than
   `loggerLevel` (`OFF` only), `sslmode` and `sslrootcert` (coordinator ruling INV-Q-4559);
6. an external pool or driver configuration file: `hikaricp.configuration-file` in any form, the
   `hikaricp.configurationFile` system property, or a class-path `org/postgresql/driverconfig.properties` (INV-Q-4559).
7. any `spring.jmx` key other than `spring.jmx.enabled=false` (INV-Q-4591).
8. a key that removes a start-up guard or loads classes or bean definitions by name (coordinator ruling INV-Q-4603), in
   any relaxed, list or indexed form: `spring.autoconfigure.exclude` with an entry in `com.dynokore.platform`,
   `spring.main.allow-bean-definition-overriding` other than `false`, `spring.main.sources`,
   `context.initializer.classes`, `context.listener.classes`, and `spring.application.admin.enabled` other than `false`.

A key passed as `server.servlet.context-parameters.<key>` is checked as `<key>`. `SqlConfigurationKeyRecheck`, a
`BeanFactoryPostProcessor` registered by `ServiceRuntimeConfiguration`, runs the same check again after
configuration-class parsing and before any application bean, so a `@PropertySource` is seen too. `PoolConfigurationGuard`
then checks what every HikariDataSource bean actually received (deny-by-default over every HikariConfig property:
no connection-init SQL, test query, schema, catalog or session settings; data-source properties only `ApplicationName`
= `gst.service`; the URL allowlist above; no password, MBeans off, and a credentials provider only exactly
`SecretStoreCredentialsProvider`, coordinator rulings INV-Q-4570 and INV-Q-4574), so a path no key names is refused as
well. The second run also fails start-up on any `MBeanExporter` bean definition (Spring Boot's JMX
auto-configuration, `@EnableMBeanExport`, an exporter `@Bean`): an exporter exports every HikariDataSource with a
writable Password, so INV uses no JMX (coordinator ruling INV-Q-4591). `PoolConfigurationRecheck`, a
`SmartInitializingSingleton` registered beside `PoolConfigurationGuard`, checks every pool again with the same rules
once every singleton exists, so a pool created before the guard was registered (a bean a `BeanFactoryPostProcessor`
fetched early) or changed after its first check fails start-up too, naming the bean (coordinator ruling INV-Q-4603).

**SpEL (coordinator ruling INV-Q-4694).** INV evaluates no SpEL. `SpelRefusingExpressionResolver` replaces the bean
factory's BeanExpressionResolver before any bean-defined post-processor runs (`SpelRefusalInitializer` in
`META-INF/spring.factories`, and a bean of `ServiceRuntimeConfiguration`). It returns text without `#{` unchanged and
fails wherever Spring would evaluate text containing `#{`: `@Value` after placeholder resolution, `@Scheduled`,
`@RequestMapping` and every other embedded value, and bean-definition strings. `@ConditionalOnExpression` fails at the
second pass, as does a replaced resolver. Property sources are not scanned, so a variable no evaluation point reads
(a CI variable, a pod annotation) never affects start-up. Bind values with `@ConfigurationProperties`.

The allowlists live only in `SqlConfigurationKeyGuard`; a key is added only through a Keel ruling.
`sql-configuration-keys.txt` (implTest) pins the classification of every `spring.datasource` and `spring.flyway` key in
Spring Boot's metadata, so an upgrade that adds a key fails until it is reviewed. The event-log pool sets no
connection-init SQL and no schema.

## Pool lanes (INV-T-3982; INV-REQ-1230 b1, INV-REQ-1072 b5, INV-ADR-3968 item 1, owner ruling INV-Q-4023)

The service's one application pool (Spring Boot's HikariDataSource, STK-17) is split into three lanes, each a permit
budget within the one pool: **request**, **background** (relay, consumers, claims, jobs, purges) and **reprocessing**
(pack-activation reprocessing and backfill). The `dataSource` bean is a `LanedDataSource`: a connection is taken in the
current thread's lane (`PoolLanes.inLane(PoolLane.BACKGROUND) { ... }`; a thread outside any `inLane` is in the request
lane) and only within that lane's permits. The pool maximum is the sum of the budgets, so a saturated background or
reprocessing lane never takes a connection a request is owed. A lane with a budget of 0 is refused, never served from
another lane (INV-REQ-1072 b5). Job firings (`JobRuntime.fire`) and service-runtime's own schedules (staleness and
unrelayed-event checks, idempotency-key purge) run in the background lane.

Each lane's work is visible in pg_stat_activity as `application_name` `<gst.service>:<lane>`, set transaction-locally:
tenancy-kernel's `TransactionRunnerImpl` adds `set_config('application_name', ?, true)` to its binding statement, with
the lane the transaction's connection holds a permit of (`TenancyKernelWiring.laneLabel`); it ends at commit or
rollback. The pool's own name `<gst.service>` is set once through HikariCP's `ApplicationName` data-source property
(also on the HA event-log pool). service-runtime issues no statement on a connection (coordinator ruling INV-Q-4487).

Defaults (request / background / reprocessing), the INV-T-3977 capacity model:

| `service-runtime.pool.profile` | `service-runtime.pool.runs-reprocessing=true` | `false` (the default) |
|---|---|---|
| `minimal` | 4 / 2 / 1 (pool 7) | 4 / 2 / 0 (pool 6) |
| `ha` | 7 / 3 / 1 (pool 11) | 7 / 3 / 0 (pool 10) |
| `saas` (per pod) | 7 / 3 / 1 (pool 11) | 7 / 3 / 0 (pool 10) |
| HA event-log pool | 0 / 3 / 0 (pool 3) | 0 / 3 / 0 (pool 3) |

Whether a service runs pack-activation reprocessing or backfill is its own declaration,
`service-runtime.pool.runs-reprocessing`, set in its packaged configuration (coordinator ruling INV-Q-4485);
service-runtime holds no list of services. The seven submission-path services declare it: ingestion-svc,
canonical-svc, rules-svc, masterdata-svc, payload-svc, submission-svc and transport-svc (owner ruling INV-Q-4023).
Start-up fails when a declaring service's reprocessing budget is 0 (its work would otherwise run on a live lane) and
when a non-declaring service's is above 0.

`service-runtime.pool.lanes.request`, `.background` and `.reprocessing` override a lane's default. There is no default
profile (coordinator ruling INV-Q-4496): start-up fails, naming the missing settings, when the profile is not set and
any lane is not set explicitly, and when the profile is not `minimal`, `ha` or `saas`; it also fails when `spring.datasource.hikari.maximum-pool-size` is set to anything but
the sum (set the lanes instead).

## Event loops (INV-T-3938; coordinator ruling INV-Q-3935, INV-REQ-1212, INV-REQ-1222, INV-ADR-3968)

A service writes no polling code (TPL-18 is unchanged). service-runtime drives every `OutboxRelay`, `InboxConsumer` and
`ReplayDelivery` bean of the application context, those the composition creates and those a service declares itself
(one `JdbcInboxConsumer` per subscribed topic, for example): one loop per bean, each on its own virtual thread, started
on `ApplicationReadyEvent` and stopped on shutdown, as a lifecycle bean, before the DataSources close. A stop lets the
call in hand finish (it interrupts only a call still running after 30 seconds).

- A loop calls `runOnce` / `pollOnce` again at once while the last call did work (relay: rows published; consumer:
  records consumed; replay delivery: an item delivered), otherwise it waits its interval.
- A call that throws is logged at WARN with the exception class and SQLSTATE only (CS-34), retried after a bounded,
  jittered backoff (the interval doubled per consecutive failure, at most 30 seconds or the interval if longer, reduced
  by up to half), and the loop goes on. An `Error` ends the loop: it is logged at ERROR, the application's readiness
  goes to `REFUSING_TRAFFIC` and the `eventLoops` health contributor reports DOWN (it is part of the default `ready`
  group; a service that sets `management.endpoint.health.group.ready.include` itself adds `eventLoops`).
- Each loop exposes the epoch seconds of its last successful call as the gauge
  `gst.platform.event.loop.last.success.timestamp.seconds` (tags `loop` = bean name, `kind` = `relay`, `consumer` or
  `replay`), NaN before the first.
- A loop works in the background lane of the one pool (`PoolLanes`, "Pool lanes"), never the request lane, and uses no
  role DataSource. It binds no tenant itself: the relay, consumers and replay delivery bind the service's workload
  principal and each row's tenant in their own transactions.
- Several instances of one service need no new lock: the relay runs one at a time (a transaction-scoped advisory lock)
  and a consumer locks its offset row (outbox-and-queue).
- **Lane sizing.** The relay holds its claim connection while it publishes a row in a tenant transaction, and the event
  log on the application pool (no `gst.db.event-log-url`) takes a third connection for the append: the background lane
  needs at least 3 permits for the relay to publish when the event log shares the application pool, and every other
  loop (a consumer in its handler transaction, the staleness and unrelayed-event checks) shares those permits.

| Property (`service-runtime.event-loops.*`) | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` switches every loop off, for tests that call `runOnce` / `pollOnce` themselves |
| `relay-interval` | `1s` | wait of each relay loop after a call that did no work |
| `consumer-interval` | `1s` | the same for each inbox consumer loop |
| `replay-interval` | `1s` | the same for each replay delivery loop |

Each interval is between 100 ms and 60 s (start-up fails otherwise); 1 s is within the 'staleness is seconds' that
INV-REQ-628 accepts.

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
