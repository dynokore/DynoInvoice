# operating-profiles

`operating-profiles.yaml` (STK-61, INV-Q-3233): a vendored, read-only snapshot of the
Keel INV operating-profile rows PRF-1 to PRF-7 (INV-REQ-1252 to INV-REQ-1258), read from
each row's `attrs.io_contract.profile` (version, status, typed values; rulings
INV-Q-3283 A and INV-Q-3285 A). It is `catalogue/generator`'s sole input for the
gst-catalogue `OperatingProfile` catalogue (INV-T-3277), never hand-edited. Its header
records the extraction query; each row pins its Keel `entity_revision` id.

## What the generator checks (`generateOperatingProfileCatalogue`, before compile)

- Each value names a variation point of `../variation-points/variation-points.yaml` that
  is not retired and has the same requirement, and every option key it uses (`option`,
  `options`, `otherwise_option`, `alternatives`, `needs_acknowledgement`) is an option of
  that row (`OperatingProfileChecker`).
- A typed value matches its option's kind and unit and lies within its numeric bounds;
  `adds` codes for an option that takes error codes exist in `../errors/error-codes.yaml`
  with the severity the option's filter names; every LOOSER option a value can select is
  flagged `needs_acknowledgement`, and only LOOSER ones are; `explicit_default` is on the
  DEFAULT option.
- A `condition_fact` {constraint, holder, name} (PRF-2's VP-01, INV-T-3636, INV-Q-3692) names a
  FACT record of that row with the same fact, on a value with a `condition`; it is exposed as
  `ProfileValue.conditionFact`.
- The version rule (INV-Q-3283 A′, `ProfileVersionChecker`): against
  `previous-snapshot.yaml`, a profile's values may change only with a higher version, a
  version never decreases, and a profile is never dropped. Prose (`text`), `status` and
  `condition_fact` (it types the `condition` already there) are not values. With no previous snapshot (the first one) the check is skipped.

The generated `OperatingProfile` marks the planned variation points each profile sets
(`plannedVariationPoints`, INV-REQ-1251), carries `previousVersion`/`previousValues` and
`changesFromPrevious`, and `OperatingProfiles.differences` gives the per-profile
difference shown when an update is offered (INV-REQ-1245 b4, INV-AC-2231).

## Untyped values

A value Keel marks `typed: false` (none today; PRF-7 VP-08 was until INV-Q-3320 typed it) is
carried as `unresolved` with Keel's reason and the open question from
`pending-values.yaml` (INV-Q-3320), never as an invented option. The generator fails if
an untyped value has no marker, or a marker is stale.

## Re-vendoring

1. `source ~/projects/invoice/tooling/k.sh` (read-only Keel access).
2. `python3 catalogue/operating-profiles/vendor-profiles.py` reports drift with a diff.
3. If it has drifted, run it with `--write` in its own merge request naming what
   changed (AGT-11). It saves the replaced data as `previous-snapshot.yaml`, then
   rewrites the snapshot. When Keel types a pending value, delete its marker.
4. `gradle -p catalogue check`.

Not CI-wired, for the same reason as `../variation-points/check-drift.py`.
