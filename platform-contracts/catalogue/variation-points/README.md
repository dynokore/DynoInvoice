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
3. If it has, re-run the extraction (the query in `variation-points.yaml`'s own
   header) and regenerate the file, in its own merge request naming what changed
   (AGT-11's pattern, mirrored from `error-codes.yaml`'s re-vendor procedure).
4. `gradle -p catalogue check` — `checkMustBeElectedAvailability` (INV-AC-2236) and
   `checkNonWaivableFloor` (INV-AC-1786) run against the re-vendored file, same as any
   other change to it.

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
