# svc-notification

`notification-svc` — Telling people and systems what happened — alerts, messages and webhooks

**Status:** service skeleton (INV-T-5972): five modules, schema `notification`, the contract gates and the webhook subscription scope decision `matches`. Every contract operation still answers 501 (`operations.tsv`). This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.17](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 4: Evidence, reconciliation, notification and console (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Bursty at deadlines |

## Owns

Alert policies; alert instances and escalation state; message templates; delivery records; webhook subscriptions, with secret references; webhook delivery log

## Provides

Policy, subscription and webhook commands, and delivery-status queries, to console-bff

## Capabilities

Alert policies per entity and class, with escalation tiers (§33.13). Default routing by role (§34.14). Acknowledgement and bounded snooze, never beyond the due date minus the safety margin; deadline and coverage alerts cannot be disabled. Message delivery through the MessageDelivery binding, by e-mail and SMS, with templates, delivery tracking and bounce escalation (§35.6); passcode messages (FR-AG.70). Customer webhooks: subscriptions, signing, retries, dead-letter, replay, secret rotation (§35.6)

## Events published

None

## Events consumed

- AccessAlertRaised
- AuthorityChanged
- ConnectionStatusChanged
- DocumentHeld
- EvidenceIntegrityFailed
- FeedHealthChanged
- InstallationStatusChanged
- JobRunRecorded
- MessageRequested
- OrganisationStructureChanged
- PackCurrencyChanged
- PayloadRejected
- ReadinessChanged
- ReconciliationCompleted
- RulePackActivated
- RulePackStaged
- SubmissionAcknowledged
- SubmissionFailed

## Synchronous calls

None

## External dependencies

Mail and SMS providers (MessageDelivery); customer webhook endpoints

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `rulepack-kernel`, `adapter-runtime` |
| L2 | None |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Decide any compliance state — it reports what others decided
- put invoice content in a message or webhook body
- allow deadline or coverage alerts to be disabled (FR-AE.68)
- sit on the critical path of submission

## Degradation

Unavailable: alerts and webhooks wait in the producers' outboxes and are delivered on recovery. Readiness and the console still show the state. The outbound heartbeat of FR-H.20 belongs to the platform component observability-bundle (§18.14), not to this service, so a stopped System is still detected

## Gates

- `operations.tsv` lists every operationId and channel of the contract with the task that implements it (an INV-T key or `UNASSIGNED`); `done-tasks.txt` holds the keys of the done tasks, one per line.
- `ac-keys.tsv` lists the acceptance criteria of this service's tasks; the Gradle check `acKeyCoverage` fails closed (README of svc-submission, same gate).

## Dependency locking and verification

Refresh after a dependency change, with an empty Gradle user home (see platform-libs README, "Dependency locking and verification"):

```sh
gradle resolveAndLockAll check bootJar -x test -x acKeyCoverage --continue \
  --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
```

Never pass `--dependency-verification off`; CI fails a build that does.
