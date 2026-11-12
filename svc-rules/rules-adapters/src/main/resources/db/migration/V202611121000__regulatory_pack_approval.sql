-- V202611121000__regulatory_pack_approval.sql (INV-T-4975; INV-REQ-489, INV-REQ-1166): the approval of a STAGED
-- regulatory pack records the approving user ID (never a role) and the time.
alter table rules.pack_registry add column approved_by text null;
alter table rules.pack_registry add column approved_at timestamptz null;
alter table rules.pack_registry add constraint pack_registry_approved_by_text
    check (approved_by is null or approved_by !~ '^\s*$|[[:cntrl:]]');
-- A regulatory row in status APPROVED always names its approver. Rows that left APPROVED keep the column.
alter table rules.pack_registry add constraint pack_registry_regulatory_approved_by
    check (layer <> 'REGULATORY' or status <> 'APPROVED' or approved_by is not null);

-- The approval is written only by this function, written, owned and granted like rules.advance_platform_pack: static
-- SQL, SECURITY DEFINER, owned by the NOLOGIN rules_sys with a fixed search_path, EXECUTE revoked from PUBLIC and
-- granted to the runtime login. It moves STAGED to APPROVED and nothing else.
create function rules.approve_platform_pack(p_pack_type text, p_pack_version text)
    returns void
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
declare
    v_actor text := (select rules.bound_actor());
begin
    if (select rules.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'a platform pack is approved under the platform tenant only' using errcode = '42501';
    end if;
    if v_actor is null or v_actor ~ '^\s*$|[[:cntrl:]]' then
        raise exception 'the bound actor is not set: approving a platform pack records its actor' using errcode = '42501';
    end if;
    update rules.pack_registry set status = 'APPROVED', approved_by = v_actor, approved_at = clock_timestamp()
        where pack_type = p_pack_type and pack_version = p_pack_version
            and tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid and status = 'STAGED';
    if not found then
        raise exception 'no STAGED platform pack version to approve' using errcode = '02000';
    end if;
end
$$;
grant create on schema rules to rules_sys;
alter function rules.approve_platform_pack(text, text) owner to rules_sys;
revoke create on schema rules from rules_sys;
revoke execute on function rules.approve_platform_pack(text, text) from public;
grant execute on function rules.approve_platform_pack(text, text) to rules_runtime;
