# Security events (INV-REQ-1384, INV-Q-10945 option A, INV-Q-10939; INV-T-10969)

`SecurityEventEmitter` is the one component that writes security-event OTLP log records. It posts an OTLP/HTTP JSON `LogsData`
with one `LogRecord` (body = class wire value; attributes `gst.security.class`, `user.id`, `gst.tenant.id`; no invoice
content) to `<TelemetryExport.otlpEndpoint()>/v1/logs`. `user.id` is the acting user ID, never a role (INV-Q-10647).

Configuration: `gst.security-events.*` (`class-attribute`, `allowed-attributes`, `queue-capacity`, `retry-max-attempts`,
`retry-backoff-millis`, `mapping`), defaults in `src/impl/resources/service-runtime-security-events-defaults.properties`.
`mapping` is a map from the exact audit action string to a class wire value (INV-Q-11054); a service's entries merge with
the shipped one by key. Keys are compared by equality. A key set through an environment variable is lower-cased by Spring and
does not match a camel-case action.

Drop line (INV-Q-11052): a record that finds the queue full or exhausts its attempts increments
`gst_security_events_dropped_total` and writes one ERROR line holding `SECURITY_EVENT_DROPPED` and a reason, a closed set:
`QUEUE_FULL`, `RETRIES_EXHAUSTED`. No catalogue code, no attribute value, no record content.

## Coverage (platform-libs origin/main 6755709d, svc-access origin/main 444bf69e)

| Class | Audit action in the effective mapping | Default or service | Where the action is produced |
|---|---|---|---|
| `sign_in` | none | none | no producer (INV-Q-11062, owner question) |
| `elevation` | `openOperatorSession` | declared by svc-access in INV-T-4154 | no producer (INV-T-4154) |
| `break_glass` | `unlockBreakGlass`, `recordBreakGlassAction` | declared by svc-access in INV-T-11068 | svc-access `access-application/src/main/kotlin/com/dynokore/gst/access/application/breakglass/BreakGlass.kt` lines 122 and 178 |
| `credential_use` | none | none | no producer (INV-Q-11062, owner question) |
| `control_relaxation` | `acknowledgeControlRelaxation` | shipped default | platform-libs `config-resolver/src/impl/kotlin/com/dynokore/platform/configresolver/JdbcPolicyWriter.kt` (constant line 281, audit entry line 226) |
