# document-lifecycle

Versioned document, the state model as data, supersession rules and the in-flight guard (part 1). Amendment
classification (part 2) is not in this release.

The library carries no state code, return form or tax rule: each embedding service supplies its `StateModel`
(states, `inFlight` and `countsAsOpen` flags, allowed transitions, initial state) and `SupersessionRules`. It holds no
persistence, clock or event type; claim and timer behaviour belong to the service.

| | |
| --- | --- |
| Layer | L2 (SDS §18.9) |
| Embedded by | `canonical-svc` (Doc UUID, versions, supersession); `payload-svc` (amendment classification, §33.3); `submission-svc` (submission state machine, obligation register) |
| Part used | As listed, each with its own types (§18.10) |
| Uses | L1 modules only (SDS §18.8) |
| Wave | Wave 3: Document pipeline (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).
