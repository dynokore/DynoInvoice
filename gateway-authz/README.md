# gateway-authz

The API gateway's token-validation and rate-limit container (INV-ADR-4051, owner ruling INV-Q-4046 A). It runs
inside every API gateway pod, as part of the api-gateway component (INV-MOD-2752), and Envoy Gateway (STK-70) calls
it on every request through SecurityPolicy extAuth over a pod-local Unix socket (failOpen false). It has no database
and no business logic; the api-gateway chart in platform-release pins its image (INV-T-3990).

Keel task: INV-T-4056. Requirements: INV-REQ-872, INV-REQ-1202 b5, INV-REQ-825 b5 and b6; rulings INV-Q-3973 A,
INV-Q-4006 A, INV-Q-4008 A, INV-Q-4021 A, INV-Q-4046 A, INV-Q-4048 A, INV-Q-4077, INV-Q-4079, INV-Q-4081, INV-Q-4083,
INV-Q-4087, INV-Q-4089; contract INV-API-4001 (getRevocationList, getVerificationKeys).

## What it does

- **Check service** (INV-Q-4077): envoy.service.auth.v3.Authorization/Check on Spring Boot's own gRPC server
  (`spring-boot-starter-grpc-server`, grpc-netty-shaded's epoll transport) bound to the Unix socket
  `spring.grpc.server.netty.domain-socket-path` (env `GATEWAY_AUTHZ_SOCKET`), with Envoy's published stubs
  (`io.envoyproxy.controlplane:api`). No call leaves the pod per request (`check/`).
- **Route classes** (INV-Q-4083), from the per-route extAuth context the api-gateway chart sets (INV-T-3990):
  `route-class` = `machine-api`, `signed-link`, `ap-notification` or `console`; `path-template` = the route's contract
  path template; on a signed-link route `link-parameter` = the path parameter that carries the link reference. A
  missing or unknown class or a path off its template is a platform configuration fault (INV-Q-4095): 500 SRT-010
  without detail, logged at error level and counted on `gateway_authz_route_configuration_faults_total`. Machine API
  routes need a bearer token (none: 401 SRT-003); signed-link and AP notification routes take none and are counted
  per link and per `{adapterId}`; console routes take no token check and no limit at the gateway (owner ruling
  INV-Q-4085 A: the console session is a cookie, and console-bff applies the console limits).
- **Tokens** (INV-Q-4081, RFC 9068): `typ` `at+jwt`, a signature by the cached key matched by `kid` and `alg` (the
  key's DER SubjectPublicKeyInfo, INV-Q-4079; RSA and EC algorithms), `iss` = `gateway-authz.token.issuer`, `aud`
  containing `gateway-authz.token.audience`, `exp` not passed, `iat` and `sub` present; anything else is 401 SRT-003
  in the error envelope. Every token the gateway validates is a machine token: a `scope` that is not exactly one API-23 machine
  scope is 401 (INV-Q-4085 A). An admitted
  request keeps Authorization, traceparent and X-Correlation-Id unchanged and loses the inbound identity headers
  `X-Workload-Token` and `X-Deadline-Ms` (fixed, INV-Q-4099) plus `gateway-authz.additional-identity-headers`
  (`token/`).
- **Key material, refreshed in the background** (INV-Q-3973 A): the verification keys from access-svc's
  getVerificationKeys and the revocation list from getRevocationList (a snapshot, then deltas by sinceVersion, a new
  snapshot when access-svc answers with one), at least once a second with one request in flight, authenticated only
  with the pod's projected service-account token (INV-Q-4006 A), held in memory (`refresh/`).
- **Revocation** (INV-Q-4008 A): a PRINCIPAL or SUBJECT entry refuses a token of that principal or subject whose `iat`
  is at or before `revokedAt`; a TOKEN entry refuses that `jti`; an entry stops refusing at its `expiresAt`
  (`revocation/`).
- **Management port** (INV-Q-4103 A): `/health/live`, `/health/ready`, `/metrics` (Prometheus) and diagnostics are
  served only on `management.server.port` 8081 (env `GATEWAY_AUTHZ_MANAGEMENT_PORT`), which has no workload-token
  filter and is never routed by the gateway; the api-gateway chart probes it.
- **Readiness** (INV-Q-4008 A): `/health/ready` (503 while DOWN) is DOWN until the keys and one full snapshot are loaded (and while
  the token settings are missing); afterwards a failed refresh serves the cache.
- **Staleness** (INV-Q-4021 A): once the list is older than `gateway-authz.staleness-bound` (default 30 s,
  tighten-only), machine tokens keep verifying from the cache and no route answers 503 for staleness (INV-Q-4085 A). `gateway_authz_revocation_list_stale` is 1 while the list is stale and drives the
  alert; `gateway_authz_revocation_list_age_seconds` is the list's age (`staleness/`).
- **Protective rate limits** (INV-REQ-825 b5 and b6, INV-Q-4048 A, INV-Q-4087, INV-Q-4089): a machine token per
  principal (policy named by its scope, e.g. `ingest`) and per `tenant_id` (`tenant`); signed-link pages per link (`signed-link`); AP notifications per
  adapter at the machine per-principal value (`ap-callback`). A token without `tenant_id` is counted per principal
  only. Over 60 s, each replica applies the configured limit divided by `gateway-authz.ready-replicas`, rounded down
  and at least 1. `RateLimit-Policy` and `RateLimit` list every policy that applies, on allowed responses (for Envoy
  to add) and refusals; a 429 carries `Retry-After`, the latest reset of the exhausted policies (`ratelimit/`).

## Settings (`gateway-authz.*`)

| Setting | Default | Rule |
| --- | --- | --- |
| `refresh-interval` | 500 ms | at most 1 s (INV-Q-3973 A) |
| `staleness-bound` | 30 s | may only be tightened (INV-Q-4021 A) |
| `ready-replicas` | 1 | the ready gateway replica count the limits are divided by (INV-Q-4048 A) |
| `rate-limits.machine-per-principal` / `machine-per-tenant` | 600 / 1,200 | may be raised or lowered |
| `rate-limits.signed-link-per-link` | 30 | may only be lowered (tighten-only) |
| `token.issuer` | none (env `GATEWAY_AUTHZ_TOKEN_ISSUER`) | required: the machine-token issuer; not ready without it (INV-Q-4081) |
| `token.audience` | none (env `GATEWAY_AUTHZ_TOKEN_AUDIENCE`) | required: the installation's public API; not ready without it (INV-Q-4081) |
| `additional-identity-headers` | none | added to the fixed `X-Workload-Token`, `X-Deadline-Ms`; tighten-only (INV-Q-4099) |

A value outside its rule fails start-up, so a raised tighten-only setting is never applied.

## Build

Kotlin on JDK 25 with platform-build's `gst.bootstrap` convention plugin and the platform-libs BOM, both from their
package registries (`settings.gradle.kts`; locally export `GST_REGISTRY_TOKEN`, a token with `read_api`). There is no
local JDK requirement: `docker run --rm -e GST_REGISTRY_TOKEN -v "$PWD":/w -w /w gradle:9.8.0-jdk25 ./gradlew check`.
The image is built by `bootBuildImage` on the pinned Paketo builder (STK-53) in CI (`.gitlab-ci.yml`).

## Status

See the INV-T-4056 merge request for what is built and what waits on open questions.

## Build

`settings.gradle.kts` reads the platform-build release from the `platformBuild` line of `gradle/libs.versions.toml`
(a release published in platform-build's package registry, project 225). In CI the job token reads the registries;
locally export `GST_REGISTRY_TOKEN`, a personal access token with `read_api`.

```sh
./gradlew check
./gradlew build --dependency-verification strict
```

## Dependency locking and verification

`gradle.lockfile` locks every configuration. Every artefact the build downloads, plugins, POMs and module metadata
included, is verified against `gradle/verification-metadata.xml` in strict mode, so an artefact without a matching
entry fails the build. No build script or CI job disables verification.

Key servers are off, as in platform-build: CI has no key-server egress. This repository commits no keyring, so each
signing key is an `ignored-key` with a reason and its artefact is pinned by SHA-256. Check every new SHA-256 against
the checksum the artefact's repository publishes before you commit it.

When a dependency or plugin changes:

1. Use an empty Gradle user home (for example `--gradle-user-home "$(mktemp -d)"`). With a warm cache, artefacts that
   are already cached get no entry, and CI fails.
2. Write the locks and the checksums in one pass, naming only tasks that exist in this build:

   ```sh
   ./gradlew compileKotlin compileTestKotlin check test --continue \
     --write-locks --write-verification-metadata sha256 --no-configuration-cache
   ```

3. Run the CI resolve-all and licence-input init scripts of platform-build (`ci/build.gitlab-ci.yml`) the same way, then
   add an `ignored-key` for each new signing key, and keep the comment in the metadata's `configuration` block.
4. Delete the `settings-gradle.lockfile` Gradle writes for the version catalogue (it holds only
   `empty=incomingCatalogForLibs0` and is not committed).
