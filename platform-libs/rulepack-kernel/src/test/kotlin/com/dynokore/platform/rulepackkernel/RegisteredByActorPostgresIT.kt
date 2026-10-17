package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/** Testcontainers needs a concrete subclass for its fluent `self()` typing (Kotlin, unlike Java, has no raw types). */
private class ActorPostgres(
    image: DockerImageName,
) : PostgreSQLContainer<ActorPostgres>(image)

/**
 * The actor rule of [PackRegistryMigration] is tenancy-kernel's shared `BlankText` (coordinator rulings INV-Q-4614 (A)
 * and INV-Q-4622 (A); INV-T-4616): an actor that is blank (only ISO controls and the caseId whitespace set) or holds a
 * control character anywhere is refused by `register_platform_pack`, by the BEFORE INSERT trigger and by the
 * `registered_by` CHECK on its own, with nothing written; an actor with a visible character is stored unchanged; a
 * whitespace-only pack field is refused by the shared blank rule. PostgreSQL 16, 17 and 18, through tenancy-jooq's
 * [TenancyProbe] as the runtime login (and as the owner for the CHECK alone).
 */
@Tag("integration")
class RegisteredByActorPostgresIT {
    @ParameterizedTest
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a blank or control actor is refused everywhere and a visible one is stored unchanged`(image: String) {
        val container = ActorPostgres(DockerImageName.parse(image)).withPackRegistryInit()
        container.start()
        try {
            val app = TenancyProbe.connect(container.jdbcUrl, "rulepack_kernel_app", "rulepack_kernel_app_pw")
            val owner = TenancyProbe.connect(container.jdbcUrl, MIGRATE, MIGRATE_PASSWORD)
            val tenant = TenantId(UUID.randomUUID())
            assertTheFunctionAndTheTriggerRefuse(app, tenant)
            assertTheCheckRefusesOnItsOwn(owner, tenant)
            assertEquals(0L, app.inTenant(tenant) { fetchValue(COUNT, "probe-%") })
            assertAVisibleActorIsStoredUnchanged(app, tenant)
            assertAWhitespaceOnlyPackFieldIsRefused(app)
        } finally {
            container.stop()
        }
    }

    private fun assertTheFunctionAndTheTriggerRefuse(
        app: TenancyProbe,
        tenant: TenantId,
    ) {
        val accepted =
            NOT_ACTORS.flatMapIndexed { i, (name, actor) ->
                val viaFunction =
                    refusedForActor {
                        app.inTenant(TenantId.PLATFORM, actor = actor) {
                            register(packId = "probe-fn-$i", version = "5.0.$i")
                        }
                    }
                val viaInsert =
                    refusedForActor {
                        app.inTenant(tenant, actor = actor) {
                            insertRow(tenant, "probe-row-$i", version = "6.0.$i", registeredBy = actor)
                        }
                    }
                listOfNotNull("function $name".takeUnless { viaFunction }, "insert $name".takeUnless { viaInsert })
            }
        assertEquals(emptyList<String>(), accepted, "blank or control actors not refused as unset")
    }

    /** The owner inserts with the trigger disabled in the same (rolled back) transaction: only the CHECK can refuse. */
    private fun assertTheCheckRefusesOnItsOwn(
        owner: TenancyProbe,
        tenant: TenantId,
    ) {
        val accepted =
            NOT_ACTORS.mapIndexedNotNull { i, (name, actor) ->
                val refused =
                    runCatching {
                        owner.inTenant(tenant) {
                            fetchValue(
                                "select rulepack_test.insert_bypassing_the_actor_trigger(?, ?, ?::uuid, ?)",
                                "probe-check-$i",
                                "7.0.$i",
                                tenant.toString(),
                                actor,
                            )
                        }
                    }.exceptionOrNull()
                val byCheck = "rule_pack_version_registered_by_check" in refused?.message.orEmpty()
                "check $name".takeUnless { byCheck }
            }
        assertEquals(emptyList<String>(), accepted, "the registered_by CHECK accepted")
        val enabled =
            owner.unbound {
                fetchValue(
                    "select tgenabled::text from pg_trigger where tgname = 'rule_pack_version_registered_by_insert'",
                )
            }
        assertEquals("O", enabled, "the trigger is enabled again")
    }

    private fun assertAVisibleActorIsStoredUnchanged(
        app: TenancyProbe,
        tenant: TenantId,
    ) {
        VISIBLE_ACTORS.forEachIndexed { i, actor ->
            app.inTenant(TenantId.PLATFORM, actor = actor) { register(packId = "visible-fn-$i", version = "8.0.$i") }
            app.inTenant(tenant, actor = actor) {
                insertRow(tenant, "visible-row-$i", version = "9.0.$i", registeredBy = actor)
            }
            val stored =
                app.inTenant(tenant) {
                    fetchRows(
                        "select registered_by from rulepack_test.rule_pack_version where pack_id in (?, ?)",
                        "visible-fn-$i",
                        "visible-row-$i",
                    ).map { it["registered_by"] }
                }
            assertEquals(listOf(actor, actor), stored)
        }
    }

    private fun assertAWhitespaceOnlyPackFieldIsRefused(app: TenancyProbe) {
        BLANKS.forEachIndexed { i, (_, value) ->
            assertRefusedWith("not blank") {
                app.inTenant(TenantId.PLATFORM, actor = OPERATOR) { register(packId = value, version = "10.0.$i") }
            }
            assertRefusedWith("not blank") {
                app.inTenant(TenantId.PLATFORM, actor = OPERATOR) { register(packId = "p-$i", version = value) }
            }
        }
    }

    /** True when [block] is refused because `app.actor` is unset or not a valid actor. */
    private fun refusedForActor(block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (refused: DataAccessException) {
            "app.actor is not set" in refused.message.orEmpty()
        }

    private companion object {
        const val OPERATOR = "svc:rules-svc"
        const val COUNT = "select count(*) from rulepack_test.rule_pack_version where pack_id like ?"

        /** Whitespace-only values: ISO controls and the caseId whitespace set (INV-Q-4614), alone and mixed. */
        val BLANKS =
            listOf(
                "tab" to "\t",
                "newline" to "\n",
                "CR" to "\r",
                "U+0085" to "\u0085",
                "U+00A0" to "\u00A0",
                "U+1680" to "\u1680",
                "U+2000" to "\u2000",
                "U+200A" to "\u200A",
                "U+2028" to "\u2028",
                "U+2029" to "\u2029",
                "U+202F" to "\u202F",
                "U+205F" to "\u205F",
                "U+3000" to "\u3000",
                "U+FEFF" to "\uFEFF",
                "U+0001" to "\u0001",
                "U+009F" to "\u009F",
                "mix" to " \u00A0\t\u3000\r\n\uFEFF",
            )

        /** Blank values and actors holding a control character next to visible ones (INV-Q-4622). */
        val NOT_ACTORS =
            BLANKS +
                listOf(
                    "a TAB b" to "a\tb",
                    "LF x" to "\nx",
                    "x CR" to "x\r",
                    "x U+0085" to "x\u0085",
                    "ESC [31mx" to "\u001B[31mx",
                    "x U+009B" to "x\u009B",
                )

        /** Actors with a visible character, stored unchanged. */
        val VISIBLE_ACTORS = listOf("x\u200By", "CASE 5521", "a".repeat(254) + "z")
    }
}
