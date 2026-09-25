# outbox-and-queue

Durable outbox, partition-keyed ordered queue, at-least-once delivery, idempotent consumption, dead-letter triage, replay by scope; the common event envelope of FR-K.3 and FR-M.11, stamped on every outbox write, with the per-document `sequence` (FR-AH.10); priority lanes with an independent rate limit per lane, so that reprocessing, backfill and replay never delay live or deadline-near work (FR-F.7, FR-C.8)

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every service with a data-plane schema — every service but `console-web` and `control-plane-svc` |
| Part used | Outbox, relay, inbox, envelope and priority lanes in all; the partition-ordered queue in `submission-svc` (FR-J.4); delivery retry, dead-letter and replay in `notification-svc` |
| Uses | tenancy-kernel |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).
