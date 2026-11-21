package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.AccessReviewEnv.Companion.ADMIN
import com.dynokore.gst.access.bootstrap.AccessReviewEnv.Companion.OFFICER_1
import com.dynokore.gst.access.bootstrap.AccessReviewEnv.Companion.OFFICER_2
import com.dynokore.gst.access.bootstrap.AccessReviewEnv.Companion.VIEWERS
import com.dynokore.gst.access.contract.model.AccessReviewResponse.Status
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-T-15798 (INV-REQ-894, INV-REQ-1266; INV-FLOW-253): `runAccessReview` and `closeAccessReview` over a real
 * PostgreSQL, the real permission matrix of the gst-catalogue and the real variation point catalogue of the pin.
 */
class AccessReviewTest {
    private fun run(block: (AccessReviewEnv) -> Unit) =
        AccessReviewEnv.run { env -> env.seedStandard().also { block(env) } }

    private fun AccessReviewEnv.outcomes(
        ref: String,
        actor: String,
        grants: Collection<String>,
        outcome: String = "CONFIRMED",
    ) = close(ref, actor, *grants.map { it to outcome }.toTypedArray())

    // ---- INV-AC-2249: quarterly by default, a monthly label refused while VP-05 is planned ------------------------

    @Test
    fun `INV-AC-2249 a new tenant opens a quarterly campaign that holds every grant of the tenant`() =
        run { env ->
            val opened = env.open("2026Q3")
            assertThat(opened.quarter).isEqualTo("2026Q3")
            assertThat(opened.status).isEqualTo(Status.OPEN)
            assertThat(opened.tenantRef).isEqualTo(env.tenant.toString())
            assertThat(
                env.campaignGrants(opened.reviewRef).keys,
            ).containsExactlyInAnyOrderElementsOf(env.seeded.values.flatten())
            // a grant of another tenant is never listed
            val foreign = env.seed("user:foreign", Role.AUDITOR, into = env.other)
            assertThat(env.campaignGrants(opened.reviewRef).keys).doesNotContain(foreign)
        }

    @Test
    fun `INV-AC-2249 a monthly label and labels less often than quarterly are each refused 400`() =
        run { env ->
            val pinned =
                GstVariationPointCatalogue("access-svc").find(VariationPointKey("VP-05"))!!.status
            // the monthly refusal holds while the pinned row is planned; once available, the cadence test rules
            if (pinned == VpStatus.PLANNED) env.assertRefused(env.refusal { env.open("2026-09") }, 400)
            listOf(
                "2026H1",
                "2026",
                "2026q3",
                "2026Q5",
                "2026Q31",
                "2026Q0",
                " 2026Q3",
                "2026Q3 ",
                "2026-13",
                "",
            ).forEach {
                env.assertRefused(env.refusal { env.open(it) }, 400)
            }
            assertThat(
                env.count("select count(*) from access.review_campaign where tenant_id = ?", env.tenant.value),
            ).isZero()
        }

    @Test
    fun `INV-AC-2249 the monthly setting is refused while VP-05 is planned, the cadence stays quarterly`() =
        run { env ->
            val pinned = GstVariationPointCatalogue("access-svc").find(VariationPointKey("VP-05"))!!.status
            if (pinned == VpStatus.PLANNED) {
                val refused = env.setCadence("MONTHLY")
                assertThat(refused.status).describedAs(refused.contentAsString).isGreaterThanOrEqualTo(400)
                assertThat(
                    env.count(
                        "select count(*) from access.config_setting where tenant_id = ? and vp_key = 'VP-05'",
                        env.tenant.value,
                    ),
                ).isZero()
                assertThat(env.open("2026Q3").status).isEqualTo(Status.OPEN)
            }
        }

    @Test
    fun `INV-AC-2249 a repeated runAccessReview of one tenant and period answers the same campaign, stores nothing`() =
        run { env ->
            val first = env.open("2026Q3")
            val second = env.open("2026Q3", actor = OFFICER_2)
            assertThat(second.reviewRef).isEqualTo(first.reviewRef)
            assertThat(env.campaignRows("2026Q3")).isEqualTo(1)
            assertThat(env.campaignGrants(first.reviewRef)).hasSameSizeAs(env.seeded.values.flatten())
            // another period is another campaign, and another tenant has its own for the same period
            assertThat(env.open("2026Q4").reviewRef).isNotEqualTo(first.reviewRef)
        }

    @Test
    fun `INV-AC-2249 a stored monthly cadence opens a month label and refuses a quarter label`() =
        AccessReviewEnv.run(available = true) { env ->
            env.seedStandard()
            val set = env.setCadence("MONTHLY")
            assertThat(set.status).describedAs(set.contentAsString).isBetween(200, 299)
            env.assertRefused(env.refusal { env.open("2026Q3") }, 400)
            assertThat(env.open("2026-09").quarter).isEqualTo("2026-09")
            assertThat(env.campaignRows("2026-09")).isEqualTo(1)
            assertThat(env.campaignRows("2026Q3")).isZero()
        }

    @Test
    fun `INV-AC-2249 with VP-05 available and no stored cadence the default is quarterly`() =
        AccessReviewEnv.run(available = true) { env ->
            env.seedStandard()
            env.assertRefused(env.refusal { env.open("2026-09") }, 400)
            assertThat(env.open("2026Q3").status).isEqualTo(Status.OPEN)
        }

    // ---- INV-AC-937: outcomes per grant, revocations at close, no review of one's own grants ----------------------

    @Test
    fun `INV-AC-937 a quarterly review closed with two revocations records an outcome per grant`() =
        run { env ->
            val campaign = env.open("2026Q3")
            val own = env.grantsHeldBy(campaign.reviewRef, OFFICER_1)
            val others = env.campaignGrants(campaign.reviewRef).keys - own.toSet()
            val revoked =
                env.grantsHeldBy(campaign.reviewRef, VIEWERS[0]) + env.grantsHeldBy(campaign.reviewRef, VIEWERS[1])
            val kept = others - revoked.toSet()
            val changedBefore = env.authorityChangedRows()
            // the reviewer cannot review their own grants
            env.denied(env.refusal { env.outcomes(campaign.reviewRef, OFFICER_1, own) })
            assertThat(env.outcomeRows(campaign.reviewRef)).isZero()
            assertThat(env.close(campaign.reviewRef, OFFICER_1, *revoked.map { it to "REVOKED" }.toTypedArray()).status)
                .isEqualTo(Status.OPEN)
            assertThat(env.outcomes(campaign.reviewRef, OFFICER_1, kept).status).isEqualTo(Status.OPEN)
            // nothing takes effect before the close
            revoked.forEach { assertThat(env.grantStatus(it)).isEqualTo("ACTIVE") }
            assertThat(env.authorityChangedRows()).isEqualTo(changedBefore)
            assertThat(env.outcomes(campaign.reviewRef, OFFICER_2, own).status).isEqualTo(Status.CLOSED)
            assertThat(env.outcomeRows(campaign.reviewRef)).isEqualTo(env.campaignGrants(campaign.reviewRef).size)
            revoked.forEach { assertThat(env.grantStatus(it)).isEqualTo("REVOKED") }
            (kept + own).forEach { assertThat(env.grantStatus(it)).isEqualTo("ACTIVE") }
            assertThat(env.authorityChangedRows()).isEqualTo(changedBefore + 2)
            // the outcome carries the acting user ID and the time, never a role
            val actors =
                env.query(
                    "select distinct actor_ref from access.review_outcome " +
                        "where tenant_id = ? and decided_at is not null",
                    env.tenant.value,
                ) { it.getString(1) }
            assertThat(actors).containsExactlyInAnyOrder(OFFICER_1, OFFICER_2)
        }

    @Test
    fun `INV-AC-937 the same outcome again is a no-op and a different outcome is 409`() =
        run { env ->
            val campaign = env.open()
            val grant = env.grantsHeldBy(campaign.reviewRef, VIEWERS[0]).single()
            env.close(campaign.reviewRef, OFFICER_1, grant to "CONFIRMED")
            val before =
                env.query(
                    "select decided_at::text, actor_ref from access.review_outcome where tenant_id = ?",
                    env.tenant.value,
                ) {
                    it.getString(1) + it.getString(2)
                }
            assertThat(env.close(campaign.reviewRef, OFFICER_2, grant to "CONFIRMED").status).isEqualTo(Status.OPEN)
            assertThat(env.outcomeRows(campaign.reviewRef)).isEqualTo(1)
            assertThat(
                env.query(
                    "select decided_at::text, actor_ref from access.review_outcome where tenant_id = ?",
                    env.tenant.value,
                ) {
                    it.getString(1) + it.getString(2)
                },
            ).isEqualTo(before)
            env.conflict { env.close(campaign.reviewRef, OFFICER_2, grant to "REVOKED") }
            assertThat(env.grantStatus(grant)).isEqualTo("ACTIVE")
            assertThat(env.outcomeRows(campaign.reviewRef)).isEqualTo(1)
        }

    @Test
    fun `INV-AC-937 a grant that is not in the campaign and a campaign that does not exist are 404`() =
        run { env ->
            val campaign = env.open()
            env.assertRefused(env.refusal { env.close(campaign.reviewRef, OFFICER_2, "GRT-none" to "CONFIRMED") }, 404)
            env.assertRefused(env.refusal { env.close("REV-none", OFFICER_2, "GRT-none" to "CONFIRMED") }, 404)
            val foreign = env.seed("user:foreign", Role.AUDITOR, into = env.other)
            env.assertRefused(env.refusal { env.close(campaign.reviewRef, OFFICER_2, foreign to "CONFIRMED") }, 404)
        }

    // ---- INV-AC-2046: the second reviewer, and the sole-operator path --------------------------------------------

    @Test
    fun `INV-AC-2046 the reviewer's own grants are refused to them and tenant admin, accepted from a second officer`() =
        run { env ->
            val campaign = env.open()
            val own = env.grantsHeldBy(campaign.reviewRef, OFFICER_1)
            assertThat(own).hasSize(2)
            env.denied(env.refusal { env.outcomes(campaign.reviewRef, OFFICER_1, own) })
            // tenant.admin holds no key access-review.run
            env.denied(env.refusal { env.outcomes(campaign.reviewRef, ADMIN, own.take(1)) })
            assertThat(env.outcomeRows(campaign.reviewRef)).isZero()
            val rest = env.campaignGrants(campaign.reviewRef).keys - own.toSet()
            assertThat(env.outcomes(campaign.reviewRef, OFFICER_1, rest).status).isEqualTo(Status.OPEN)
            assertThat(env.outcomes(campaign.reviewRef, OFFICER_2, own.take(1)).status).isEqualTo(Status.OPEN)
            assertThat(env.outcomes(campaign.reviewRef, OFFICER_2, own.drop(1)).status).isEqualTo(Status.CLOSED)
        }

    @Test
    fun `INV-AC-2046 a self-review without or with a withdrawn acknowledgement fails 403`() =
        run { env ->
            val campaign = env.open()
            val own = env.grantsHeldBy(campaign.reviewRef, OFFICER_1)
            env.denied(env.refusal { env.outcomes(campaign.reviewRef, OFFICER_1, own.take(1)) })
            val ack = env.recordAck()
            env.withdrawAck(ack)
            env.denied(env.refusal { env.outcomes(campaign.reviewRef, OFFICER_1, own.take(1)) })
            assertThat(env.outcomeRows(campaign.reviewRef)).isZero()
        }

    @Test
    fun `INV-AC-2046 a self-review with the ACTIVE acknowledgement passes and the audit entry names it`() =
        run { env ->
            val campaign = env.open()
            val own = env.grantsHeldBy(campaign.reviewRef, OFFICER_1)
            val ack = env.recordAck()
            val auditBefore = env.auditRows().size
            assertThat(env.outcomes(campaign.reviewRef, OFFICER_1, own.take(1)).status).isEqualTo(Status.OPEN)
            val stored =
                env.query(
                    "select ack_ref from access.review_outcome where tenant_id = ? and grant_ref = ?",
                    env.tenant.value,
                    own[0],
                ) {
                    it.getString(1)
                }
            assertThat(stored).containsExactly(ack)
            val audit = env.auditRows().drop(auditBefore).filter { "closeAccessReview" in it }
            assertThat(audit).hasSize(1)
            assertThat(audit.single()).contains(ack).contains(OFFICER_1)
            // an outcome on someone else's grant names no acknowledgement
            val viewer = env.grantsHeldBy(campaign.reviewRef, VIEWERS[0])
            env.outcomes(campaign.reviewRef, OFFICER_1, viewer)
            assertThat(
                env.query(
                    "select coalesce(ack_ref, 'none') from access.review_outcome where tenant_id = ? and grant_ref = ?",
                    env.tenant.value,
                    viewer[0],
                ) { it.getString(1) },
            ).containsExactly("none")
        }

    @Test
    fun `INV-AC-2046 the campaign becomes CLOSED only on the last missing outcome`() =
        run { env ->
            val campaign = env.open()
            val all = env.campaignGrants(campaign.reviewRef).keys.toList()
            val own = env.grantsHeldBy(campaign.reviewRef, OFFICER_1)
            val ordered = all.filterNot { it in own } + own
            ordered.dropLast(1).forEachIndexed { i, grant ->
                val actor = if (grant in own) OFFICER_2 else OFFICER_1
                assertThat(
                    env.outcomes(campaign.reviewRef, actor, listOf(grant)).status,
                ).describedAs("after ${i + 1}").isEqualTo(Status.OPEN)
            }
            assertThat(
                env.outcomes(campaign.reviewRef, OFFICER_2, listOf(ordered.last())).status,
            ).isEqualTo(Status.CLOSED)
        }

    @Test
    fun `a caller without the key access-review-run is refused though holding the role security-officer`() =
        AccessReviewEnv.run(catalogueMatrix = false) { env ->
            // the fixture matrix holds no key access-review.run, so no role holds it
            env.seedStandard()
            env.denied(env.refusal { env.open() })
            env.denied(env.refusal { env.close("REV-none", OFFICER_1, "GRT-none" to "CONFIRMED") })
            assertThat(
                env.count("select count(*) from access.review_campaign where tenant_id = ?", env.tenant.value),
            ).isZero()
        }

    @Test
    fun `a caller whose role has no key access-review-run in the catalogue matrix is refused`() =
        run { env ->
            val campaign = env.open()
            env.denied(env.refusal { env.open(actor = ADMIN) })
            env.denied(
                env.refusal {
                    env.outcomes(campaign.reviewRef, VIEWERS[0], env.grantsHeldBy(campaign.reviewRef, VIEWERS[1]))
                },
            )
            env.denied(env.refusal { env.open(actor = "user:nobody") })
            // a campaign is for the bound tenant only
            env.denied(env.refusal { env.open(tenantRef = env.other.toString()) })
            assertThat(env.campaignRows("2026Q3")).isEqualTo(1)
        }

    // ---- INV-AC-16621: a campaign of three grants closes only with an outcome per grant -------------------------

    @Test
    fun `INV-AC-16621 three grants close only on a second officer's outcome for the reviewer's own`() =
        AccessReviewEnv.run { env ->
            // exactly three grants in the campaign: one held by the acting reviewer, two by others
            val own = env.seed(OFFICER_1, Role.SECURITY_OFFICER)
            val first = env.seed(VIEWERS[0], Role.AUDITOR)
            val second = env.seed(VIEWERS[1], Role.AUDITOR)
            val campaign = env.open()
            // the second officer and tenant admin are seeded after the snapshot, so they are not in the campaign
            env.seed(OFFICER_2, Role.SECURITY_OFFICER)
            val grants = env.campaignGrants(campaign.reviewRef)
            assertThat(grants.keys).containsExactlyInAnyOrder(own, first, second)
            assertThat(env.grantsHeldBy(campaign.reviewRef, OFFICER_1)).containsExactly(own)
            val changedBefore = env.authorityChangedRows()

            // (a) after two outcomes on the other grants the campaign is OPEN, nothing has taken effect
            assertThat(env.close(campaign.reviewRef, OFFICER_1, first to "REVOKED").status).isEqualTo(Status.OPEN)
            assertThat(env.close(campaign.reviewRef, OFFICER_1, second to "REVOKED").status).isEqualTo(Status.OPEN)
            assertThat(env.outcomeRows(campaign.reviewRef)).isEqualTo(2)
            assertThat(env.grantStatus(first)).isEqualTo("ACTIVE")
            assertThat(env.grantStatus(second)).isEqualTo("ACTIVE")
            assertThat(env.authorityChangedRows()).isEqualTo(changedBefore)

            // (b) the outcome on the reviewer's own grant is refused: ApiRefusal ACCESS_DENIED, 403 SRT-004
            val refused = env.refusal { env.close(campaign.reviewRef, OFFICER_1, own to "CONFIRMED") }
            env.denied(refused)
            assertThat(refused.code.code).isEqualTo("SRT-004")
            assertThat(env.statusOf(refused)).isEqualTo(403)
            assertThat(env.outcomeRows(campaign.reviewRef)).isEqualTo(2)
            assertThat(
                env.count(
                    "select count(*) from access.review_outcome where tenant_id = ? and campaign_ref = ? " +
                        "and grant_ref = ?",
                    env.tenant.value,
                    campaign.reviewRef,
                    own,
                ),
            ).isZero()

            // (c) another caller holding access-review.run records it: CLOSED, both revocations applied by that close
            assertThat(env.close(campaign.reviewRef, OFFICER_2, own to "CONFIRMED").status).isEqualTo(Status.CLOSED)
            val revokedOutcomes =
                env.count(
                    "select count(*) from access.review_outcome where tenant_id = ? and campaign_ref = ? " +
                        "and decision = 'REVOKE'",
                    env.tenant.value,
                    campaign.reviewRef,
                )
            assertThat(revokedOutcomes).isEqualTo(2)
            assertThat(env.grantStatus(first)).isEqualTo("REVOKED")
            assertThat(env.grantStatus(second)).isEqualTo("REVOKED")
            assertThat(env.grantStatus(own)).isEqualTo("ACTIVE")
            assertThat(env.authorityChangedRows()).isEqualTo(changedBefore + revokedOutcomes)
            assertThat(env.outcomeRows(campaign.reviewRef)).isEqualTo(3)

            // (d) a caller for whom access-review.run is decided deny is refused on every outcome call
            val rows = env.outcomeRows(campaign.reviewRef)
            grants.keys.forEach { grant ->
                env.denied(env.refusal { env.close(campaign.reviewRef, ADMIN, grant to "CONFIRMED") })
            }
            assertThat(env.outcomeRows(campaign.reviewRef)).isEqualTo(rows)
        }
}
