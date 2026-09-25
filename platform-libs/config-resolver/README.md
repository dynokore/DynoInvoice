# config-resolver

Precedence-ordered configuration resolution with effective dates and resolved-value recording

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every back-end service, including `control-plane-svc` (VP-45) |
| Part used | Resolution, setting and history, bounds |
| Uses | audit-log, outbox-and-queue (where present) |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).
