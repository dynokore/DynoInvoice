package com.dynokore.gst.rules.domain.pack

/**
 * The activation state of a pack version held in the registry (INV-REQ-1166 lifecycle, INV-REQ-1196).
 *
 * A mapping pack goes DRAFT, SUBMITTED, APPROVED, ARMED (shown as ACTIVE once effective); a regulatory pack goes
 * STAGED, ACKNOWLEDGED, ARMED. [frozen] states never change again (INV-REQ-773): content, effective dates and
 * signature are fixed once armed, and there is no way back.
 */
enum class PackStatus(
    val frozen: Boolean,
) {
    DRAFT(false),
    SUBMITTED(false),
    APPROVED(false),
    STAGED(false),
    ACKNOWLEDGED(false),
    ARMED(true),
    SUPERSEDED(true),
    ABORTED(true),
}
