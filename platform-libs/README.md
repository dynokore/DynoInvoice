# platform-libs

The shared modules of the INV platform: the L1 modules, the L2 modules and the L3 module `product-runtime` (SDS §18.8, §18.9, §18.15; STK-60).

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| Module | Layer | Wave |
| --- | --- | --- |
| [`service-runtime`](service-runtime/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`outbox-and-queue`](outbox-and-queue/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
| [`tenancy-kernel`](tenancy-kernel/) | L1 | Wave 1: Contracts, harness and platform (SDS §25.1) |
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
