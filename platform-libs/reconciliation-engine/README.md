# reconciliation-engine

Two-sided comparison by an opaque key, a difference taxonomy supplied by the host as data, explained versus
unexplained variance and the work-paper model with a plain-value export (`toRows()`).

The library carries no category, tolerance, form type or tax rule: each embedding service supplies its categories and
`ClassificationRule`s. A variance no rule explains stays in the unexplained residual (`hasUnexplained`); raising an
alert for it is the service's. It holds no persistence, clock, file or event type.

| | |
| --- | --- |
| Layer | L2 (SDS §18.9) |
| Embedded by | `reconciliation-svc` |
| Part used | All |
| Uses | L1 modules only (SDS §18.8) |
| Wave | Wave 4: Evidence, reconciliation, notification and console (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).
