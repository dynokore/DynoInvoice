# catalogue

`gst-catalogue` (STK-61): a separate Gradle build from this repository's root (see
the root `README.md`'s "gst-catalogue (INV-T-2851)" section for the full layout,
the four acceptance checks and how CI builds and publishes it).

`ErrorCode` — code, condition, severity, disposition, owner — is generated (INV-T-2851
/ T7) from `errors/error-codes.yaml` (vendored, pinned-commit copy of invoice-docs'
`model/architecture/error-codes.yaml`), never edited by hand.

`VariationPoint` — class, options, default (or must-be-elected), bounds, setting
role, scope, owner, status, form-dependence — is generated (INV-T-2852 / T8) from
`variation-points/variation-points.yaml` (vendored from the Keel INV variation-point
requirements — see that file's own header for why, unlike `error-codes.yaml`, there
is no invoice-docs file to vendor from instead), never edited by hand. See
`variation-points/README.md` for the re-vendor procedure and its drift-check tool.

## Versioning

`gradle.properties` is the one place the version of `gst-catalogue` is declared (`gstCatalogueVersion`).
Removal or rename of a published field or key of the catalogue (an `ErrorCode` member, a `VariationPoint` member, a field of a generated JSON artefact, a permission key) is a major step of `gstCatalogueVersion`.
An additive change is a minor step of `gstCatalogueVersion`.
