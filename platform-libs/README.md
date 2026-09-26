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
- **A bill of materials** names a set of module versions tested together; a service bumps that one line.
- **Ownership** is per directory (CODEOWNERS, once teams are named).
- A module moves to its own repository when it gains consumers outside INV, its release cadence stays different from the rest, or a separate team owns it.

## Building locally

`platform-build`'s `gst.*` convention plugins and its shared Konsist rule set
are not published to a registry yet, so `settings.gradle.kts` consumes them
from a **sibling checkout**: it expects `platform-build` checked out next to
this repository, at `../platform-build` (the same layout `~/projects/invoice/`
already uses). Clone it there before running `./gradlew` locally:

```sh
git clone http://registry.example.internal/invoice/platform-build.git ../platform-build
```

CI does the equivalent clone itself (`.gitlab-ci.yml`), so this is only needed
for a local build. Once `platform-build`'s registry publishing lands, this
requirement goes away.
