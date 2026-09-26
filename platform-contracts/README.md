# platform-contracts

One published source for every cross-service contract

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Kind | Contract artefacts |
| Contains | The event envelope and every event schema, domain and platform (§19.8, FR-AH.15); the error envelope; the `ErrorCode` and `VariationPoint` catalogue artefact of `gst-catalogue`; the API contracts of §19 and every service's published API; the lifecycle states of §33.2 |
| SaaS | Not deployed; consumed at build time and published per train |
| Private | Same |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |
| Design | [SDS §18.14](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |

Also holds the topic definitions of the `event-log` component, which follow from the event schemas.

## Layout (INV-T-2849)

One directory per owning service (API-01, FR-AH.13, the OW1 ownership decision):
`<service>/openapi.yaml`, `<service>/asyncapi.yaml`, `<service>/schemas/`. `CODEOWNERS`
at the repository root names who may change each one — every directory
defaults to `@inv-reviewer` today; see `CODEOWNERS`'s own header for why and
what changes it once real per-service teams exist. `service-runtime/` is not
a service: it holds the OpenAPI/JSON-Schema components every other directory
references by relative `$ref` (the error envelope, API-20; the CloudEvents
envelope, API-40) rather than each redeclaring its own copy.

Every `openapi.yaml`/`asyncapi.yaml` currently holds one placeholder
operation/event, so the directory lints clean and demonstrates the
conventions (including the `x-operation` extension, FR-AC.1/FR-AC.19) from
its first commit. A service's own team replaces the placeholder with its
real operations and events as each lands — this scaffold does not gate that
on anything.

**The pre-existing `api/`, `errors/` (the shared error envelope schema),
`event-log/`, `events/` and `lifecycle/` directories are unchanged** — they
predate the OW1 decision this task implements and are out of its declared
scope. Migrating their content into the relevant service directories (and
`service-runtime/` for what is genuinely shared) is a follow-up, named here
rather than done silently. `catalogue/` is no longer one of these
unchanged directories: INV-T-2851 (T7) built it (see below).

## `gst-catalogue` (INV-T-2851)

`catalogue/` is its own, separate Gradle build (`catalogue/settings.gradle.kts`),
distinct from this repository's root build above — it is the one directory here
with real Kotlin source, so it needs the Kotlin toolchain, detekt and Spotless
the root build does not.

```
catalogue/
├── errors/error-codes.yaml   vendored, pinned-commit copy of invoice-docs'
│                             model/architecture/error-codes.yaml (STK-61) — the
│                             generator's sole input, never hand-edited; re-vendor
│                             it (with the new commit SHA in its header) to pick
│                             up a design change
├── generator/                an included build (pluginManagement, like
│                             platform-build's build-logic/ one level down):
│                             provides the gst.catalogue-generator plugin —
│                             parses error-codes.yaml, emits ErrorCode.kt and the
│                             catalogue artefact JSON, and the two acceptance
│                             checks below. Not published: single in-repo consumer.
└── gst-catalogue/             the published module: `ErrorCode` (code, condition,
                              severity, disposition, owner — CS-13), generated,
                              never hand-edited
```

Two Gradle tasks, wired into `check`:

- `checkCatalogueStability` (INV-AC-2170) — no code changes its number or
  condition since the previous published train. Skips (does not fail) when no
  previous train artefact exists yet — `catalogue/errors/previous-train-catalogue.json`,
  absent for this first train (1.0.0), the same "nothing to compare against yet"
  convention this repository's own `oasdiff-breaking-gate` already uses.
- `checkBlockDispositionCauses` (INV-AC-2173) — every `BLOCK` code has the
  disposition its cause calls for (INV-REQ-1186). Only the codes the live Keel
  text names explicitly (MDM-003, PAY-006, PAY-001, PAY-010, SUB-006, SUB-009,
  RUL-005) are asserted against their classified cause; every other `BLOCK` code
  is listed for decision in the task's report
  (`build/reports/catalogue/block-disposition.txt`) rather than guessed at —
  INV-NOTE-3 §21.2, which classifies the rest, is an archived document this
  repository's coding agents do not read.

Built and published via `gradle -p catalogue <task>` (`catalogue-compile`,
`catalogue-verify`, `catalogue-publish` in `.gitlab-ci.yml`) rather than through
the shared `ci/build.gitlab-ci.yml`/`ci/publish.gitlab-ci.yml` templates, which
invoke `./gradlew` — this repository still commits no wrapper (unchanged from
T5). The publish job reuses those templates' Maven-registry *conventions*
(`gstMavenUrl`/`gstMavenUser`/`gstMavenPassword`, "main/tag only") against this
project's own package registry rather than platform-build's shared one, since a
cross-project publish would need a Token Access allowlist entry on
platform-build that does not exist yet (ci/README.md, platform-build).

### CI (`.gitlab-ci.yml`)

- `spectral-lint` — `spectral/ruleset.yaml` against every `openapi.yaml` and
  `asyncapi.yaml` (API standard rules named `inv-api-nn`; the FR-AC.1/FR-AC.19
  `x-operation` rules are `inv-fr-ac1-*`).
- `asyncapi-validate` — the AsyncAPI CLI's own schema validation.
- `breaking-change-self-test` — proves the gate itself works, against the
  fixtures in `spectral/fixtures/`, independent of whether any real contract
  changed in a given pipeline.
- `oasdiff-breaking-gate` / `asyncapi-breaking-gate` — merge-request pipelines
  only: fail on a breaking change to any file that already existed at the
  merge request's base commit (API-03, API-41). A new file has nothing to
  compare against and is skipped, not failed.
- `bundle-publish` — `main`/tag pipelines only: packages every directory's
  contracts into one versioned archive (`build.gradle.kts`'s
  `bundleContracts`) and publishes it to this project's generic package
  registry — the "versioned contracts bundle per train" done-when item.

Only `ci/stages.gitlab-ci.yml` is included from `platform-build` (the stage
list, shared variables, pinned tool-image digests): this repository has no
Kotlin source, so `ci/build.gitlab-ci.yml`'s Gradle/Kotlin jobs do not apply,
and every job above is this repository's own.

Per-service Maven modules (`generatorSource("platform-contracts:<service>:openapi.yaml")`,
TPL-01) are a follow-up: the `gst.contract-server`/`gst.contract-client`
Gradle plugins that would resolve them are not built yet.
