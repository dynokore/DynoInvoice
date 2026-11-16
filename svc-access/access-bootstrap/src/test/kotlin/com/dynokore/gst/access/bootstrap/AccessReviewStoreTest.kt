package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.review.ReviewCampaigns
import com.dynokore.gst.access.application.review.ReviewOutcomes
import com.dynokore.gst.access.bootstrap.AccessReviewEnv.Companion.OFFICER_1
import com.dynokore.gst.access.bootstrap.AccessReviewEnv.Companion.OFFICER_2
import com.dynokore.gst.access.bootstrap.AccessReviewEnv.Companion.VIEWERS
import com.dynokore.gst.access.contract.model.AccessReviewResponse.Status
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.review.PeriodLabel
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/** INV-T-15798: the tables of the review, their constraints and row-level security, the close in one transaction. */
class AccessReviewStoreTest {
    private fun run(block: (AccessReviewEnv) -> Unit) =
        AccessReviewEnv.run { env -> env.seedStandard().also { block(env) } }

    private fun insertOutcome(
        env: AccessReviewEnv,
        campaign: String,
        grant: String,
    ) = BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "insert into access.review_outcome " +
                    "(id, tenant_id, campaign_ref, grant_ref, decision, actor_ref, decided_at) " +
                    "values (?, ?, ?, ?, 'KEEP', 'user:x', now())",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, env.tenant.value)
                it.setString(3, campaign)
                it.setString(4, grant)
                it.executeUpdate()
            }
    }

    @Test
    fun `a second outcome for the same campaign and grant is refused by the unique key and the first row stays`() =
        run { env ->
            val campaign = env.open()
            val grant = env.grantsHeldBy(campaign.reviewRef, VIEWERS[0]).single()
            env.close(campaign.reviewRef, OFFICER_1, grant to "CONFIRMED")
            val thrown = catchThrowable { insertOutcome(env, campaign.reviewRef, grant) }
            assertThat(thrown).isInstanceOf(SQLException::class.java)
            assertThat((thrown as SQLException).sqlState).isEqualTo("23505")
            assertThat(env.outcomeRows(campaign.reviewRef)).isEqualTo(1)
            // a second campaign row for the same tenant and period is refused too
            val again =
                catchThrowable {
                    BoundaryFixture.asSuperuser { c ->
                        c
                            .prepareStatement(
                                "insert into access.review_campaign " +
                                    "(id, tenant_id, campaign_ref, period, cadence, opened_by, opened_at)" +
                                    " values (?, ?, 'REV-dup', '2026Q3', 'QUARTERLY', 'user:x', now())",
                            ).use {
                                it.setObject(1, UUID.randomUUID())
                                it.setObject(2, env.tenant.value)
                                it.executeUpdate()
                            }
                    }
                }
            assertThat((again as SQLException).sqlState).isEqualTo("23505")
        }

    @Test
    fun `a read under another tenant's binding returns zero rows`() =
        run { env ->
            val campaign = env.open()
            env.close(
                campaign.reviewRef,
                OFFICER_1,
                env.grantsHeldBy(campaign.reviewRef, VIEWERS[0]).single() to "CONFIRMED",
            )
            val tx = env.context.getBean(TransactionRunner::class.java)
            val campaigns = env.context.getBean(ReviewCampaigns::class.java)
            val outcomes = env.context.getBean(ReviewOutcomes::class.java)

            fun <T> asOther(block: () -> T): T =
                env.tenants.bind(env.other, "user:other") { tx.inTransaction { block() } }

            fun <T> asOwner(block: () -> T): T =
                env.tenants.bind(env.tenant, OFFICER_1) { tx.inTransaction { block() } }
            assertThat(asOwner { outcomes.recorded(campaign.reviewRef) }).hasSize(1)
            assertThat(asOther { outcomes.recorded(campaign.reviewRef) }).isEmpty()
            assertThat(asOther { campaigns.find(campaign.reviewRef) }).isNull()
            assertThat(asOther { campaigns.findByPeriod(PeriodLabel.parse("2026Q3")!!) }).isNull()
            assertThat(
                asOther {
                    outcomes.find(
                        campaign.reviewRef,
                        GrantRef(
                            env.seeded.values
                                .first()
                                .first(),
                        ),
                    )
                },
            ).isNull()
        }

    @Test
    fun `an unbound runtime session sees no row and the tables hold the forced policy of role_grant`() =
        run { env ->
            env.open()
            val unbound =
                BoundaryFixture.asRuntime { c ->
                    listOf("review_campaign", "review_campaign_grant", "review_outcome").associateWith { table ->
                        c.createStatement().executeQuery("select count(*) from access.$table").use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }
            assertThat(unbound.values).containsOnly(0)
            val policies =
                env.query(
                    "select c.relname || '|' || c.relforcerowsecurity || '|' || p.polname from pg_class c " +
                        "join pg_policy p on p.polrelid = c.oid where c.relname like 'review\\_%' order by 1",
                ) { it.getString(1) }
            assertThat(policies).containsExactlyInAnyOrder(
                "review_campaign|true|tenant_isolation",
                "review_campaign_grant|true|tenant_isolation",
                "review_outcome|true|tenant_isolation",
            )
        }

    @Test
    fun `a closing outcome with one revocation ends the grant and writes one AuthorityChanged row`() =
        run { env ->
            val campaign = env.open()
            val all = env.campaignGrants(campaign.reviewRef).keys
            val revoked = env.grantsHeldBy(campaign.reviewRef, VIEWERS[0]).single()
            val own = env.grantsHeldBy(campaign.reviewRef, OFFICER_1)
            env.close(
                campaign.reviewRef,
                OFFICER_1,
                *(all - own.toSet() - revoked).map { it to "CONFIRMED" }.toTypedArray(),
            )
            env.close(campaign.reviewRef, OFFICER_2, *own.map { it to "CONFIRMED" }.toTypedArray())
            val before = env.authorityChangedRows()
            assertThat(env.grantStatus(revoked)).isEqualTo("ACTIVE")
            assertThat(env.close(campaign.reviewRef, OFFICER_2, revoked to "REVOKED").status).isEqualTo(Status.CLOSED)
            assertThat(env.grantStatus(revoked)).isEqualTo("REVOKED")
            assertThat(env.authorityChangedRows()).isEqualTo(before + 1)
            assertThat(env.auditRows().filter { "revokeGrant" in it }).hasSize(1)
        }

    /** Runs [block] while every update of the grant [grant] fails in the database. */
    private fun failingUpdateOf(
        grant: String,
        block: () -> Unit,
    ) {
        val trigger = "t15798_" + UUID.randomUUID().toString().take(8)
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.execute(
                    "create function access.${trigger}_fn() returns trigger language plpgsql as " +
                        "\$\$ begin raise exception 'forced failure'; end \$\$",
                )
                it.execute(
                    "create trigger $trigger before update on access.role_grant for each row " +
                        "when (old.grant_ref = '$grant') execute function access.${trigger}_fn()",
                )
            }
        }
        try {
            block()
        } finally {
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use {
                    it.execute("drop trigger $trigger on access.role_grant")
                    it.execute("drop function access.${trigger}_fn()")
                }
            }
        }
    }

    @Test
    fun `a forced failure while revoking leaves the outcome, the status and the grants unchanged`() =
        run { env ->
            val campaign = env.open()
            val all = env.campaignGrants(campaign.reviewRef).keys
            val own = env.grantsHeldBy(campaign.reviewRef, OFFICER_1)
            val doomed = env.grantsHeldBy(campaign.reviewRef, VIEWERS[0]).single()
            env.close(
                campaign.reviewRef,
                OFFICER_1,
                *(all - own.toSet() - doomed).map { it to "CONFIRMED" }.toTypedArray(),
            )
            env.close(campaign.reviewRef, OFFICER_2, *own.map { it to "CONFIRMED" }.toTypedArray())
            val outcomesBefore = env.outcomeRows(campaign.reviewRef)
            val changedBefore = env.authorityChangedRows()
            val auditBefore = env.auditRows().size
            failingUpdateOf(doomed) {
                assertThat(catchThrowable { env.close(campaign.reviewRef, OFFICER_2, doomed to "REVOKED") }).isNotNull()
            }
            assertThat(env.outcomeRows(campaign.reviewRef)).isEqualTo(outcomesBefore)
            assertThat(env.grantStatus(doomed)).isEqualTo("ACTIVE")
            assertThat(env.authorityChangedRows()).isEqualTo(changedBefore)
            assertThat(env.auditRows()).hasSize(auditBefore)
            // the same call now succeeds: the campaign was still open
            assertThat(env.close(campaign.reviewRef, OFFICER_2, doomed to "REVOKED").status).isEqualTo(Status.CLOSED)
            assertThat(env.grantStatus(doomed)).isEqualTo("REVOKED")
        }
}
