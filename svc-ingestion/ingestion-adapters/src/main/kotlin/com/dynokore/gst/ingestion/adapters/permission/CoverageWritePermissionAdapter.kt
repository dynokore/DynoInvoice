package com.dynokore.gst.ingestion.adapters.permission

import com.dynokore.gst.ingestion.application.permission.EntityPermission
import com.dynokore.gst.ingestion.application.triggers.CoverageOperation
import com.dynokore.gst.ingestion.application.triggers.CoverageWritePermission
import java.util.UUID

/**
 * [CoverageWritePermission] over [EntityPermission] (INV-Q-10027): one scoped permission key per operation, asked for
 * the acting user and the entity of the path, never a role. Mapping and the review waiver ask the entity registry key;
 * covering a class by another solution is the setting of VP-61 (INV-REQ-1322) and asks its own key. The `when` is total
 * over [CoverageOperation], so a new operation does not compile until it names its key.
 */
class CoverageWritePermissionAdapter(
    private val permission: EntityPermission,
) : CoverageWritePermission {
    override fun permits(
        actorUserId: UUID,
        operation: CoverageOperation,
        entityRef: String,
    ): Boolean {
        val key =
            when (operation) {
                CoverageOperation.MAP -> ENTITY_CONFIG_MANAGE
                CoverageOperation.WAIVE -> ENTITY_CONFIG_MANAGE
                CoverageOperation.COVER_ELSEWHERE -> VP_61_SET
            }
        return permission.holds(actorUserId.toString(), entityRef, key)
    }

    companion object {
        const val ENTITY_CONFIG_MANAGE = "entity-config.manage"
        const val VP_61_SET = "vp-61.set"
    }
}
