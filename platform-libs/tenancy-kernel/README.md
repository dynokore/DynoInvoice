# tenancy-kernel

Tenant context for each request and each message; row-level security with the session tenant set per request and cleared on return to the pool; the mandatory `tenant_id` column; isolation test support (§9.2, §13.3). The hierarchy itself is product data, read through the structure replica that `product-runtime` configures (§18.11). Entitlement is checked through the `LicenceCheck` binding (§12.1) and never blocks (FR-G.12)

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | The same services as `outbox-and-queue` |
| Part used | Tenant context, row-level security, the mandatory `tenant_id` |
| Uses | None |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## Transaction binding

`TransactionRunnerImpl` binds the tenant and the actor as the first statement of every transaction, both
transaction-local (`set_config(..., true)`). An optional `ConnectionLabel` (third constructor argument, since 1.4.0;
INV-T-3982, coordinator ruling INV-Q-4487) adds `set_config('application_name', ?, true)` to that same statement with
the name it gives for the transaction's connection, so pg_stat_activity shows it while the transaction runs and it ends
at commit or rollback. service-runtime passes one that names the pool lane (`<gst.service>:<lane>`). Without a label,
or when it gives `null`, the statement is unchanged.

## Signed binding

Hardening on the transaction binding (INV-REQ-854 b7, owner ruling INV-Q-4121; INV-T-4156, slice 1 INV-T-5358).
`TENANT_PREDICATE` is now `tenant_id = (select <schema>.bound_tenant())` (INV-T-4156 last slice). Slice 2 (INV-T-5359): `TransactionRunnerImpl(..., keys:
BindingKeySource, transactionTimeout = 30 s, clock)` signs with `BindingSignature` and sets
`app.binding_sig` in the same first statement as the tenant and the actor, as a bind parameter only; the expiry is the
transaction start plus `transactionTimeout`, rounded down to whole seconds. `keys` has no default: a runner built without a key is refused at construction (INV-T-4156). The
testFixtures helper is `SignedBinding`.

`BindingKeyMigration.ddl(schema, sysRole, runtimeRole, pgcryptoSchema = "public")` generates, for one schema, to run
as `<svc>_sys` (the first block refuses any other login and a missing pgcrypto):

- `<schema>.binding_key(slot, secret)`: slot `current` and slot `previous`, no privilege for PUBLIC, so only `<svc>_sys`
  reads it. `BindingKeyMigration.writeKeys(dsl, schema, BindingKeySource)` is the migration runner's write (a jOOQ
  upsert; a missing or empty current key throws and writes nothing; no previous key deletes that slot).
- `<schema>.bound_tenant()` (uuid) and `<schema>.bound_actor()` (text): STABLE `SECURITY DEFINER`, owned by `<svc>_sys`,
  `search_path = pg_catalog, <schema>, pg_temp` (INV-Q-4038), pgcrypto called by its qualified name, EXECUTE revoked from PUBLIC and granted
  to the runtime login only. They return NULL, never an error, for a missing, empty, malformed, forged or expired value.
  They read `app.binding_sig` only, never `app.tenant_id` or `app.actor`.
  `bound_tenant()` is one plpgsql function that checks the MAC before anything else and has no exception block, no loop and no call to another function; both functions return NULL for an actor part that does not decode to well-formed UTF-8 without a NUL byte.

`app.binding_sig` is one text value of four fields separated by `.`:

    <tenant>.<actor>.<expiry>.<mac>

| Field | Form |
| --- | --- |
| `tenant` | the tenant UUID, lower-case, 36 characters |
| `actor` | the actor in UTF-8, base64url (RFC 4648 section 5) without padding, so it never contains `.` |
| `expiry` | seconds since the Unix epoch, decimal, 1 to 12 digits; the value is expired when it is not after `now()` |
| `mac` | lower-case hex (64 characters) of HMAC-SHA256 over the exact text `<tenant>.<actor>.<expiry>` (the first three fields as transmitted) with the installation binding key |

The MAC is checked against the `current` key and then the `previous` key, so a rotation keeps values signed with the
previous key valid and a key older than that is refused. A value is read once per statement by a policy through
`(select bound_tenant())`, never per row.

### The entity set (INV-T-7520; INV-Q-7453 (B); INV-REQ-866, INV-AC-1912)

The value carries, optionally, the entity set of the acting principal as a fifth field, signed by the same key with the
tenant, the actor and the expiry:

    <tenant>.<actor>.<expiry>.<entities>.<mac>

| Field | Form |
| --- | --- |
| `entities` | `-` for the empty set; otherwise the entity UUIDs, each lower-case canonical (36 characters), in ascending order of their text, each once, joined by `,` |
| `mac` | as above, over the exact text `<tenant>.<actor>.<expiry>.<entities>` (the first four fields as transmitted) |

- **Told apart by the number of fields.** Four fields (`<tenant>.<actor>.<expiry>.<mac>`) is the form without a set
  and keeps verifying as before for `bound_tenant()` and `bound_actor()`; it carries no entity set, so
  `bound_entities()` is NULL for it. Five fields carry a set, which may be empty. Any other count is refused.
- **Order and de-duplication.** `BindingSignature.sign` sorts the set by the text of its UUIDs and signs each once, so
  one set has one text. The database does not re-sort: it checks the MAC over the text as sent and the shape of the
  field, so a set that is changed, added to or removed from after signing fails the MAC.
- **Shape of the field.** `-`, or one or more lower-case canonical UUIDs separated by single `,`. An upper-case UUID,
  an empty element, a repeated `,` or any other character makes the whole value refused (NULL from all three
  functions), even when the MAC is right.
- **The empty set** is the field `-` with a valid MAC: `bound_entities()` returns an empty array and the entity
  predicate matches no row. A value with no set returns NULL, which also matches no row.
- `BindingSignature` stays the only signing code. `TransactionRunnerImpl(..., entities: BoundEntitySource? = null)`
  signs the five-field form when a source is given (it answers for the tenant and actor of the transaction; the empty set
  is a set) and the four-field form when there is none. service-runtime supplies the source from its authority
  replica; no application code sets the set.

`<schema>.bound_entities()` (uuid[]) is created beside the other two, with the same owner, `search_path`, revoked
PUBLIC and EXECUTE for the runtime login only. It returns NULL, never an error, for a missing, empty, malformed,
forged or expired value and for a value with no set, and reads `app.binding_sig` only.
`TenantRlsMigration.ddl(schema, table, policyName, entityColumn = null)` adds, when `entityColumn` is given (a plain
lower-case identifier), `AND <entityColumn> = ANY ((select <schema>.bound_entities())::uuid[])` to the USING and WITH CHECK
of the policy; with the default the text is unchanged. A NULL set matches no row. This module applies the clause to no service table.
