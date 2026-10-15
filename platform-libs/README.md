# platform-libs

The shared modules of the INV platform: the L1 modules, the L2 modules and the L3 module `product-runtime` (SDS §18.8, §18.9, §18.15; STK-60).

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| Module | Layer | Wave |
| --- | --- | --- |
| [`service-runtime`](service-runtime/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`outbox-and-queue`](outbox-and-queue/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`tenancy-kernel`](tenancy-kernel/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`tenancy-jooq`](tenancy-jooq/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`config-resolver`](config-resolver/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`audit-log`](audit-log/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`rulepack-kernel`](rulepack-kernel/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`evidence-store`](evidence-store/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`adapter-runtime`](adapter-runtime/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`party-registry`](party-registry/) | L2 | Wave 2: Foundation services (SDS §25.1) |
| [`calendar-and-periods`](calendar-and-periods/) | L2 | Wave 2: Foundation services (SDS §25.1) |
| [`document-lifecycle`](document-lifecycle/) | L2 | Wave 3: Document pipeline (SDS §25.1) |
| [`integration-canon`](integration-canon/) | L2 | Wave 3: Document pipeline (SDS §25.1) |
| [`reconciliation-engine`](reconciliation-engine/) | L2 | Wave 4: Evidence, reconciliation, notification and console (SDS §25.1) |
| [`product-runtime`](product-runtime/) | L3 | Wave 1: Contracts, harness and platform (SDS §25.1) |

## Rules for this repository

- **One directory per module.** Each module has its own version, changelog and release. Services use only published versions, never source paths (FR-L.6).
- **Modules in this repository build against each other at the same commit** and publish exact version constraints on one another. A change that crosses modules is one merge request.
- **Dependency order is enforced.** L1 modules use one another only downward, in the order of SDS §18.9 ("Order within L1"); an L2 module uses only L1 modules; no module names a service or a product event (product wiring is in `product-runtime`). CI will check this.
- **CI builds the changed modules and everything downstream of them** in this repository.
- **A bill of materials** names a set of module versions tested together; a service bumps that one line. `platform-libs-bom` has no hand-edited version or changelog (see "Releasing").
- **Ownership** is per directory (CODEOWNERS, once teams are named).
- A module moves to its own repository when it gains consumers outside INV, its release cadence stays different from the rest, or a separate team owns it.

## CI

`check` is the merge gate: every task `check` depends on, in every module, must pass before a merge request merges. The jobs that run on every merge-request and `main` pipeline:

- `compile`: compiles the changed modules and everything downstream of them.
- `unit-test`: the tests of the changed and downstream modules (implTest included). On every pipeline, whatever changed, it also runs the service-runtime Konsist tests that scan the real modules: SqlGuardsTest, the real-scope case of L1DependencyOrderTest ("the real modules keep the L1 order", every module and source set) and CompositionRootDirectionTest. The other cases of L1DependencyOrderTest self-test the rules against fixture repositories. Two negative tests plant an evidence-store import of an outbox-and-queue type, and a service-runtime import of a product-runtime type, and require the real-scope case to fail on the rule's own message. The layer rules are described under "Layer rules".
- `lint`: spotlessCheck and detekt.
- `verify-rules` (INV-T-4715): `./gradlew check --continue` over every module with `ci/verify-rules.init.gradle`, which disables only the Test tasks and the lint tasks the jobs above run. Every other task wired into `check` runs here, including the bytecode SQL-placement rules (verifyRawSqlPlacement and each verify<SourceSet>SqlPlacement), verifyNoTenancyProbeOnMainClasspath, verifyBannedDependencies, checkDependencyCatalogUsage, verifySplitJars and verifyImplPoms, and any task added to `check` later. It needs no Docker. It fails unless verifyRawSqlPlacement ran in every module that has it, and its negative test must see a raw JDBC call in crypto rejected.
- `contract-tests`, `split-jars`, `impl-poms`, `bom-verify`, and the shared template's Semgrep and Trivy jobs.

## Layer rules

`service-runtime/src/test/kotlin/com/dynokore/platform/serviceruntime/L1DependencyOrderTest.kt` is the only layer gate in this repository (the bytecode boundary rules do not apply to `gst.library`). The model map it enforces, `MODEL` in that file, lives in the test: one entry per module with its layer and its INV-MOD key (the "Order within L1" lines as amended by coordinator ruling INV-Q-4732, plus L2 and L3). It does not use `L1_ALLOWED_USES` of platform-build's konsist-rules. For every module that declares `gstLibrary.layer`, in every source set:

- **Layer**: L1 imports only the L1 modules its entry allows (transitive closure of its direct uses; crypto is usable by every L1 module; service-runtime, the composition root, may use every L1 module and no L1 module uses it), and no L2 or L3 type. L2 imports only L1. L3 imports L1 and L2. A module with no source files does not fail the rule and is checked once it has one.
- **Package ownership**: every Kotlin and Java file declares a package under `com.dynokore.platform.<module name without hyphens>`. The only exceptions are two exact (path, package) pairs, `tenancy-jooq/src/springTest/java/org/jooq/impl/SpoofField.java` and `SpoofWindow.java` with package `org.jooq.impl`, which prove the tenant guard refuses a custom part declared in jOOQ's own package (coordinator ruling INV-Q-4760). They waive package ownership only; the layer rule still applies, and an entry whose file is absent or declares another package fails. The test prints the exceptions it applied.
- **Completeness**: a module that declares a layer (in its build file, or in the generated `gst/layer.properties`) must be in the map with the same layer, and a module included in `settings.gradle.kts` must declare a layer unless its name is exactly one of eight: `platform-libs-bom`, `contract-tests` and the six generated `product-runtime-*` client and event-type projects. A new module cannot skip the rules by declaring nothing.

## Releasing

- **A module** release: bump the module's own `version.txt` and `CHANGELOG.md` in its merge request.
- **The BOM** is never bumped by hand. A module merge request that adds a new module's constraint pair, or changes the module version a pair resolves to (`project(":x").version`), edits only `platform-libs-bom/build.gradle.kts`, and does not touch a BOM version or changelog. Parallel module merge requests therefore do not conflict on the BOM.
- **On a `main` pipeline** the `maven-publish` job runs `ci/bom-version.sh`. It reads the latest `platform-libs-bom` version in this project's package registry (job token), and compares the constraint set the build resolves with that published POM. If they differ it publishes minor+1 (for example `0.10.0` to `0.11.0`); if they are the same it publishes nothing. Only the commit at the tip of `origin/main` publishes, and the job fails on any `unspecified` version or if the next version already exists. `platform-libs-bom/CHANGELOG.md` is frozen history up to 0.12.0. Nothing is committed or pushed from CI.
- **The BOM changelog entry** (the constraints added, removed or changed, with the pipeline and commit) is in the job log and published with the BOM as `platform-libs-bom-<version>-changelog.txt` in the registry.
- **On a merge request** the `bom-verify` job checks that the BOM resolves and prints what `main` would do. It never publishes.
- A local build versions the BOM `0.0.0-local`.

## Building locally

`platform-build`'s `gst.*` convention plugins and its shared Konsist rule set are
consumed as published artefacts from its package registry (project 225), at the
`platformBuild` version in `gradle/libs.versions.toml`. CI authenticates with the job
token. Locally, export a personal access token with `read_api` before running `./gradlew`:

```sh
export GST_REGISTRY_TOKEN=<your token>
```

## Dependency locking and verification

STK-72 applies (INV-T-4297, coordinator ruling INV-Q-4367). Every resolvable configuration of every project, `platform-libs-bom` included, is locked in strict mode:

- the settings classpath in `gradle/settings-gradle.lockfile` (INV-Q-4375). Gradle writes the version catalogue's own lock state to the default `settings-gradle.lockfile` and would drop the classpath entries there, so the root `settings-gradle.lockfile` holds only `empty=incomingCatalogForLibs0`, and `settings.gradle.kts` fails the build when that file is missing or holds anything else;
- each build script's plugin classpath in `buildscript-gradle.lockfile`, at the root and next to each module's `build.gradle.kts`;
- each module's configurations in its `gradle.lockfile`.

The staged same-commit `platform-contracts:*` coordinates (unpacked by product-runtime from the locked and SHA-256-verified `platform-contracts-bundle` zip into `product-runtime/build/contracts-repo`) resolve only from that staging repository (`exclusiveContent`), are resolved by no locked configuration, and are trusted in the verification metadata for that group only (INV-Q-4377, as INV-Q-4322 (3)).

A configuration without lock state, a resolution outside the lock files, or a deleted lock file of any of the three kinds fails the build.

Every artefact the build downloads, plugins, POMs and module metadata included, is verified against `gradle/verification-metadata.xml` in strict mode, so an artefact without a matching entry fails the build. Signatures are checked against the committed `gradle/verification-keyring.keys` and `.gpg`. Key servers are off, as in platform-build: CI has no key-server egress, and a key-server fetch must never block a build. A key that is not in the keyring is an `ignored-key`, and its artefact is checked by its SHA-256 checksum, as are unsigned artefacts. No build script or CI job disables verification.

When a dependency or plugin changes:

1. Use an empty Gradle user home (for example `GRADLE_USER_HOME=$(mktemp -d)`). With a warm cache, artefacts that are already cached get no entry, and CI fails.
2. Leave key servers off. To check a new artefact's signature, first add its signer's key to `gradle/verification-keyring.keys` (for example from platform-build's keyring). Otherwise the new key becomes an `ignored-key`.
3. Run the pipeline's task set, naming only tasks that exist in this build (a missing task name fails task selection and leaves only configuration-time resolution), and write the locks, the metadata and the keys in one pass:

   ```sh
   ./gradlew resolveAndLockAll compileKotlin compileTestKotlin spotlessCheck detekt \
     checkDependencyCatalogUsage verifyBannedDependencies verifySplitJars listPublications \
     publishToMavenLocal assemble -x test -x implTest --continue \
     --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
   ```

4. Keep the comment in the metadata's `configuration` block, and check every SHA-256-only entry against the artefact's published checksum (`.sha256`/`.sha512`, else `.sha1`) before you commit. Plugin marker POMs differ between the Gradle Plugin Portal and Maven Central: keep both checksums (`also-trust`), each one checked against its own repository.
