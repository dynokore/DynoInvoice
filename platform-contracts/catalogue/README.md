# catalogue

`gst-catalogue` (STK-61): a separate Gradle build from this repository's root (see
the root `README.md`'s "gst-catalogue (INV-T-2851)" section for the full layout,
the two acceptance checks and how CI builds and publishes it).

`ErrorCode` — code, condition, severity, disposition, owner — is generated (INV-T-2851
/ T7) from `errors/error-codes.yaml` (vendored, pinned-commit copy of invoice-docs'
`model/architecture/error-codes.yaml`), never edited by hand. `VariationPoint`,
generated from the Keel INV variation-point requirements, is not built yet — a
follow-up Keel task, named here rather than silently assumed.
