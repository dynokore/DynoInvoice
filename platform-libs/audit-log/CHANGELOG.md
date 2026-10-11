# Changelog

All notable changes to `audit-log` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [2.0.0] - 2026-10-11
### Changed
- BREAKING: `OverridePolicy` no longer takes `additionalRequiredRoles`; an override needs one
  `tax.approver` per document version (VP-32 default, INV-REQ-1293; INV-Q-2972). The
  "Keel controls are tighten-only" comment is corrected to INV-REQ-1244's rule.

## [1.0.0] - 2026-10-11
### Added
- `AuditWriter`: actor, action, scope, reason and prior state written as `AuditEntryRecorded`
  through the service's outbox in the command's transaction (INV-REQ-861, -1226, -2588).
- `AuditedOperations`: a state change commits with its entry, a read returns only after its
  entry is written; a failed entry commits nothing and returns no result (INV-AC-2593).
- Override path: `OverrideService`, `OverrideRegister`, `ErrorCatalogue`. TERMINAL (and MANUAL
  or unknown) codes are refused before any write; OVERRIDABLE needs a reason and the
  `tax.approver` role (a policy may only add requirements, never widen), is scoped to one document version, and commits with its audit entry
  or leaves nothing (INV-REQ-1179, -1180; INV-AC-2166, -2209).
- `AuditSchema.ddl`: the `override_grant` table with `tenant_id`, forced row-level security,
  select and insert only.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
