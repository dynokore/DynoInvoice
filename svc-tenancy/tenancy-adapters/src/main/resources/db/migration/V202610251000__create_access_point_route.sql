-- V202610251000: the replica of AccessPointRouteChanged (INV-REQ-1286, INV-REQ-1310; owner rulings INV-Q-4872 A and
-- INV-Q-4993 B). One row per Access Point connection of a tenant: the entity refs assigned to it (the whole list,
-- replaced by every event), the elected acknowledgement route or none, and the per-connection version. transport-svc
-- publishes the event and holds the assignment; tenancy-svc only reads this replica. Forced RLS on the signed binding.
SET lock_timeout = '5s';

CREATE TABLE tenancy.access_point_route (
    tenant_id      uuid        NOT NULL,
    connection_ref text        NOT NULL CHECK (length(connection_ref) BETWEEN 1 AND 40),
    entity_refs    text[]      NOT NULL,
    route          text        CHECK (route IN ('AP_CALLBACK', 'POLLING', 'REPORT_IMPORT')),
    version        bigint      NOT NULL CHECK (version >= 1),
    recorded_at    timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, connection_ref)
);

CREATE INDEX access_point_route_entities ON tenancy.access_point_route USING gin (entity_refs);

ALTER TABLE tenancy.access_point_route ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.access_point_route FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.access_point_route
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON tenancy.access_point_route TO tenancy_runtime;
