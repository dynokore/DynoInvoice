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

**Pre-OW1 directories.** `api/`, `errors/`, `events/` and `lifecycle/` were
README-only stubs that predate the OW1 decision; they were removed in the
INV-T-2850 cleanup (their content already lives per service, in
`service-runtime/` for the shared error and event envelopes, and in
`submission/schemas/lifecycle-states.schema.json`). `event-log/` stays: it holds
the generated topic definitions (INV-T-2855). `catalogue/` is built by
INV-T-2851 (T7), see below.

## `gst-catalogue` (INV-T-2851, INV-T-2852)

`catalogue/` is its own, separate Gradle build (`catalogue/settings.gradle.kts`),
distinct from this repository's root build above — it is the one directory here
with real Kotlin source, so it needs the Kotlin toolchain, detekt and Spotless
the root build does not.

```
catalogue/
├── errors/error-codes.yaml    vendored, pinned-commit copy of invoice-docs'
│                              model/architecture/error-codes.yaml (STK-61) — the
│                              ErrorCode generator's sole input, never hand-edited;
│                              re-vendor it (with the new commit SHA in its header)
│                              to pick up a design change
├── variation-points/
│   ├── variation-points.yaml  vendored snapshot of the Keel INV variation-point
│   │                          requirements (STK-61, CS-28) — the VariationPoint
│   │                          generator's sole input, never hand-edited. No
│   │                          invoice-docs file to pin to here (unlike
│   │                          error-codes.yaml): see its own header for why
│   ├── check-drift.py         re-vendor procedure's drift check against live Keel
│   │                          (not CI-wired — see README.md there)
│   └── README.md
├── generator/                 an included build (pluginManagement, like
│                              platform-build's build-logic/ one level down):
│                              provides the gst.catalogue-generator plugin — parses
│                              both vendored files, emits ErrorCode.kt/
│                              VariationPoint.kt and their catalogue artefact JSON,
│                              and the four acceptance checks below. Not published:
│                              single in-repo consumer.
└── gst-catalogue/              the published module: `ErrorCode` (code, condition,
                               severity, disposition, owner — CS-13) and
                               `VariationPoint` (class, options, default, bounds,
                               setting role, scope, owner, status,
                               form-dependence — CS-28), both generated, never
                               hand-edited
```

Four Gradle tasks, wired into `check`:

- `checkCatalogueStability` (INV-AC-2170) — no error code changes its number or
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
- `checkMustBeElectedAvailability` (INV-AC-2236) — every must-be-elected variation
  point, and every one INV-AC-2236 itself names as closing a source open question
  (INV-REQ-1309 to INV-REQ-1315, INV-REQ-1323), is `available`.
- `checkNonWaivableFloor` (INV-AC-1786, checkable half only) — a `CONTROL` marked
  non-waivable has no looser option; a looser option of any other `CONTROL` needs a
  recorded acknowledgement, both read from the vendored catalogue's own text
  (INV-REQ-780 behaviour #1). The other half needs the force-of-law table of
  INV-NOTE-2 §26.3, an archived document this repository's coding agents do not
  read — not checked, named as a follow-up rather than guessed at (see
  `catalogue/variation-points/README.md`).

Built and published via `gradle -p catalogue <task>` (`catalogue-verify`,
`catalogue-publish` in `.gitlab-ci.yml`) rather than through
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
- `tool-self-tests` (check `breaking-change-self-test`) — proves the gate itself works, against the
  fixtures in `spectral/fixtures/`, independent of whether any real contract
  changed in a given pipeline.
- `oasdiff-breaking-gate` / `asyncapi-breaking-gate` — merge-request pipelines
  only: fail on a breaking change to any file that already existed at the
  merge request's base commit (API-03, API-41). A new payload schema file is
  checked against API-41 (additionalProperties never false); the unchanged-rule
  comparison applies only to files present at the base.
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

## Caller token kind `x-caller-auth` (INV-T-8269)

`x-caller-auth` is required on every operation, with one of the values `end-user`, `signed-link` and `service-job` (INV-REQ-1202, owner answer INV-Q-8201 A, criterion INV-AC-8259). `end-user` is the value of an operation whose caller holds a verified end-user token. A value other than `end-user` is set only by a platform-contracts change that passes contract review. The merge request of that change names the operation, the verification the operation keeps instead of the end-user check (the signed link by its passcode and attempt rules in access-svc; the job by its workload token and the caller check of the service) and who verifies it. The Spectral rule `inv-req-1202-x-caller-auth` refuses an operation without the key.

## Dependency locking and verification (INV-T-4299)

STK-72 applies (coordinator rulings INV-Q-4322 and INV-Q-4327). This repository has three Gradle builds: the root packaging build, `catalogue/` (with its included build `catalogue/generator/`) and `codegen-self-test/`. In each one, every resolvable configuration is locked in strict mode:

- the settings classpath in `settings-gradle.lockfile`. Gradle writes an included build's settings lock state to the including build's `settings-gradle.lockfile`, so two builds name their own file: `catalogue/generator/settings-gradle.lockfile` (otherwise written over `catalogue/settings-gradle.lockfile`) and `codegen-self-test/gradle/settings-gradle.lockfile`. `codegen-self-test/settings-gradle.lockfile` holds the included platform-build `build-logic`'s settings lock state, which Gradle writes there;
- each build script's plugin classpath in `buildscript-gradle.lockfile` next to it;
- each real module's configurations in its `gradle.lockfile`;
- each generated `codegen-self-test` module in `codegen-self-test/gradle/locks/<module>.lockfile`, because the modules live under the ignored `build/`. A new contract artefact without a lock file fails the build with a hint, and so does a lock file without a module. The generated modules get their plugins from `codegen-self-test/build.gradle.kts` and may not resolve a plugin classpath of their own.

Settings, build script and project locking all run in strict mode, so a missing lock file or entry fails the build, as does a resolution outside the lock files. The staged same-commit `platform-contracts:*` coordinates are left out of locking and verification: they are built from this commit's files into `codegen-self-test/build/contracts-repo` with version `+` and never downloaded, so there is no remote artefact to pin or verify. Each generated module routes the `platform-contracts` group to that staging repository only (`exclusiveContent`), so a remote artefact of the group can never resolve. Every external artefact stays locked and verified.

Every artefact a build downloads, plugins, POMs and module metadata included, is verified against that build's `gradle/verification-metadata.xml` (`gradle/`, `catalogue/gradle/`, `codegen-self-test/gradle/`) in strict mode, so an artefact without an entry fails the build. Signatures are checked against the committed `verification-keyring.keys` and `.gpg` next to the metadata (the root build resolves nothing, so it has no keyring). Key servers are off because CI has no key-server egress. A key that could not be downloaded is an `ignored-key`, and its artefact is checked by SHA-256, as are unsigned artefacts.

When a dependency or plugin changes:

1. Use an empty Gradle user home (for example `GRADLE_USER_HOME=$(mktemp -d)`). With a warm cache, artefacts already cached get no entry and CI fails.
2. Set `<key-servers enabled="false"/>` to `true` in the build's metadata, so Gradle can download new keys.
3. Run the build's CI tasks together with `resolveAndLockAll`, writing locks, metadata and keys in one pass:
   - root: `gradle resolveAndLockAll printTrainVersion bundleContracts --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache`
   - catalogue: `gradle -p catalogue resolveAndLockAll compileKotlin compileTestKotlin check generatePomFileForMavenPublication generateMetadataFileForMavenPublication --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache --rerun-tasks`
   - codegen-self-test (with `PLATFORM_BUILD_DIR` at the CI job's `PLATFORM_BUILD_REF`): `gradle -p codegen-self-test resolveAndLockAll compileAllContracts --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache --rerun-tasks`
4. Set key servers back to `enabled="false"` and keep the comments in the `configuration` block.
5. Plugin marker POMs differ between the Gradle Plugin Portal and Maven Central. Keep both checksums (`also-trust`), each checked against the published `.sha1` of its repository.
6. Check every SHA-256-only entry against the artefact's published checksum before committing.
