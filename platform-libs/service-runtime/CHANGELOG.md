# Changelog

All notable changes to `service-runtime` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [8.2.0] - 2026-10-18
### Added
- `ConfigResolverWiring` binds `OptionValueMaximum` (`JdbcOptionValueMaximum`) over the module's DSLContext when a `TransactionRunner` exists, replaceable by a service's own bean (INV-T-6538, INV-Q-6611; config-resolver 4.1.0).

## [8.1.9] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5361, G-DVS): depends on `audit-log` 4.0.0, whose published module metadata changed; no code change.

## [8.1.8] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5359, G-DVS): depends on `tenancy-kernel` 3.3.0, whose published module metadata changed; no code change.

## [8.1.7] - 2026-10-18
### Changed
- Moved `MigrationRunnerConfiguration` into a file of its own name and removed two unused HikariCP imports from `JobCommandHttpIT` (INV-T-6393); no behaviour change, the compiled source-file attribute of the moved class changes.

## [8.1.6] - 2026-10-18
### Changed
- Metadata-only release (INV-T-6291, G-DVS): depends on `outbox-and-queue` 6.1.0; no code change.

## [8.1.5] - 2026-10-18
### Changed
- `OutboxAndQueueWiring` (INV-T-4202, slice 4 of 4; owner ruling INV-Q-4196): `eventLogPresence` runs on the event-log
  `TenantScope` like the reader and the binding, so no outbox-and-queue bean is handed a `DataSource` any more; the
  raw-user hand-off helpers (`application`, `eventLogSource`) are gone. The Companion `handOff` stays only for the HA
  event-log pool's runner and `EventLoopsIT`.

## [8.1.4] - 2026-10-18
### Changed
- `OutboxAndQueueWiring` (INV-T-5306, outbox-and-queue 6.0.0; coordinator ruling INV-Q-5275): builds the event-log
  reader, the retention purge, the event-log purge and the staleness monitor on the event-log `TenantScope` (and the
  module's `DSLContext`), so they get no `DataSource` and the lane gate hand-off of these beans is gone. `ConsumerRebuildRuntime` builds the module's `DSLContext` for `JdbcConsumerReplay`
  (INV-Q-5956).

## [8.1.3] - 2026-10-18
### Changed
- Metadata-only release (INV-T-6135, G-DVS): depends on `audit-log` 3.1.0, whose published module metadata changed; no code change.

## [8.1.2] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5358, G-DVS): depends on `tenancy-kernel` 3.2.0, whose published module metadata gained a jOOQ dependency; no code change.

## [8.1.1] - 2026-10-17
### Changed
- `OutboxAndQueueWiring` (INV-T-5305, outbox-and-queue 5.0.0; coordinator ruling INV-Q-5275): builds `LaneRateLimiter`,
  `JdbcWorkQueue`, `JdbcDeadLetterTriage` and `JdbcInboxConsumer` with the module's tenancy-jooq `DSLContext` and the
  `TenantScope`; the limiter, the work queue and the inbox consumer get no `DataSource` any more, so the lane gate hand-off
  of these beans is gone (the transactions of the scope's `TenantAwareDataSource` hold the gate).

## [8.1.0] - 2026-10-17
### Changed
- The `tenancyTables` bean is no longer replaceable: a service that defines its own `TenancyTables` bean now fails start-up (INV-T-5821, owner answer INV-Q-4821 B). KDoc: a tenant id in `shedlock.name` is scheduling metadata, not tenant data (owner answer INV-Q-4817 A).

## [8.0.7] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5820, ruling INV-Q-5678 A): depends on `tenancy-jooq` 1.2.0 (the guard refuses a `TenancyTables` not built by `TableClasses.verify`, INV-Q-4821 B); no code change.

## [8.0.6] - 2026-10-17
### Changed
- `ConfigResolverWiring` builds `JdbcPolicyReader`, `JdbcPolicyHistory` and `JdbcPolicyWriter` (config-resolver 4.0.0) with the
  module's DSLContext, as it does the purge and the recorder (INV-T-4517; coordinator ruling INV-Q-5275); their beans are
  conditional on the `TransactionRunner` bean. The implTest `PolicyHttpHarness` declares config-resolver's other tables.

## [8.0.5] - 2026-10-17
### Changed
- `OutboxAndQueueWiring` (INV-T-5304, outbox-and-queue 4.0.0; coordinator rulings INV-Q-5275, INV-Q-5614): builds the
  outbox writer, relay, event binding, unrelayed-event check and event-log presence with the module's tenancy-jooq
  `DSLContext` (private to the bean methods, no bean of the context) or the `TenantScope`; the presence takes the
  event-log `TenantScope`; the relay gets the background lane's budget and refuses a run under 2 permits (a pool
  without lanes has no bound). The event binding keeps its creation conditions (no `EventCatalogue` needed, INV-Q-5851): it declares the topic tables of the
  `EventCatalogue` bean when the context has one, and none otherwise (a publish then fails until a catalogue is declared).

## [8.0.4] - 2026-10-17
### Changed
- `ConfigResolverWiring` builds `JdbcConfigPurge` and `JdbcResolutionRecorder` (config-resolver 3.0.0) with the module's
  DSLContext, `TenancyJooq.dslContext(ConfigTables.declared(schema), tenants, transactionRunner)`; their beans are
  conditional on the `TransactionRunner` bean (INV-T-5303; coordinator ruling INV-Q-5275). The DSLContext is not a bean (the
  context keeps its one, `TenancyJooqWiring`); the other beans of the file are unchanged.

## [8.0.3] - 2026-10-17
### Changed
- `EvidenceStoreWiring` (INV-T-4516, evidence-store 2.0.0; coordinator ruling INV-Q-5275) builds `JdbcEvidenceLedger` and `JdbcLegalHoldRegistry` with the module's tenancy-jooq `DSLContext` (`TenancyJooq.dslContext` over the `TransactionRunner` and `TenantContext` beans, the table declared `TENANT`) and no longer takes tenancy-kernel's `CurrentConnection`; the bean conditions are on the `TransactionRunner` bean. The `DSLContext` is private to the bean method, no bean of the context.

## [8.0.2] - 2026-10-16
### Changed
- `AuditLogWiring` (INV-T-5301, audit-log 3.0.0; coordinator ruling INV-Q-5275) builds `LocalAuditWriter` and `JdbcOverrideService` with the module's tenancy-jooq `DSLContext` (`TenancyJooq.dslContext` over the `TransactionRunner` and `TenantContext` beans, the table declared `TENANT`) and no longer takes tenancy-kernel's `CurrentConnection`; the bean conditions are on the `TransactionRunner` bean. The `DSLContext` is private to the bean method, no bean of the context.

## [8.0.1] - 2026-10-16
### Changed
- Metadata only: depends on `tenancy-kernel` 3.1.0 (adds `TransactionRunner.outsideOpenTransaction`, INV-T-5675); version step per ruling INV-Q-5678 (A). No code change.

## [8.0.0] - 2026-10-15
### Added
- The abandoned-run sweeper's function `job_run_in_progress(batch_size)` (sys-owned SECURITY DEFINER, execute for the
  runtime login only, batch 1..500, one jsonb array of the seven columns) replaces the view `job_run_in_progress_v`
  (coordinator ruling INV-Q-5115). The job-retention row is written by the Flyway `afterMigrate` callback of the
  migration runner (INV-Q-5113). `FlywayLoginPool` gives Flyway a HikariCP pool of the migrate login whose credentials
  are read through SecretStore for each new connection (INV-Q-5111). `LanePermits`, the plain permit class that
  implements tenancy-kernel's `LaneGate` (owner ruling INV-Q-4502 (A)).
- ShedLock's storage is service-runtime's own `ShedLockJooqAccessor` under ShedLock core's `StorageBasedLockProvider`
  (owner answer to INV-Q-5210, option A): the SQL of shedlock-provider-jooq on `DSL.name(schema, "shedlock")`, each
  operation in one platform binding and one `TransactionRunner` transaction. `shedlock-provider-jdbc-template` and
  `spring-jdbc` leave the build scripts; spring-jdbc stays only as a transitive of spring-boot-starter-jdbc.
### Changed (breaking)
- Every statement of `JdbcJobState`, `JobRuntime`, `IdempotencyStore` and `TransactionalOperationRunner` is jOOQ DSL on
  tenancy-jooq's DSLContext (owner ruling INV-Q-4196): their constructors take a `DSLContext`, and `JobRuntime` takes no
  DataSource. `EventLoop` takes `SqlErrors`; `ConsumerRebuildRuntime` reads SQLSTATEs through it (INV-Q-5121).
  `SqlConfigurationKeyGuard.verify` takes a resource lookup (INV-Q-5117). `PoolConfigurationGuard` reads a pool by direct
  getters, with no reflection (INV-Q-5109). `JobSchema.writeRetention` and `IN_PROGRESS_VIEW` are removed.
### Removed (breaking)
- `LanedDataSource` and `LaneConnection`: the application pool is the Hikari bean itself; the lane gate is the
  `LaneGate` bean `PoolLanePostProcessor` registers. `SecretStoreDriverDataSource`.
- Every raw user of the pools is behind the lane gate (coordinator ruling INV-Q-5243): the relay, claim queue, retention and
  event-log purges, triage, rate limiter, staleness monitor, event-log reader and presence, unrelayed-event check and
  inbox consumers get `OutboxAndQueueWiring.handOff(pool, gate)` (a `TenantAwareDataSource`), the HA event-log pool its own
  admit-all `EventLogGate`. `LaneGateHolder` and `EventLogGate` accept `PermitObserver`s (a test hook, none in production;
  coordinator ruling INV-Q-5251). `OutboxAndQueueWiring` takes the two gates as constructor arguments (breaking).

## [7.1.1] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5341, coordinator ruling INV-Q-5337, delegated, owner 2026-10-14): no API or behaviour
  change; `src/main` and `src/impl` are unchanged since 7.1.0.
- The POMs and module metadata of both publications (api and impl) now name the sibling modules at their current
  versions: config-resolver 2.1.2 (was 2.1.0), audit-log 2.3.4 (was 2.3.3) and tenancy-jooq 1.0.3 (was 1.0.2).

## [7.1.0] - 2026-10-15
### Added
- `setPolicy` and `acknowledgeControlRelaxation` over HTTP (INV-T-5180; coordinator rulings INV-Q-5172, INV-Q-5217,
  INV-Q-5233, INV-Q-5235 and INV-Q-5237; INV-REQ-1239, INV-REQ-1242, INV-REQ-1243, INV-REQ-1244; platform-contracts
  `service-runtime/openapi.yaml` 1.3.0): `POST /internal/v1/policies/{variationPoint}` and
  `POST /internal/v1/policies/{variationPoint}/relaxations`, served by `PolicyCommandServlet` onto the service's
  config-resolver `PolicyWriter` and mounted by `JobCommandHttpAutoConfiguration` only when a `PolicyWriter` bean exists.
  The actor and tenant come from the end-user token; the roles from the new api-half port `PolicyRoleResolver`
  (`rolesOf(actor, tenant, level, scopeRef, entityRef)`), which the owning service binds; unbound, an error, no answer or
  an empty set is 403 SRT-004. Scope-shape errors (`entityRef` equal to `scopeRef` for ENTITY, `scopeRef` and `entityRef`
  present exactly when) are 400 SRT-001; a TENANT `scopeRef` other than the token tenant's id is 403 SRT-004; with a
  stale entity set the `entityRef` is proved by the port. `Idempotency-Key` is kept in the existing `IdempotencyStore`.
  `PolicyErrorCode` maps `UnknownVariationPoint`, `VariationPointRetired` and every `PolicyRefusal` to POL-001..POL-024.
  Additive: no existing public type changes; `JobCommandHttpAutoConfiguration` now also needs only the principal resolver,
  scope and data source at class level (the job servlet itself still needs `JobCommands`).

## [7.0.0] - 2026-10-15
### Added
- The service's event loops (INV-T-3938; coordinator rulings INV-Q-3935 and INV-Q-4850, INV-ADR-3968; INV-REQ-1212, INV-REQ-1222).
  service-runtime now drives every `OutboxRelay`, `InboxConsumer` and `ReplayDelivery` bean of the application context
  (those OutboxAndQueueWiring creates and those a service declares, such as one `JdbcInboxConsumer` per topic), with no
  code in the service: one loop per bean on its own virtual thread in the background lane, started on
  `ApplicationReadyEvent` and stopped on shutdown before the DataSources close, finishing the call in hand. A loop
  repeats at once while a call did work, otherwise waits `service-runtime.event-loops.relay-interval`,
  `.consumer-interval` or `.replay-interval` (default 1 s each, 100 ms to 60 s, else start-up fails); a failing call is
  logged (exception class and SQLSTATE) and retried after a bounded, jittered backoff. `service-runtime.event-loops.enabled`
  (default true) switches every loop off. A loop ended by an Error is logged and sets readiness to `REFUSING_TRAFFIC`
  (`eventLoops` health contributor, in the default `ready` group); each loop exposes
  `gst.platform.event.loop.last.success.timestamp.seconds`. Additive: no port, API, event or schema change.
  When the event log shares the application pool (no `eventLogDataSource`), `OutboxAndQueueWiring.eventBinding` gives
  `PostgresEventBinding` the application `TenantScope`, so the event-log append joins the row's tenant transaction and
  commits with `published_at`: a relay run holds at most 2 permits of the background lane (the claim connection and the
  row's transaction), within the owner's lane budgets (4 / 2 / x), which are unchanged (coordinator ruling INV-Q-4850).
  With a separate event-log pool (HA) the append stays its own transaction on that pool. No lane budget changes.
- `JobSchema.platformScopedTables(schema)`: `shedlock` and `job_retention`, whose DDL carries the table comment
  `platform-scoped: no tenant data`. No JdbcTemplate, NamedParameterJdbcTemplate, TransactionTemplate or transaction
  manager is created. ShedLock's move to its jOOQ provider is INV-T-4203's.

### Changed (breaking)
- The framework tenant predicate on jOOQ, wired into every service (INV-T-4134; INV-ADR-3968 item 4a, INV-REQ-854 b2
  and b6; owner rulings INV-Q-4113, INV-Q-4196). `TenancyJooqWiring` (its own auto-configuration, after the L1
  composition and before Spring Boot's jOOQ auto-configuration) creates the `TenancyTables`, an `org.jooq.Configuration`
  and the `DSLContext` over tenancy-kernel's `TransactionRunner` with tenancy-jooq's listeners, and a Spring Boot
  `DefaultConfigurationCustomizer` for a service that has Boot's jOOQ support. `TenancyTables` is built by
  `TableClasses.verify` over the service's `org.jooq.Schema` beans: start-up fails for a tenantless table nobody
  declared, a declared table that is missing, or a declaration that disagrees with the columns. A service adds
  declarations with `inv.tenancy.platform-scoped-tables` and `inv.tenancy.reference-tables` (`schema.table`); the
  libraries declare their own (`ConfigSchema`, `OutboxSchema`, `EventLogSchema`, `JobSchema` `platformScopedTables`).
  `TenancyJooqGuard` fails start-up for any `DSLContext` or `org.jooq.Configuration` bean not made by tenancy-jooq
  (for example `DSL.using(dataSource)`). A service with a database (a `TransactionRunner`) supplies its generated
  schema as `org.jooq.Schema` beans in its bootstrap module; start-up fails when they hold no table (coordinator
  ruling INV-Q-4815). tenancy-jooq becomes a dependency of the impl half.

### Migration
1. Supply the service's generated jOOQ schema as `org.jooq.Schema` beans in the bootstrap module (start-up fails when
   a service with a database supplies none that holds a table, INV-Q-4815).
2. Declare each table without a tenant column in `inv.tenancy.platform-scoped-tables` or `inv.tenancy.reference-tables`
   (`schema.table`); start-up fails for an undeclared tenantless table, a declared table that is missing, or a
   declaration that disagrees with the columns.
3. Remove every `DSLContext` or `org.jooq.Configuration` bean not made by tenancy-jooq (for example
   `DSL.using(dataSource)`).
4. tenancy-jooq and jOOQ are now on the impl half's classpath.

## [6.0.1] - 2026-10-14
### Fixed
- Every actor check uses tenancy-kernel's `BlankText.isActor` instead of `isNotBlank()` (coordinator ruling
  INV-Q-4684 (A), INV-T-4686; INV-Q-4534, INV-Q-4614, INV-Q-4622; INV-REQ-854 b1): `EndUserPrincipal.actor`,
  `JobCommandRequest.actor` and `RebuildConsumerRequest.actor` refuse a value that is blank by the shared set (for
  example U+FEFF alone) or holds an ISO control character, with an `IllegalArgumentException` that names the rule
  and never echoes the value. `JwtEndUserPrincipalResolver` takes the `sub` of an operator-session token and of an
  identity-provider token only when it is a valid actor; otherwise it resolves to null (401), as for a missing
  `sub`.

## [6.0.0] - 2026-10-14
### Changed (breaking)
- A stricter start-up check (INV-T-4605; coordinator rulings INV-Q-4603 and INV-Q-4694, the runtime half of the boundary for
  string-based and programmatic bean creation; INV-REQ-854). 5.0.0 was already released, so this ships as the next
  MAJOR. `SqlConfigurationKeyGuard` and `SqlConfigurationKeyRecheck` now also refuse, naming the key and never the
  value, in any relaxed, list or indexed form (properties, environment variables, command line):
  `spring.autoconfigure.exclude` with an entry naming a class in `com.dynokore.platform` (it removes the pool check);
  `spring.main.allow-bean-definition-overriding` with any value but `false` (a bean could replace a guard);
  `spring.main.sources` (class names or XML locations loaded as bean definitions); `context.initializer.classes` and
  `context.listener.classes` (classes instantiated by name); `spring.application.admin.enabled` with any value but
  `false` (JMX, INV-Q-4591); and `spring.datasource.driver-class-name` with any value but `org.postgresql.Driver`.
- SpEL is refused where Spring evaluates it (coordinator ruling INV-Q-4694 (C)). `SpelRefusingExpressionResolver`, the
  bean factory's BeanExpressionResolver, never parses or evaluates: it returns text without `#{` unchanged and fails on
  any text containing `#{`, never printing the value (Spring's wrapping exception names the bean and the injection
  point). `SpelRefusalInitializer` (an ApplicationContextInitializer in `META-INF/spring.factories`) adds
  `SpelRefusingResolverInstaller`, a highest-PriorityOrdered BeanFactoryPostProcessor (no
  BeanDefinitionRegistryPostProcessor, INV-T-4593), which installs it before any bean-defined post-processor and any
  application bean; the same installer is a bean of `ServiceRuntimeConfiguration` for contexts built without
  `spring.factories`. It also gives Spring's standard resolver, already held by the EmbeddedValueResolver of every
  EmbeddedValueResolverAware bean, a parser that never evaluates. This covers `@Value` after placeholder resolution,
  every EmbeddedValueResolver user (`@Scheduled`, `@RequestMapping` and the rest), `@Value` method parameters and
  bean-definition strings. `SpelBoundaryRecheck`, in the second pass, fails start-up when the bean factory's resolver is
  not service-runtime's or when the ConditionEvaluationReport holds any OnExpressionCondition outcome. No property
  source is scanned for `#{`.
- `PoolConfigurationRecheck`, a `SmartInitializingSingleton` registered by service-runtime's auto-configuration beside
  `PoolConfigurationGuard`, checks every HikariDataSource bean (and every HikariConfig or DataSource wrapping one) again
  with the same deny-by-default rules (INV-Q-4566, INV-Q-4570, INV-Q-4574) once every singleton exists. A pool created
  before the guard was registered (a bean a `BeanFactoryPostProcessor` fetched early) or changed after its first check
  fails start-up, naming the bean and the properties without values (CS-24).

### Migration
1. A pool login or pool property set in code after the pool bean is created now fails at the second pass; mount the
   login in SecretStore and read it through `SecretStoreCredentialsProvider` (STK-29, INV-Q-4570), as `withMountedLogin`
   does in this repository's ITs.
2. Remove any `spring.autoconfigure.exclude` entry naming a class in `com.dynokore.platform`, and remove
   `spring.main.sources`, `context.initializer.classes` and `context.listener.classes`; set
   `spring.main.allow-bean-definition-overriding` and `spring.application.admin.enabled` only to `false` or leave them
   unset; set `spring.datasource.driver-class-name` only to `org.postgresql.Driver` or leave it unset.
3. SpEL is never evaluated: a `@Value`, embedded-value attribute or bean-definition string whose resolved text contains
   `#{` fails where Spring would evaluate it (at start-up for singletons), and any `@ConditionalOnExpression` fails
   start-up; replace them with `@ConfigurationProperties` binding. Property sources are not scanned, so unrelated
   environment variables (`CI_COMMIT_MESSAGE`, `CI_MERGE_REQUEST_DESCRIPTION`, orchestrator annotations) do not affect
   start-up or consumer tests.

## [5.0.0] - 2026-10-14
### Changed (breaking)
- Spring configuration keys that run SQL or set session state fail start-up (INV-T-4544; coordinator ruling
  INV-Q-4542; INV-ADR-3968 item 4c, INV-REQ-1230, INV-REQ-854 b8). `SqlConfigurationKeyGuard`, an
  `EnvironmentPostProcessor` in `META-INF/spring.factories`, runs before any bean in every service and in the migration
  runner, reads every property source in any relaxed form (properties, YAML, environment variables, command line) and
  names every offending key. Refused: every key under `spring.sql`, `spring.jpa`, `spring.r2dbc`, `spring.batch`,
  `spring.quartz`, `spring.session.jdbc`, `spring.integration.jdbc` and `spring.modulith.events.jdbc`; any key whose
  last segment contains `init-sql`, `init-sqls`, `init-s-q-l`, `initialize-schema`, `schema-initialization`,
  `schema-locations` or `data-locations` (so `spring.flyway.init-sqls`, `spring.datasource.hikari.connection-init-sql`,
  `spring.datasource.dbcp2.connection-init-sqls`, `spring.datasource.tomcat.init-s-q-l`); any `spring.datasource` key
  outside the allowlist `url`, `username`, `driver-class-name`, `type` (HikariDataSource only),
  `hikari.maximum-pool-size`, `hikari.minimum-idle`, `hikari.connection-timeout`, `hikari.idle-timeout`,
  `hikari.max-lifetime`, `hikari.keepalive-time`, `hikari.validation-timeout`, `hikari.leak-detection-threshold`,
  `hikari.initialization-fail-timeout`, `hikari.pool-name` (so `hikari.register-mbeans` (INV-Q-4574), `hikari.data-source-properties.*`,
  `hikari.schema`, `hikari.catalog`, `hikari.transaction-isolation`, `hikari.auto-commit`, `hikari.read-only`,
  `dbcp2.*`, `tomcat.*`, `oracleucp.*`, `schema`, `data`, `initialization-mode`, `password` and `hikari.password`); any
  `spring.flyway` key outside the allowlist `enabled`, `url`, `user`, `schemas`, `default-schema`, `create-schemas`,
  `locations` (`classpath:` only), `table`, `baseline-on-migrate`, `baseline-version`, `baseline-description`,
  `validate-on-migrate`, `out-of-order`, `clean-disabled` (`true` only), `connect-retries`, `connect-retries-interval`,
  `lock-retry-count`, `fail-on-missing-locations`, `postgresql.transactional-lock`, `placeholder-replacement`,
  `placeholders.*` (plain identifiers only, `SqlIdentifier`) (so `init-sqls`, `callback-locations`,
  `sql-migration-prefix` and `password`); any JDBC URL parameter in `spring.datasource.url`, `spring.flyway.url` or
  `gst.db.event-log-url` other than `loggerLevel` (`OFF` only), `sslmode` and `sslrootcert` (coordinator ruling
  INV-Q-4559); `hikaricp.configuration-file` in any form, the `hikaricp.configurationFile` system property and a
  class-path `org/postgresql/driverconfig.properties` (INV-Q-4559). Keys are matched in every relaxed form, including
  the `gst.db.event-log-url` environment variables, indexed or map-keyed SQL keys (`my.store.init-sqls[0]`) and keys
  passed as `server.servlet.context-parameters.*`. `SqlConfigurationKeyRecheck`, a `BeanFactoryPostProcessor`
  registered by `ServiceRuntimeConfiguration`, runs the same check again after configuration-class parsing, so a
  `@PropertySource` is checked too (INV-Q-4559). `PoolConfigurationGuard` checks what every HikariDataSource bean
  actually received, deny-by-default over every HikariConfig property, so an indirect path no key names is refused. `spring.datasource.password`, `spring.datasource.hikari.password` and
  `spring.flyway.password` are now refused with or without `service-runtime.datasource.credential` or
  `service-runtime.flyway.credential` (formerly only beside it; STK-29, coordinator ruling INV-Q-4548), and are also read
  by exact name, so a property source that cannot be enumerated is seen (INV-Q-4570).
- A pool's password comes only from SecretStore through the credential path (STK-29, TPL-16, INV-Q-3909; coordinator
  ruling INV-Q-4570): `PoolConfigurationGuard` fails start-up when a HikariDataSource bean has a password, from any
  property source or code, or a credentials provider other than exactly `SecretStoreCredentialsProvider`. MBeans are
  refused: `registerMbeans` must stay `false`, since they expose `HikariConfigMXBean.setPassword` over JMX (coordinator
  ruling INV-Q-4574). The HA event-log pool no longer copies the application pool's password, only its credentials
  provider; a service that logged in with a password property must use `service-runtime.datasource.credential`.
- No JMX export (coordinator ruling INV-Q-4591): every `spring.jmx` key other than `spring.jmx.enabled=false` fails
  start-up in any relaxed form, and `SqlConfigurationKeyRecheck` fails start-up, before any bean is created, on any
  bean definition of type `MBeanExporter` or a subclass (Spring Boot's JMX auto-configuration, `@EnableMBeanExport`, an
  exporter `@Bean`, a library's exporter). An exporter exports every HikariDataSource as HikariConfigMXBean with a
  writable Password, whatever `registerMbeans` is. Metrics, health and tracing stay HTTP on the management port.
- The HA event-log pool no longer copies the application pool's `connectionInitSql`: it sets no connection-init SQL
  and no schema (INV-Q-4542, INV-REQ-1230, INV-T-3982).
- One application DataSource per service (INV-T-3981; owner ruling INV-Q-3955, INV-REQ-1230): the role DataSources
  (`workerDataSource`, `eventLogRelayDataSource`, `eventLogClaimDataSource`, `eventLogReaderDataSource`,
  `eventLogPurgeDataSource`) are removed and every adapter uses the service's `DataSource` (`<svc>_runtime`). A second
  pool, `eventLogDataSource`, exists only when `gst.db.event-log-url` names a separate HA event-log instance: same login,
  at most 3 connections, `service-runtime.event-log-pool.minimum-idle` default 1 (INV-Q-4348).
- The workload principal is `svc:<gst.service>` (`WorkloadPrincipal`), passed to `TenantScope`; a scheduled run binds
  `svc:<svc>/job:<job>` (owner ruling INV-Q-4344 (b)). A `gst.service` that is not a lower-case service name fails
  start-up.
- `JobRuntime` and `IdempotencyStore` take no worker DataSource. Purges run through `<svc>_sys`-owned security-definer
  functions (`job_state_purge_expired`, `idempotency_key_purge_expired`); the runtime role has no DELETE on job or
  idempotency rows. `JobSchema.ddl` and `IdempotencySchema.ddl` take the sys role.
- `JobCommandServlet` binds the token's actor for every command (the actor-less fallback is gone); a tenantless token
  gets 403 SRT-004 unless the target is platform-level (INV-Q-4115), and an entityRef on a PLATFORM-scoped job from it
  is the 400 contract violation of INV-Q-3537. `JobCommands.registered()` lists the registered jobs.
- `IdempotencyStore` takes no `Clock`: a key's `created_at` and the purge cut-off are both read on the database clock,
  so a service clock skewed against the database neither fails the purge nor deletes a key early.
- `UnrelayedEventCheck` (INV-T-4337) reads the outbox on the application DataSource.
- The application pool is split into lanes (INV-T-3982; INV-REQ-1230 b1, INV-REQ-1072 b5, INV-ADR-3968 item 1, owner
  ruling INV-Q-4023, coordinator rulings INV-Q-4485, INV-Q-4487): the `dataSource` bean is a `LanedDataSource` over
  Spring Boot's HikariDataSource (unwrap to reach the pool). Request, background and reprocessing permits per
  `service-runtime.pool.profile` (`minimal`, `ha`, `saas`) and `service-runtime.pool.runs-reprocessing` (default
  false), with per-lane overrides `service-runtime.pool.lanes.*`; the pool maximum is their sum. There is no default profile
  (INV-Q-4496): start-up fails, naming the missing settings, when the profile is unset and any lane is not set, or the
  profile is unknown; when `spring.datasource.hikari.maximum-pool-size` differs from the sum,
  and when the reprocessing declaration and budget disagree. A thread is in the request lane unless it runs in
  `PoolLanes.inLane(...)`; job firings and service-runtime's own schedules run in the background lane. The pool's
  `application_name` is `<gst.service>`; each transaction names its lane, `<gst.service>:<lane>`, transaction-locally
  through tenancy-kernel 1.4.0's `ConnectionLabel`.
- `FullSyncWiring` takes only the read-model clears; a full sync runs on the application DataSource (INV-Q-4064).
### Added
- `MigrationRunner`, the migration entry point (INV-Q-3959): runs Flyway as `<svc>_migrate` and writes
  `service-runtime.jobs.retention` (default 30 days) into `job_retention`. The service no longer runs Flyway
  (`spring.flyway.enabled=false`), and `JobRuntime` refuses to start when `job_retention` does not hold its retention.
- Tests for local end-user token verification (keys fetched once, no network call per request) and for forged
  identity headers (401).

### Changed
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426); no behaviour or API change
### Fixed
- tests no longer depend on the calendar date (INV-Q-4638)

## [4.0.1] - 2026-10-13
### Fixed
- The published impl POM (`service-runtime`) declared no dependency (INV-T-4356; defect in INV-T-2860's and
  INV-T-3937's per-module publishing, STK-60, STK-01 rule (a), TPL-01): the `impl` publication was built with
  `artifact(implJar)` and no software component, so a consumer declaring platform-libs-bom and this impl artefact had
  to list `service-runtime-api` and the other modules itself. The impl POM now declares `service-runtime-api` at the
  same version and the first-level dependencies of the impl runtime classpath, generated and checked by the root
  build.gradle.kts (`verifyImplPoms`): audit-log-api:2.3.1 (compile), config-resolver-api:2.0.1 (compile),
  outbox-and-queue:2.1.1 (compile), outbox-and-queue-api:2.1.1 (compile), service-runtime-api:4.0.1 (compile),
  tenancy-kernel:1.3.1 (compile), tenancy-kernel-api:1.3.1 (compile),
  io.github.resilience4j:resilience4j-bulkhead:2.4.0 (compile),
  io.github.resilience4j:resilience4j-circuitbreaker:2.4.0 (compile), io.github.resilience4j:resilience4j-retry:2.4.0
  (compile), io.github.resilience4j:resilience4j-timelimiter:2.4.0 (compile),
  jakarta.servlet:jakarta.servlet-api:6.1.0 (compile), net.javacrumbs.shedlock:shedlock-core:6.10.0 (compile),
  net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.10.0 (compile), org.jetbrains.kotlin:kotlin-stdlib:2.4.20
  (compile), org.springframework.boot:spring-boot-starter-actuator:4.1.1 (compile),
  org.springframework.security:spring-security-oauth2-jose:7.1.1 (compile), org.springframework:spring-jdbc:7.0.9
  (compile), org.springframework:spring-web:7.0.9 (compile), tools.jackson.core:jackson-databind:3.2.3 (compile). The
  classes are unchanged.

## [4.0.0] - 2026-10-12
### Changed (breaking: 3.x -> 4.0.0; probes, scrapes and health-group keys move)
- Health, metrics and diagnostics move to the management port 8081 (INV-T-4125; owner ruling INV-Q-4103 A; API-33,
  INV-REQ-1228 b5, INV-REQ-1123 b4). `service-runtime-defaults.properties` sets `management.server.port=8081` and
  `management.endpoints.web.base-path=/`, so Spring Boot's separate management server serves API-33's `/health/live`,
  `/health/ready` (UP, DEGRADED or DOWN, FR-AH.17), `/metrics` (Prometheus format, `path-mapping.prometheus=metrics`)
  and the FR-AE.82 `/diagnostics`, with no token. `WorkloadTokenAuthenticationFilter` stays on `/*` of the application
  port with no exemption path; the application port serves none of these endpoints (401 without a token, 404 with one).
  The `/actuator/...` paths on the application port are gone: probes and scrapes use the management port
  (platform-release service-template, INV-T-4126).
- The health groups are named `live` and `ready` (formerly `liveness` and `readiness`; same members, status order and
  DEGRADED mapping): Spring Boot serves a group only at `/health/<group>`. A service that sets
  `management.endpoint.health.group.readiness.include` (TPL-16 before this release) renames the key to
  `group.ready.include`, and `group.liveness.*` to `group.live.*` (coordinator ruling INV-Q-4192).
- Start-up fails, with a message naming `live` and `ready`, when any property source (properties, YAML, environment
  variables) sets `management.endpoint.health.group.liveness.*` or `management.endpoint.health.group.readiness.*`, so a
  service cannot silently leave its db or eventLog out of `/health/ready`: `HealthGroupKeyGuard` (INV-Q-4192).
- A service may change the management port number in its own configuration, but start-up fails when the management
  server would share the application port (`management.server.port` equal to `server.port`, or 8080 while `server.port`
  is unset): `ManagementPortGuard`.
### Fixed
- A DOWN readiness answered 200: the configured `status.http-mapping.DEGRADED=200` replaced Spring Boot's own
  mappings, so the readiness probe never failed. The defaults restate `DOWN` and `OUT_OF_SERVICE` as 503 (FR-AH.17 b4:
  a cold start with the vault down fails visibly).
### Tests
- `ManagementPortIT`: a running service composed as TPL-18 (Tomcat on both ports) answers `/health/live`,
  `/health/ready`, `/metrics` and `/diagnostics` on 8081 without a token, 503 DOWN on `/health/ready` with the vault
  down; on the application port the same paths answer 401 without a token and 404 with a valid one, and a business
  endpoint 401 without a token; a changed management port works; equal ports fail start-up; a former `liveness` or
  `readiness` group key fails start-up (property or environment variable), the new `live` and `ready` keys start.

## [3.4.0] - 2026-10-12
### Added
- `ClosedErrorCode.RATE_LIMITED`, SRT-012 (HTTP 429, "Protective rate limit exceeded"; severity BLOCK, disposition
  TERMINAL, remediation owner Platform, like SRT-011), matching its row in platform-contracts gst-catalogue 1.15.0
  (INV-T-4092; INV-T-4055, coordinator ruling INV-Q-4059; INV-REQ-827 b3, INV-REQ-825 b6). The client retries after
  `Retry-After`; the API gateway's gateway-authz container emits it.
- `Problems.render(code, correlationId, detail, retryAfterSeconds)`, a public entry point that renders any closed
  code outside the servlet path as a `ProblemResponse` (status, headers, body): the API-20 envelope of
  `Problems.closed` with `Content-Type: application/problem+json`, and for a 429 `Retry-After` in delta-seconds, which
  is then required and not negative; any other code refuses a Retry-After. gateway-authz's extAuth denials (401
  SRT-003, 503 SRT-011, 429 SRT-012) carry the same envelope as service responses (ARC-19, CS-14; INV-T-4056).
- Tests: the vendored platform-contracts copy moves to commit 81ab6f4 (gst-catalogue 1.15.0); `ProblemsTest` checks
  SRT-012 against its catalogue row and the rendered denials against the error-envelope schema.

## [3.3.2] - 2026-10-12
### Fixed
- An idempotent command held two app-pool connections at once (INV-T-3947; coordinator ruling INV-Q-3945, defect in
  INV-T-2875's API-15 store against INV-REQ-957, ARC-05 and TPL-04). `IdempotencyStore.once` held a session advisory
  lock on its own app-pool connection for the whole command while the key lookup, the command and a no-state
  response's record each took another, so M concurrent commands on an app pool of maximum M deadlocked until
  HikariCP's `connectionTimeout`, and at most M/2 ran at once. The in-flight guard is now a transaction advisory lock
  (`pg_try_advisory_xact_lock`) taken, with the key lookup, as the first step of the command's own transaction
  (`TransactionalJobCommandJournal.begin`, called by `JobRuntime`'s run-now, pause and resume transactions); the
  effect and the API-15 record commit or roll back with it. A response that wrote no state has its guard, lookup and
  record in one transaction of the key's tenant. No connection is held outside the command's transaction, and the
  guard is safe behind a transaction-mode pooler. API-15 behaviour (replay, 422, 409 at once, retention, purge) and
  the job-command responses are unchanged; `IdempotencyStore` no longer takes the application DataSource. No change
  to the api half, pool sizes or pool settings.

## [3.3.1] - 2026-10-12
### Fixed
- The published `service-runtime-api` jar held no api classes (INV-T-3937; coordinator ruling INV-Q-3929, defect in
  INV-T-2860's per-module publishing, STK-60, FR-L.6). `implJar` had no output directory of its own, so it wrote the
  same build/libs file as the api `jar` and the impl classes overwrote it: every published `service-runtime-api` from
  1.2.0 to 3.3.0 is byte-identical to `service-runtime` and holds only the impl classes. `implJar` now writes to
  build/libs-impl, as every other split module. No source or API change; consumers of a `-api` artefact need 3.3.1.

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
