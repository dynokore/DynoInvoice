package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.VisitListenerProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The PostgreSQL majors the layer is proved on (STK-15). */
object Images {
    const val PG16 = "postgres:16"
    const val PG17 = "postgres:17"
    const val PG18 = "postgres:18"

    @JvmField
    val ALL: List<String> = listOf(PG16, PG17, PG18)
}

/** One started database per image, shared by the test classes and stopped when the JVM exits. */
internal object Databases {
    private val started = ConcurrentHashMap<String, TenancyJooqFixture>()

    fun of(image: String): TenancyJooqFixture = started.computeIfAbsent(image) {
        TenancyJooqFixture(it, "classpath:db/tenancy-jooq-test").also { fixture ->
            Runtime.getRuntime().addShutdownHook(Thread { fixture.close() })
        }
    }
}

/**
 * The mutation switch of [MutationTest]: when set, [dsl] builds the DSLContext without tenancy-jooq's VisitListener
 * and with no ExecuteListener, the way a service would run if the layer were removed.
 */
object Mutation {
    @Volatile
    var withoutTenancyListeners: Boolean = false
}

/** The two tenants of the fixture data. */
object Tenants {
    val A: TenantId = TenantId(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"))
    val B: TenantId = TenantId(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002"))
}

/** The configured DSLContext of [fixture] (or, under [Mutation], the same DSLContext without the tenancy listeners). */
internal fun dsl(fixture: TenancyJooqFixture): DSLContext {
    val configuration = fixture.configuration(TEST_TABLES)
    return if (Mutation.withoutTenancyListeners) {
        configuration
            .derive(*arrayOf<VisitListenerProvider>())
            .derive(*arrayOf<org.jooq.ExecuteListenerProvider>())
            .dsl()
    } else {
        configuration.dsl()
    }
}

/** The configured DSLContext of [fixture] with the VisitListener removed: the ExecuteListener alone (INV-Q-4146). */
internal fun dslWithoutInjector(fixture: TenancyJooqFixture): DSLContext {
    val configuration = fixture.configuration(TEST_TABLES)
    return configuration.derive(*arrayOf<VisitListenerProvider>()).dsl()
}

/** Runs [block] in a transaction of the runtime login bound to [tenant]. */
internal fun <T> TenancyJooqFixture.asTenant(
    tenant: TenantId,
    block: () -> T,
): T = tenants.bind(tenant, "tenancy-jooq-test") { transactions.inTransaction(block) }

/** Runs [block] in a transaction bound to the platform tenant. */
internal fun <T> TenancyJooqFixture.asPlatform(block: () -> T): T = platform.bindPlatform("tenancy-jooq-test") {
    transactions.inTransaction(block)
}
