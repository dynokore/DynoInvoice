package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.WriteCatalogue.DESPATCH
import com.dynokore.platform.configresolver.WriteCatalogue.DUE_DATE
import com.dynokore.platform.configresolver.WriteCatalogue.EVERY_LEVEL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The PolicyChanged payload is exactly the INV-API-3306 data model (INV-Q-3714): it conforms to the pinned
 * platform-contracts `policy-changed-payload.schema.json` and carries no settingId, profileId or profileVersion,
 * which stay on the setting record (owner ruling INV-Q-2979).
 */
@Tag("integration")
class PolicyChangedPayloadPostgresIT : WritePathBase() {
    private val mapper = ObjectMapper()
    private val dataModel =
        setOf("scope", "vpId", "value", "effectiveDate", "source", "relaxationAcknowledgementReference")

    private fun Run.lastPolicyChanged(): JsonNode = mapper.readTree(events().last { it.kind == "event" }.dataJson)

    private fun assertDataModel(
        payload: JsonNode,
        source: String,
    ) {
        assertEquals(emptyList<String>(), PayloadSchema.errors(SCHEMA, payload), payload.toString())
        assertEquals(source, payload.get("source").asString())
        listOf("settingId", "profileId", "profileVersion").forEach { assertFalse(payload.has(it), "$it in $payload") }
        assertTrue(payload.propertyNames().all { it in dataModel }, payload.toString())
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-API-3306 an OVERRIDE payload is the data model only`(image: String) {
        val r = Run(image)
        r.put(set(EVERY_LEVEL, r.e1, ScopeLevel.ENTITY, one("B"), admin))
        assertDataModel(r.lastPolicyChanged(), "OVERRIDE")
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-API-3306 a PROFILE payload leaves the profile id and version on the setting record`(image: String) {
        val r = Run(image)
        val profile = "PRF-1" to "3"
        val window = one("DAILY_WINDOW")
        r.put(set(DESPATCH, r.e1, ScopeLevel.ENTITY, window, source = SettingSource.PROFILE, profile = profile))
        assertDataModel(r.lastPolicyChanged(), "PROFILE")
        val source = r.resolve(DESPATCH, r.e1).source as PolicySource.Profile
        assertEquals(profile, source.profileId to source.profileVersion)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-API-3306 a relaxing payload carries its acknowledgement reference`(image: String) {
        val r = Run(image)
        val move = one("NEXT_WORKING_DAY")
        val solo = r.ack(ackCmd(DUE_DATE, r.e1, ScopeLevel.ENTITY, move, approver, "tax.approver"))
        r.put(set(DUE_DATE, r.e1, ScopeLevel.ENTITY, move, relaxation = solo.relaxation))
        val payload = r.lastPolicyChanged()
        assertDataModel(payload, "OVERRIDE")
        assertEquals(solo.relaxation.toString(), payload.get("relaxationAcknowledgementReference").asString())
    }

    private companion object {
        const val SCHEMA = "service-runtime/schemas/policy-changed-payload.schema.json"
    }
}
