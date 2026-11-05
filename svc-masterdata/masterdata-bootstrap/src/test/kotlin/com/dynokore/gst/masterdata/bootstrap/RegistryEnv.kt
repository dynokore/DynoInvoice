package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.NetworkRegistrationController
import com.dynokore.gst.masterdata.adapters.http.RegisterSuggestionsController
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.gst.masterdata.application.registry.RegisterAnswer
import com.dynokore.gst.masterdata.application.registry.RegisterQuestion
import com.dynokore.gst.masterdata.contract.model.Reachability
import com.dynokore.gst.masterdata.contract.model.RegisterSuggestion
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.serviceruntime.events.PlatformEventTypes
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID
import com.dynokore.platform.serviceruntime.logging.CorrelationId as LoggingCorrelationId

/** The real application context bound to the registries at [directoryUrl] and [registerUrl], over real PostgreSQL. */
internal fun runRegistry(
    directoryUrl: String = Simulators.directory.baseUrl,
    registerUrl: String = Simulators.register.baseUrl,
    customize: (WebApplicationContextRunner) -> WebApplicationContextRunner = { it },
    block: (RegistryEnv) -> Unit,
) {
    Simulators.resetAll()
    customize(BoundaryFixture.runner())
        .withPropertyValues(
            "masterdata.registry.directory.base-url=$directoryUrl",
            "masterdata.registry.register.base-url=$registerUrl",
            "masterdata.registry.timeout=PT3S",
        ).run { context ->
            context.startupFailure?.let { throw it }
            block(RegistryEnv(context))
        }
}

/** What the registry criteria tests call: the resolution, the register lookup, the policy ports and the records. */
internal class RegistryEnv(
    val context: AssertableWebApplicationContext,
) {
    val ce = CounterpartyEnv(context)
    val clock: MovableClock = context.getBean(Clock::class.java) as MovableClock
    val lookupRegister: LookupGstRegister = context.getBean(LookupGstRegister::class.java)
    val networkRegistration: NetworkRegistrationController = context.getBean(NetworkRegistrationController::class.java)
    val suggestions: RegisterSuggestionsController = context.getBean(RegisterSuggestionsController::class.java)

    fun entity(): String = unique("ENT").also { ce.triple(it) }

    /** The reachability fact of [peppolId] as `resolveCounterparty` returns it for [entity]. */
    fun reach(
        entity: String,
        peppolId: String,
    ): Reachability = checkNotNull(ce.resolve(entity, "PEPPOL_PARTICIPANT_ID", peppolId).reachability)

    fun lookup(
        entity: String,
        uen: String,
        party: String? = null,
    ): RegisterAnswer = ce.inTenant { lookupRegister.handle(RegisterQuestion(entity, uen, party?.let(::PartyRef))) }

    fun businessDate(): LocalDate = clock.instant().atZone(ZoneId.of("Asia/Singapore")).toLocalDate()

    fun advance(by: Duration) = clock.advance(by)

    /** One document: every lookup made inside [block] carries [correlation] on its event envelope. */
    fun <T> document(
        correlation: String,
        block: () -> T,
    ): T = CorrelationContext.withCorrelation(LoggingCorrelationId(correlation), block)

    /** The lookup records of the tenant, oldest first: key, kind, queried, bytes, hash, degradation, correlation. */
    fun records(kind: String? = null): List<LookupRow> =
        BoundaryFixture.asSuperuser { c ->
            val where = kind?.let { "and kind = '$it'" }.orEmpty()
            c
                .createStatement()
                .executeQuery(
                    "select record_key, kind, queried, bytes, hash, degradation, correlation_id " +
                        "from masterdata.lookup_record where tenant_id = '${ce.tenant}'::uuid $where " +
                        "order by looked_up_at, id",
                ).use { rs ->
                    val out = mutableListOf<LookupRow>()
                    while (rs.next()) {
                        out +=
                            LookupRow(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getString(7),
                            )
                    }
                    out
                }
        }

    /** The `EvidenceArtefactRecorded` envelopes this context published for the tenant, oldest first. */
    fun evidenceEvents(): List<Envelope> =
        ce.captured.envelopes.filter {
            it.tenant == ce.tenant && it.type == PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED
        }

    fun count(sql: String): Int = ce.count(sql.replace("{tenant}", ce.tenant.toString()))

    /** The suggestions the list operation returns for [party], as the console reads them. */
    fun suggestionsOf(
        party: String,
        state: String? = null,
    ): List<RegisterSuggestion> =
        ce.inTenant { suggestions.listRegisterSuggestions(party, state, PAGE, null).body!!.items }

    fun confirm(suggestionId: String): RegisterSuggestion =
        ce.inTenant { suggestions.confirmRegisterSuggestion(suggestionId, UUID.randomUUID()).body!! }

    /** The versions of [party] whose legal name is [name]. */
    fun versionsNamed(
        party: String,
        name: String,
    ): Int =
        count(
            "select count(*) from masterdata.counterparty_version where tenant_id = '{tenant}'::uuid " +
                "and party_ref = '$party' and legal_name = '$name'",
        )

    private companion object {
        const val PAGE = 100
    }
}

internal data class LookupRow(
    val key: String,
    val kind: String,
    val queried: String,
    val bytes: String,
    val hash: String,
    val degradation: String?,
    val correlation: String,
)

/**
 * The equality INV-AC-9503 asks for: the event carries artefact type `REGISTER_LOOKUP`, the reference of the lookup
 * record and the SHA-256 of the stored bytes, and its envelope carries the call's correlation. Compared by equality,
 * never by pattern, so the look-alike type `REGISTER_LOOKUPS` or a hash of other bytes fails it.
 */
internal fun assertEvidenceEvent(
    event: Envelope,
    row: LookupRow,
    correlation: String,
) {
    val data = JsonMapper.builder().build().readTree(event.dataJson)
    org.assertj.core.api.Assertions
        .assertThat(data.get("artefactType").asString())
        .isEqualTo("REGISTER_LOOKUP")
    org.assertj.core.api.Assertions
        .assertThat(data.get("reference").asString())
        .isEqualTo(row.key)
    org.assertj.core.api.Assertions
        .assertThat(data.get("hash").asString())
        .isEqualTo(sha256(row.bytes))
    org.assertj.core.api.Assertions
        .assertThat(event.correlation.value)
        .isEqualTo(correlation)
    org.assertj.core.api.Assertions
        .assertThat(row.correlation)
        .isEqualTo(correlation)
}

private fun sha256(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
