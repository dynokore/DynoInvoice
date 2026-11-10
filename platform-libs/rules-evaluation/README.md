# rules-evaluation

—

| | |
| --- | --- |
| Layer | L3 (ARC-16) |
| Embedded by | `rules-svc` and `control-plane-svc` (INV-Q-8719) |
| Part used | Pure per-line coding through a `TenantMapping`, and the decoders of the pack contents JSON (slice 1 of 3) |
| Uses | rulepack-kernel, tenancy-kernel, gst-catalogue |
| Wave | Wave 2: Library extension |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

Evaluation is pure (INV-REQ-775): the same inputs and pack version give the same answer. The module imports no clock,
store, network or Spring type (`ArchitectureTest`). An internal tax code with no mapping entry is refused (RUL-001);
no default code is applied and no category code is returned for it.
