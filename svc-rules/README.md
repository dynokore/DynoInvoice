# svc-rules

`rules-svc` — Rules as versioned data — which rules apply at a date, and what they say about a transaction, reproducibly.

| | |
| --- | --- |
| Design | [SDS §28.4](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 2: Foundation services (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | CPU-bound, stateless evaluation |

## Owns

Pack registry metadata; activation state and instance acknowledgements; drafts, approvals and dry-run results; import history of mapping spreadsheets (FR-AG.57).

## Provides

The evaluation API to canonical-svc, and to payload-svc for pre-issuance drafts. Authoring, approval and activation commands to console-bff.

## Layout (TPL-01)

| Module | Holds |
| --- | --- |
| `rules-contract` | Generated from `platform-contracts/rules/` (openapi.yaml; asyncapi.yaml and `schemas/` as resources). Never hand-written. |
| `rules-domain` | Pure Kotlin: pack entries and their sources, the pack lifecycle (an armed pack is never edited, a correction is a new version), resolution by transaction date over the rulepack-kernel, pure evaluation with its decision trace. |
| `rules-application` | The evaluation use case and the port events leave through. Framework-free. |
| `rules-adapters` | Controllers (501 until their task), the jOOQ lookup of armed packs, the outbox emitter and event catalogue, Flyway migrations of schema `rules`. |
| `rules-bootstrap` | The Spring Boot composition root (TPL-18), `application.yaml` (TPL-16), the architecture tests and the two repository gates. |

## Gates

- `operations.tsv` lists every operationId and channel of the rules contract with the task that implements it. `./gradlew verifyOperations` fails when the contract holds one the table lacks, when the table names one the contract does not hold, or when a row names a task listed in `done-tasks.txt` (one INV-T key per line; each task's merge request adds its own) while the operation still answers 501. `OperationsOwnersTest` and the gate compare the `task` column row by row with the owners of INV-T-4965 (INV-Q-7442).
- `ac-keys.tsv` lists one row per acceptance criterion the tasks of this service carry (criterion, task, wave: 2 for a criterion run by this service's tasks, 3 or 4 for a deferred one). `./gradlew acKeyCoverage` fails when the table is missing or empty, when a wave-2 row has no test whose name holds the key as a whole token, or when a test name holds a key that is in no row. Every later task of this service appends the rows of its own `ac_refs` and its deferred criteria.
- Both gates are in `check`; their logic is Kotlin in `rules-bootstrap/src/test/kotlin/.../gates/` with probe tests.

## Database

Schema `rules`, roles `rules_migrate` (owns every table, runs the migrations through service-runtime's `MigrationRunner`), `rules_runtime` (the one runtime login) and `rules_sys` (NOLOGIN, owns the definer functions). Every table has `tenant_id uuid NOT NULL`, forced row-level security and policies on the signed tenant binding. The platform rows (regulatory packs) are written only through `rules.register_platform_pack` and `rules.advance_platform_pack`. The registry holds metadata and activation state, no artefact content. `V2__outbox.sql` is generated from outbox-and-queue and checked by `MigrationDdlTest`; regenerate with `-Dgst.regenerate=true`.

Precondition: `rules.bound_tenant()` and `rules.bound_actor()` exist in the schema (tenancy-kernel `BindingKeyMigration`, run as the sys login). In an installation the sys login runs it (owner question INV-Q-6580); the code generation installs it in its throwaway database from the tenancy-kernel jar (`gstJooqCodegen.bindingSource`, INV-Q-7418).

## Dependency locking and verification

STK-72 applies as in platform-libs: every resolvable configuration is locked in strict mode (`gradle.lockfile` per module, `buildscript-gradle.lockfile` for the plugin classpath, `gradle/settings-gradle.lockfile` for the settings classpath) and every downloaded artefact is verified against `gradle/verification-metadata.xml`. When a dependency or plugin changes: use an empty Gradle user home, then run

```sh
./gradlew resolveAndLockAll compileKotlin compileTestKotlin spotlessCheck detekt checkDependencyCatalogUsage \
  verifyBannedDependencies assemble -x test --continue \
  --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
```

and check every SHA-256-only entry against the artefact's published checksum before committing. No build script or CI job disables verification; a build with `--dependency-verification off` is refused by the shared build template.
