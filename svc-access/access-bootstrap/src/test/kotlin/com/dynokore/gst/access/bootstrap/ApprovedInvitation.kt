package com.dynokore.gst.access.bootstrap

import java.util.UUID

/**
 * Test data: a diagnostic invitation of [identity] for [tenant] already approved by a security officer and valid for an
 * hour (INV-T-4941). A deployment operator is answered only inside such a record (INV-REQ-888 behaviour 2), so the
 * tests of the operator's own permission cells seed one and assert the cell itself.
 */
internal fun seedApprovedInvitation(
    tenant: Any,
    identity: String,
) = BoundaryFixture.asSuperuser { c ->
    c.createStatement().use {
        it.execute(
            "insert into access.elevation (id, tenant_id, elevation_ref, kind, subject_ref, requester_ref, case_id, " +
                "status, valid_from, valid_until, decided_by, created_at, created_by, updated_at, updated_by, " +
                "version) values (gen_random_uuid(), '$tenant', 'DIAG-${UUID.randomUUID().toString().take(8)}', " +
                "'DIAGNOSTIC_INVITATION', 'INST-1', '$identity', 'CASE-SEED', 'GRANTED', " +
                "now() - interval '1 minute', now() + interval '1 hour', 'IDN-OFFICER', now(), 'seed', now(), " +
                "'seed', 1)",
        )
    }
}
