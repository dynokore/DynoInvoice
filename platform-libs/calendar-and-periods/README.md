# calendar-and-periods

Fiscal and operational periods, business-day calendars, deadline derivation and period readiness gating.

**No Singapore filing rule is built in.** Period windows, return forms, due-date rules, weekend days, public holidays
and any pinned non-business-day rule all come from a calendar pack passed in as data (INV-REQ-87, INV-REQ-707).
Change the pack, not the library, to change a date. A filing frequency change is always cut at the change date: the
transitional period ends the day before it takes effect and the next period starts on the change date (INV-Q-5045 A,
INV-AC-154); the pack does not choose.

| | |
| --- | --- |
| Layer | L2 (SDS §18.9) |
| Embedded by | `tenancy-svc` (period records, extensions); `canonical-svc` (period assignment); `submission-svc` (due dates, readiness gating) |
| Part used | As listed, each with its own types (§18.10) |
| Uses | L1 modules only (SDS §18.8) |
| Wave | Wave 2: Foundation services (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).
