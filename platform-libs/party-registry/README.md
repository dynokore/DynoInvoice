# party-registry

Legal entities, identifiers and their schemes, addresses, provenance and validation hooks.

| | |
| --- | --- |
| Layer | L2 (SDS §18.9) |
| Embedded by | `masterdata-svc` |
| Part used | All |
| Uses | L1 modules only (SDS §18.8) |
| Wave | Wave 2: Foundation services (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

Legal entities, scheme-qualified identifiers with provenance, addresses, the supplier identity triple and the scheme
validation hooks (INV-MOD-2740). The model (`party-registry-api`) is pure: no I/O, no Spring, no jOOQ. The format of a
UEN, GSTN or Peppol participant ID is not fixed here: the owner supplies a `SchemeValidator`.
