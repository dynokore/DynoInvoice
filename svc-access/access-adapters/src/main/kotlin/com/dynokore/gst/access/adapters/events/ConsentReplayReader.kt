package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.column
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.CONSENT
import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.outboxandqueue.ReplayScope
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

/** One consent record to replay: the client entity it is keyed on, its payload and the aggregate key of its event. */
internal class ConsentReplay(
    val client: String,
    val json: String,
    val aggregateKey: String,
)

/**
 * Reads the consent records of the bound tenant for the replay (INV-T-9061): one per client entity, the LIVE row or
 * else the WITHDRAWN row with the highest stored version (a row without one counts as the highest, so it is chosen and
 * fails the page). The scope filters apply to the chosen row, so a scan walks the client entities in chunks until the
 * requested number of records is found.
 */
internal class ConsentReplayReader(
    private val dsl: DSLContext,
    private val payloads: AuthorityChangedPayloads,
) {
    fun read(
        scope: ReplayScope?,
        after: String?,
        count: Int,
    ): List<ConsentReplay> {
        val found = mutableListOf<ConsentReplay>()
        var from = after
        while (found.size < count) {
            val clients =
                dsl
                    .selectDistinct(CONSENT.CLIENT_ENTITY_REF)
                    .from(CONSENT)
                    .where(
                        from?.let { CONSENT.CLIENT_ENTITY_REF.gt(it) } ?: DSL.noCondition(),
                        (scope as? ReplayScope.Entity)?.let { CONSENT.CLIENT_ENTITY_REF.eq(it.entity.value) }
                            ?: DSL.noCondition(),
                    ).orderBy(CONSENT.CLIENT_ENTITY_REF)
                    .limit(count)
                    .fetch(CONSENT.CLIENT_ENTITY_REF)
                    .filterNotNull()
            if (clients.isEmpty()) break
            val rows =
                dsl
                    .selectFrom(CONSENT)
                    .where(CONSENT.CLIENT_ENTITY_REF.`in`(clients))
                    .fetch()
                    .groupBy { it.clientEntityRef }
            for (client in clients) {
                val chosen = choose(rows.getValue(client))
                if (inPeriod(scope, chosen)) found += replayOf(client, chosen)
            }
            from = clients.last()
        }
        return found.take(count)
    }

    private fun choose(rows: List<Record>): Record {
        val live = rows.filter { it.get(CONSENT.STATUS) == ConsentStatus.LIVE.name }
        return live.ifEmpty { rows }.maxWith(
            compareBy<Record>({ it.get(CONSENT.AUTHORITY_VERSION) ?: Long.MAX_VALUE }, { it.get(CONSENT.ID) }),
        )
    }

    private fun inPeriod(
        scope: ReplayScope?,
        row: Record,
    ): Boolean =
        (scope as? ReplayScope.Period)?.let {
            val at = row.get(CONSENT.UPDATED_AT).column("updated_at").toInstant()
            !at.isBefore(it.from) && at.isBefore(it.to)
        } ?: true

    private fun replayOf(
        client: String?,
        row: Record,
    ): ConsentReplay {
        val entity = client.column("client_entity_ref")
        val version =
            checkNotNull(row.get(CONSENT.AUTHORITY_VERSION)) {
                "the row consent $entity must be replayed and has no stored authority version"
            }
        val consent =
            Consent(
                ref = ConsentRef(row.get(CONSENT.CONSENT_REF).column("consent_ref")),
                client = EntityRef(entity),
                agent = IdentityRef(row.get(CONSENT.AGENT_REF).column("agent_ref")),
                terms =
                    ConsentTerms(
                        items =
                            buildSet {
                                if (row.get(CONSENT.SUBMIT_INVOICE_DATA) == true) add(ScopeItem.SUBMIT_INVOICE_DATA)
                                if (row.get(CONSENT.FILE_GST_RETURN) == true) add(ScopeItem.FILE_GST_RETURN)
                                if (row.get(CONSENT.MAPPING_APPROVAL) == true) add(ScopeItem.MAPPING_APPROVAL)
                            },
                        validFrom = row.get(CONSENT.VALID_FROM).column("valid_from"),
                        authorisingPersonEmail =
                            row.get(CONSENT.AUTHORISING_PERSON_EMAIL).column("authorising_person_email"),
                    ),
                status = ConsentStatus.valueOf(row.get(CONSENT.STATUS).column("status")),
                handoverStatementRef = row.get(CONSENT.HANDOVER_STATEMENT_REF),
                version = row.get(CONSENT.VERSION).column("version"),
            )
        val tenant = TenantRef(row.get(CONSENT.TENANT_ID).column("tenant_id").toString())
        val json = payloads.consent(ConsentAuthorityRecord(tenant, consent, version)).toString()
        return ConsentReplay(entity, json, consent.agent.value)
    }
}
