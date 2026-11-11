INV-T-17802. Requirement: INV-REQ-1202. Criterion: INV-AC-17807 (contract part). No other requirement found by search of Keel for the key-feed `use`.

## Done
- Schema: `use` enum gains `LINK_SESSION` (descriptions: link-session tokens only; no `use` still read as OPERATOR_SESSION); `keys.maxItems` 6 with description (three uses, current and next).
- asyncapi.yaml message text names the third use and one signing key per use.
- Samples: valid `operator-session-keys-changed-link-session.json` (one item of each use); `invalid/...-five-items.json` deleted (five items are valid at maxItems 6, so it would fail validation); `invalid/...-seven-items.json` added; invalid `use` samples `link-session` (hyphen file), `link_session` (lower file), `LINK-SESSION` (underscore file). No sample edited in place. `examples/schema.txt` is unchanged: its glob `operator-session-keys-changed-*.json` already covers the new files.
- No lock file regenerated (none forced).
- Scope check, `git diff --name-only origin/main` (rename detection folds the deleted five-items file into seven-items):
```
service-runtime/asyncapi.yaml
service-runtime/examples/invalid/operator-session-keys-changed-seven-items.json
service-runtime/examples/invalid/operator-session-keys-changed-use-link-session-hyphen.json
service-runtime/examples/invalid/operator-session-keys-changed-use-link-session-lower.json
service-runtime/examples/invalid/operator-session-keys-changed-use-link-session-underscore.json
service-runtime/examples/valid/operator-session-keys-changed-link-session.json
service-runtime/schemas/operator-session-keys-changed-payload.schema.json
```
- prepush: `prepush: ALL OK` (gradle codegen-self-test + service-runtime, spectral, asyncapi-validate, schema-sample-validation).

## GAP (not done): required-identifiers row
The mandated row `OperatorSessionKeysChanged.keys.use.LINK_SESSION` (schema_enum_value) fails the checker:
```
service-runtime/required-identifiers.tsv:134: 'OperatorSessionKeysChanged.keys.use.LINK_SESSION' (schema_enum_value, schemas/operator-session-keys-changed-payload.schema.json, INV-REQ-1202): not found as a schema_enum_value (whole-token, case-sensitive match)
```
`schema_enum_value` needs `<Schema>.<property>.<MEMBER>` under `components.schemas`; the payload schema file is a standalone JSON Schema with no `components.schemas`, and the 4-part name splits to MEMBER `use.LINK_SESSION`. The row is therefore omitted. Options: (A) row `LINK_SESSION	enum_value	schemas/operator-session-keys-changed-payload.schema.json	INV-REQ-1202`, same as the existing OPERATOR_SESSION and MACHINE_ACCESS rows (probes of delete/rename work); (B) extend check-required-identifiers.py for standalone schemas (outside this scope).

Not run: look-alike scope probes and the enum probes (depend on the row).
remaining independent work: none

Exact scope: as listed above plus deleted `service-runtime/examples/invalid/operator-session-keys-changed-five-items.json`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
