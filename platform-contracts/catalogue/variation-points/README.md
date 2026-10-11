# variation-points

`variation-points.yaml` (STK-61, CS-28): a vendored, read-only, pinned snapshot of the
Keel INV variation-point requirements — every `requirement` entity tagged `VP-01`
through `VP-78`, read from its own `io_contract` attribute. It is the
`catalogue/generator` module's sole input for the gst-catalogue `VariationPoint`
catalogue (INV-T-2852 / T8), never hand-edited. See the file's own header for the
extraction query, why there is no invoice-docs file to vendor from instead (unlike
`catalogue/errors/error-codes.yaml`, T7), and what each field means.

## Re-vendoring

Keel is the live source, and this file drifts from it whenever a variation point's
`io_contract` changes (a new option, a status change, a new VP entirely). To pick up
such a change:

1. `source ~/projects/invoice/tooling/k.sh` (read-only Keel access — the same setup
   CODER-BRIEF's own query mechanism uses).
2. `python3 catalogue/variation-points/check-drift.py` — reports whether the committed
   file has drifted, with a diff if so.
3. If it has, run `python3 catalogue/variation-points/check-drift.py --write`, which
   regenerates the file below its header from the same query, in its own merge
   request naming what changed (AGT-11's pattern, mirrored from `error-codes.yaml`'s
   re-vendor procedure). When a row gains its Keel `catalogue` fields, delete its
   marker from `pending-fields.yaml` in the same merge request.
4. `gradle -p catalogue check` — `checkMustBeElectedAvailability` (INV-AC-2236) and
   `checkNonWaivableFloor` (INV-AC-1786) run against the re-vendored file, same as any
   other change to it.

## Installation-parameter rows (INV-T-3377; INV-Q-3202, P57)

VP-79 and VP-80 (INV-REQ-3210, INV-REQ-3211) are the installation maxima of VP-57 and
VP-67, made their own rows. Keel writes `source_ids` only when an entity is created
(P57), so they carry no `VP-nn` source id. `check-drift.py` takes them by the mapping
Keel already publishes on the referencing row (`installation_parameter: {row, requirement}`)
and fails closed if referencers disagree, if the requirement's own source ids differ
from the derived id (once Keel gains them, the two must agree), if the derived id
collides with another row, or if the requirement is not published. The generator still
fails any `{row: VP-nn}` bound or parameter row that names a row missing from this file.
Tests: `python3 -m unittest discover -s catalogue/variation-points -p 'test_*.py'`.

## Catalogue fields and pending markers (INV-T-3000)

Each row's `catalogue` block is Keel's `io_contract.catalogue`, copied unedited:
scope levels (INV-REQ-1242 b1), option keys in catalogue order (TPL-10), strictness
per option (INV-Q-2936), typed values with bounds (INV-Q-2934), and
`multi_select`/`exclusive_keys` for set-valued rows. `checkVariationPointFields` (in
`check`) requires each row to have that block, or to be retired, or to be listed in
`pending-fields.yaml` with the open INV-Q question that holds it. That file is
repository-owned, never vendored, and never supplies a value. A marker on a row that
already has fields fails as stale.

## Not yet CI-wired

`check-drift.py` is not run by `.gitlab-ci.yml`, unlike T7's `catalogue-drift-check`
and T15's `lifecycle-states-drift-check`, which clone invoice-docs with
`gitlab-ci-token:$CI_JOB_TOKEN`. There is no equivalent mechanism yet for a CI job to
reach Keel's read-only database (a different piece of infrastructure — a database
credential, not a git remote). Named here as a follow-up rather than silently
assumed or worked around: until it exists, drift is caught by whoever next runs
`check-drift.py` with Keel access (an inv-coder or inv-reviewer agent, per the steps
above), not automatically on every pipeline.

## The two acceptance checks

Wired into `check` by `catalogue/generator`'s own `gst.catalogue-generator.gradle.kts`
(same file T7 already added, extended here — one plugin, one `check` wiring, for both
the `ErrorCode` and `VariationPoint` catalogues, STK-61's "one catalogue"):

- `checkMustBeElectedAvailability` (INV-AC-2236) — every variation point with
  `must_be_elected: true`, and every one of the requirements INV-AC-2236 itself names
  as closing a source open question (INV-REQ-1309 to INV-REQ-1315, INV-REQ-1323), is
  `available`. See `MustBeElectedAvailabilityChecker`'s own KDoc for why the
  open-question-closing set is a small named list, not inferred.
- `checkNonWaivableFloor` (INV-AC-1786) — only its checkable half: a `CONTROL` marked
  non-waivable (in full or in part) has no looser option, and a looser option of any
  other `CONTROL` needs a recorded acknowledgement, both read from the vendored
  catalogue's own text (INV-REQ-780 behaviour #1). The other half — no variation
  point offers an option that relaxes a row of the force-of-law table, INV-NOTE-2
  §26.3 — is not checked: that table lives only in an archived document CODER-BRIEF
  forbids this repository's coding agents from reading. See
  `NonWaivableFloorChecker`'s own KDoc.

## Typed constraints (INV-T-3528)

Since gst-catalogue 1.9.0 (1.8.0 only loads them) a row's `catalogue` block may carry typed constraint records
(Keel INV-CS-3604; INV-Q-3526, INV-Q-3610, INV-Q-3614, INV-Q-3618): `constraints` for
the row's own options, `cross_row` for records naming another row, `cross_option` for
the whole multi-select selection. Each record is `{id, kind: FORM | FACT | REQUIRES |
EXCLUDES | CHECK, options | selection, forms, fact {holder, name}, row / row_options /
at, rows / scope_level, check / inputs, rule, refused, source}`. The generator fails
on an unknown field, kind, form or scope level, and `check` fails on a record naming
an option key, row or row option that does not exist. The library exposes them as
`VariationPoint.constraints` (and `VariationPoint.allConstraints()`); `crossRow` and
`crossOption` keep the records' rule text for 1.x readers. VP-64 is untyped by
design (INV-Q-3616); VP-01 stays untyped while INV-Q-3612 is open.
