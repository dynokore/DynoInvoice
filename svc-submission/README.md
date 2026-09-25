# svc-submission

`submission-svc` — The obligation and its transmission — which documents IRAS must receive, by when, and whether it has

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.7](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 3: Document pipeline (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Stateful, partitioned by Doc UUID |

## Owns

submission, submission_attempt and queue_item in one schema (FR-Z.7), with the payload bytes of each queued version held with its queue item (AD-30); the obligation register; readiness records; filing confirmations

## Provides

Readiness, drain, confirmFiling, withdraw and replay commands; submission-list and status queries — to console-bff, and through the gateway to the filing-workflow and reporting principals

## Capabilities

The obligation register (§33.2). Routing to Type 1A, 1B, 2 or 3. For self-billing it routes to the type the scenario pack's party-to-type assignment decides in rules-svc (§28.4, FR-T.2); payload-svc builds the payload for that type (§6.6; clarified 25 Sep 2026). Due dates from the period record, the actual filing date and recorded extensions, stored as a snapshot on the submission record and recomputed only on the triggers of §7.7. Version states of the two-level lifecycle, including AWAITING_PRECEDING, SENT_UNCONFIRMED, REJECTED, DEAD_LETTERED and WITHDRAWN. State and queue position in one transaction (FR-J.4). Ordering per Doc UUID (FR-C.2). Batching. Applying amendment outcomes: resubmit, hold, withdraw (§33.3). Despatch of Relay-mode network issuance (§33.1). Readiness and drain; confirmFiling; expediting late documents with SUB-010. Submission list and tracking reports (§35.1). The submission-boundary query for bulkResolve (FR-AC.3). Replay, on its own priority lane (FR-C.8). Holding backfilled documents until the backfill is approved by tax.approver, and releasing them on approval (FR-R.9). Period readiness includes the completeness inputs FeedHealthChanged and AggregateWindowChanged (FR-A.5) and shows DEP-006 from InstallationStatusChanged (FR-AG.48)

## Events published

- ReadinessChanged
- SubmissionAcknowledged
- SubmissionDespatched
- SubmissionFailed

## Events consumed

- AcknowledgementReceived
- AggregateWindowChanged
- CanonicalVersionCreated
- DocumentHeld
- DocumentReleased
- FeedHealthChanged
- GstReturnRecorded
- InstallationStatusChanged
- PayloadReady
- PayloadRejected
- PeriodDueDateChanged
- PeriodExtended
- PeriodReassigned

## Synchronous calls

- transport-svc

## External dependencies

None

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log` |
| L2 | `calendar-and-periods`, `document-lifecycle` |
| L3 | `product-runtime`, `gst-catalogue`, four submission types |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Split state and queue position across transactions (FR-J.4)
- allow two in-flight versions of one Doc UUID (FR-C.2)
- mix document types in a batch
- truncate an oversized document
- extend a deadline because the return was filed late (FR-6.4)
- change a due date ad hoc or by hand — it recomputes only on the triggers of §7.7 (FR-6.13)
- advance to ACKNOWLEDGED on AP success alone (FR-E.3)
- report readiness without draining (FR-C.8)
- silently delay a document to preserve credit-note sequence (FR-AA.10)
- build or classify payloads

## Degradation

Transport failure queues and retries; trading is unaffected (FR-8.16). Deadline logic never self-extends (FR-8.18)
