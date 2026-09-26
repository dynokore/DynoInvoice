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

**The pre-existing `api/`, `catalogue/`, `errors/`, `event-log/`, `events/`
and `lifecycle/` directories are unchanged** — they predate the OW1 decision
this task implements and are out of its declared scope. Migrating their
content into the relevant service directories (and `service-runtime/` for
what is genuinely shared) is a follow-up, named here rather than done
silently.

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
