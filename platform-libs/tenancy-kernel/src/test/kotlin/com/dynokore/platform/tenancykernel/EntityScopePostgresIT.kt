package com.dynokore.platform.tenancykernel

import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * INV-T-7520 (INV-REQ-866; INV-Q-7453): the entity clause of [TenantRlsMigration.ddl] on PostgreSQL 16, 17 and 18, each
 * version with its own container and sample. The table `ent_probe.item` holds rows of two entities E1 and E2 of tenant
 * T1 and a row with the same entity UUID E1 under tenant T2. The subject is the rows a query written without any entity
 * condition returns (`select` of all columns through jOOQ).
 */
@Tag("integration")
class EntityScopePostgresIT {
    private val t1 = TenantId(UUID.randomUUID())
    private val t2 = TenantId(UUID.randomUUID())
    private val e1 = UUID.randomUUID()
    private val e2 = UUID.randomUUID()
    private val keyCurrent = SignedBinding.key(5)
    private val keyPrevious = SignedBinding.key(6)
    private val keyOlder = SignedBinding.key(7)
    private val keyOther = SignedBinding.key(8)
    private val actor = "user-1"
    private val rowT1E1 get() = "${t1.value}/$e1"
    private val rowT1E2 get() = "${t1.value}/$e2"
    private val rowT2E1 get() = "${t2.value}/$e1"
    private val future = System.currentTimeMillis() / 1000 + 600

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-1912 case 1 and case 2 and case 3 a query without an entity condition returns only the scope`(
        image: String,
    ) = withDatabase(image) { sample ->
        // Case 1: binding set {E1}: only E1 rows of the bound tenant.
        assertEquals(setOf(rowT1E1), sample.rows(t1, setOf(e1)), "case 1")
        // Case 2: binding set {E1, E2}: the rows of both entities of the bound tenant.
        assertEquals(setOf(rowT1E1, rowT1E2), sample.rows(t1, setOf(e1, e2)), "case 2")
        // Case 3: eight bindings return no row and no error.
        val valid = sample.value(t1, setOf(e1, e2))
        val fields = valid.split('.')
        val signedE1 = sample.value(t1, setOf(e1)).split('.')
        val expired = sample.raw("${t1.value}.${b64(actor)}.1.$e1", keyCurrent)
        val case3 =
            linkedMapOf<String, String?>(
                "the empty set" to sample.value(t1, emptySet()),
                "a four-field value with no set" to sample.value(t1, null),
                "a missing value" to null,
                "an expired value" to expired,
                "a MAC of another key" to sample.rawSet(t1, "$e1", keyOther),
                "a set changed after signing (E2 added)" to
                    (signedE1.take(3) + "$e1,$e2" + signedE1[4]).joinToString("."),
                "a set with E1 removed and the signature kept" to
                    (fields.take(3) + "$e2" + fields[4]).joinToString("."),
                "a malformed entity element" to sample.rawSet(t1, "$e1,not-a-uuid", keyCurrent),
            )
        assertEquals(8, case3.size)
        for ((name, value) in case3) assertEquals(emptySet<String>(), sample.rowsWith(t1, value), name)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `entity set rotation accepts the previous key and refuses an older one`(image: String) =
        withDatabase(image) { sample ->
            val signedPrevious = sample.rawSet(t1, "$e1", keyPrevious)
            val signedOlder = sample.rawSet(t1, "$e1", keyOlder)
            assertEquals(setOf(rowT1E1), sample.rowsWith(t1, sample.rawSet(t1, "$e1", keyCurrent)), "current")
            assertEquals(setOf(rowT1E1), sample.rowsWith(t1, signedPrevious), "the previous key is accepted")
            assertEquals(emptySet<String>(), sample.rowsWith(t1, signedOlder), "a key older than the previous one")
            sample.writeKeys(keyOther, keyCurrent)
            assertEquals(emptySet<String>(), sample.rowsWith(t1, signedPrevious), "rotated out")
        }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the same entity UUID in upper case in a signed set matches no row`(image: String) =
        withDatabase(image) { sample ->
            val upper = e1.toString().uppercase()
            assertEquals(
                emptySet<String>(),
                sample.rowsWith(t1, sample.rawSet(t1, upper, keyCurrent)),
                "upper-case E1",
            )
            assertEquals(
                emptySet<String>(),
                sample.rowsWith(t1, sample.rawSet(t1, "$e1,${e2.toString().uppercase()}", keyCurrent)),
                "upper-case E2 beside E1",
            )
        }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a binding of the first tenant holding E1 does not reach the E1 row of the second tenant`(image: String) =
        withDatabase(image) { sample ->
            val rows = sample.rows(t1, setOf(e1))
            assertTrue(rowT2E1 !in rows, "the second tenant's E1 row")
            assertEquals(setOf(rowT2E1), sample.rows(t2, setOf(e1)))
        }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a login other than the runtime login cannot call bound_entities`(image: String) =
        withDatabase(image) { sample ->
            val denied =
                assertThrows(DataAccessException::class.java) {
                    sample.inTx(sample.other, t1, null) { it.fetch("select $SCHEMA.bound_entities()") }
                }
            assertTrue(denied.message!!.contains("permission denied for function"), denied.message)
        }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a same-named function in a schema the caller controls is not resolved`(image: String) =
        withDatabase(image) { sample ->
            val path = DSL.field("set_config('search_path', 'evil, public', true)")
            val control =
                sample.inTx(sample.app, t1, null) {
                    it.select(path).fetch()
                    it.fetchOne("select bound_entities()")!!.get(0)
                }
            assertEquals(listOf(EVIL_ENTITY), (control as Array<*>).toList(), "the caller's path finds its look-alike")
            val signed = sample.rawSet(t1, "$e1", keyCurrent)
            val real =
                sample.inTx(sample.app, t1, signed) {
                    it.select(path).fetch()
                    it.fetchOne("select $SCHEMA.bound_entities()")!!.get(0)
                }
            assertEquals(listOf(e1), (real as Array<*>).toList(), "the function keeps its own search_path")
            val viaPolicy =
                sample.inTx(sample.app, t1, signed) {
                    it.select(path).fetch()
                    it.itemRows()
                }
            assertEquals(setOf(rowT1E1), viaPolicy)
        }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `bound_entities answers the signed set, NULL for the four-field form, and the old functions keep working`(
        image: String,
    ) = withDatabase(image) { sample ->
        assertEquals(setOf(e1, e2), sample.boundEntities(sample.value(t1, setOf(e2, e1)))?.toSet())
        assertEquals(emptyList<UUID>(), sample.boundEntities(sample.value(t1, emptySet())))
        assertNull(sample.boundEntities(sample.value(t1, null)), "four-field form")
        assertNull(sample.boundEntities(null), "missing")
        assertNull(sample.boundEntities(""), "empty")
        // bound_tenant() and bound_actor() verify both forms
        for (value in listOf(sample.value(t1, null), sample.value(t1, setOf(e1)), sample.value(t1, emptySet()))) {
            assertEquals(t1.value to actor, sample.boundTenantActor(value), value)
        }
        // a five-field value with a malformed set is refused by all three
        val bad = sample.rawSet(t1, "$e1,", keyCurrent)
        assertEquals(null to null, sample.boundTenantActor(bad))
        assertNull(sample.boundEntities(bad))
    }

    private fun DSLContext.itemRows(): Set<String> =
        select()
            .from(ITEM)
            .fetch()
            .map { it.rowText() }
            .toSet()

    private fun Record.rowText(): String = "${get("tenant_id")}/${get("entity_id")}"

    private fun withDatabase(
        image: String,
        block: (Sample) -> Unit,
    ) {
        PostgresProbeFixture(image, initSql = provisioning()).use { fixture ->
            fun pool(
                user: String,
                password: String,
            ) = TestPoolFixture(fixture.jdbcUrl(), user, password, maximumPoolSize = 1)
            pool(SYS, SYS_PW).use { sys ->
                pool(PostgresProbeFixture.APP, PostgresProbeFixture.APP_PASSWORD).use { app ->
                    pool(OTHER, OTHER_PW).use { other ->
                        val sample = Sample(sys, app, other)
                        sample.writeKeys(keyCurrent, keyPrevious)
                        sample.seed()
                        block(sample)
                    }
                }
            }
        }
    }

    /** The sample table, its rows and the helpers that read it under a given binding value. */
    private inner class Sample(
        val sys: TestPoolFixture,
        val app: TestPoolFixture,
        val other: TestPoolFixture,
    ) {
        fun writeKeys(
            current: ByteArray,
            previous: ByteArray?,
        ) = inTx(sys, t1, null) {
            BindingKeyMigration.writeKeys(it, SCHEMA, SignedBinding.keys(current, previous))
        }

        /** Three rows, inserted through the runner as the runtime login (WITH CHECK allows its own scope). */
        fun seed() {
            insert(t1, setOf(e1, e2), e1, ROW_IDS[0])
            insert(t1, setOf(e1, e2), e2, ROW_IDS[1])
            insert(t2, setOf(e1), e1, ROW_IDS[2])
        }

        private fun insert(
            tenant: TenantId,
            set: Set<UUID>,
            entity: UUID,
            id: UUID,
        ) = inTx(app, tenant, null, set) {
            it
                .insertInto(ITEM)
                .columns(DSL.field("id"), DSL.field("tenant_id"), DSL.field("entity_id"))
                .values(id, tenant.value, entity)
                .execute()
        }

        /** The value the runner signs for [tenant] and [actor]: with [set], or the four-field form when null. */
        fun value(
            tenant: TenantId,
            set: Set<UUID>?,
        ): String = BindingSignature.sign(keyCurrent, tenant, actor, future, set)

        /** A value whose MAC is right over [text] exactly as given. */
        fun raw(
            text: String,
            key: ByteArray,
        ): String {
            val mac =
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal(text.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
                }
            return "$text.$mac"
        }

        /** A five-field value with [entityField] as given, signed with [key]. */
        fun rawSet(
            tenant: TenantId,
            entityField: String,
            key: ByteArray,
        ): String = raw("${tenant.value}.${b64(actor)}.$future.$entityField", key)

        /** The rows tenant [tenant] sees under the runner's own signed set [set]. */
        fun rows(
            tenant: TenantId,
            set: Set<UUID>,
        ): Set<String> = inTx(app, tenant, null, set) { it.itemRows() }

        /** The rows tenant [tenant] sees after `app.binding_sig` is replaced by [value] (null: reset). */
        fun rowsWith(
            tenant: TenantId,
            value: String?,
        ): Set<String> = inTx(app, tenant, value) { it.itemRows() }

        fun boundEntities(value: String?): List<UUID>? =
            inTx(app, t1, value) {
                (it.fetchOne("select $SCHEMA.bound_entities()")!!.get(0) as Array<*>?)?.map { e -> e as UUID }
            }

        fun boundTenantActor(value: String?): Pair<UUID?, String?> =
            inTx(app, t1, value) {
                val r = it.fetchOne("select $SCHEMA.bound_tenant(), $SCHEMA.bound_actor()")!!
                (r.get(0) as UUID?) to (r.get(1) as String?)
            }

        /** Runs [block] in a transaction of [pool] bound to [tenant]; [value] then replaces `app.binding_sig`. */
        fun <T> inTx(
            pool: TestPoolFixture,
            tenant: TenantId,
            value: String?,
            set: Set<UUID>? = null,
            block: (DSLContext) -> T,
        ): T {
            val tenants = TenantContextImpl()
            val source = set?.let { s -> BoundEntitySource { _, _ -> s } }
            val tx = pool.runner(tenants, keys = SignedBinding.keys(keyCurrent), entities = source)
            return tenants.bind(tenant, actor) {
                tx.inTransaction {
                    val dsl = DSL.using(tx.require())
                    if (value != null) {
                        val setting = DSL.field("set_config('app.binding_sig', {0}, true)", DSL.`val`(value))
                        dsl.select(setting).fetch()
                    } else if (set == null) {
                        dsl.execute("reset app.binding_sig")
                    }
                    block(dsl)
                }
            }
        }
    }

    private fun b64(text: String) =
        java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(text.toByteArray())

    private fun provisioning(): String =
        """
        RESET ROLE;
        CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;
        CREATE ROLE $SYS LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '$SYS_PW';
        CREATE ROLE $OTHER LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '$OTHER_PW';
        CREATE SCHEMA $SCHEMA AUTHORIZATION $SYS;
        GRANT USAGE ON SCHEMA $SCHEMA TO ${PostgresProbeFixture.APP}, $OTHER;
        CREATE SCHEMA evil AUTHORIZATION ${PostgresProbeFixture.APP};
        CREATE FUNCTION evil.bound_entities() RETURNS uuid[] LANGUAGE sql
            AS ${'$'}${'$'}SELECT ARRAY['$EVIL_ENTITY'::uuid]${'$'}${'$'};
        GRANT USAGE ON SCHEMA evil TO ${PostgresProbeFixture.APP};
        SET ROLE $SYS;
        ${BindingKeyMigration.ddl(SCHEMA, SYS, PostgresProbeFixture.APP)}
        CREATE TABLE $SCHEMA.item (
            id        uuid PRIMARY KEY,
            tenant_id uuid NOT NULL,
            entity_id uuid NOT NULL
        );
        ${TenantRlsMigration.ddl(SCHEMA, "item", entityColumn = "entity_id")}
        GRANT SELECT, INSERT ON $SCHEMA.item TO ${PostgresProbeFixture.APP};
        RESET ROLE;
        SET ROLE ${PostgresProbeFixture.OWNER};
        """.trimIndent()

    private companion object {
        const val SCHEMA = "ent_probe"
        const val SYS = "ent_probe_sys"
        const val SYS_PW = "ent_probe_sys_pw"
        const val OTHER = "ent_probe_other"
        const val OTHER_PW = "ent_probe_other_pw"
        val ITEM = DSL.table(DSL.name(SCHEMA, "item"))
        val ROW_IDS = List(3) { UUID.randomUUID() }
        val EVIL_ENTITY: UUID = UUID.fromString("deadbeef-dead-beef-dead-beefdeadbeef")
    }
}
