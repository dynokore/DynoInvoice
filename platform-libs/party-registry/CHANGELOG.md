# Changelog

All notable changes to `party-registry` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-10-16
### Added
- Model: `LegalEntity`, `Identifier` (scheme, value, provenance), `Address`, and the supplier identity triple
  (`SenderId`, `SupplierId`, `SupplierGstn` in `SupplierIdentity`) as separate value types; `SubmittingIdentity`
  shares no type with `SupplierIdentity` (INV-REQ-803, INV-REQ-310).
- Provenance on every identifier: `providedBy` in INTERNAL, COUNTERPARTY, REGISTER, MIGRATED, an evidence reference and
  a capture time, none nullable; MIGRATED only through `Provenance.migrated` (INV-REQ-314, INV-REQ-1111).
- `SchemeValidator`, the injectable format hook for UEN, GSTN and Peppol participant ID; `PartyField` and
  `checkNaPopulation` accept `NA` only as a whole and return MDM-004 for a partial population (INV-REQ-313).
- `PartyChecks` (impl half) composing the NA rule and the hook; a UEN format failure is MDM-003.
