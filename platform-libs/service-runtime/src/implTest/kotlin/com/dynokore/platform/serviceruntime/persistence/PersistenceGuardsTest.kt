package com.dynokore.platform.serviceruntime.persistence

import com.dynokore.platform.serviceruntime.composition.PersistenceProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/** INV-T-4224, ruling INV-Q-6548: the `inv.persistence.append-only-tables` declaration. */
class PersistenceGuardsTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PersistenceProperties::class)
    class Properties

    private fun startup(vararg entries: String) =
        ApplicationContextRunner()
            .withUserConfiguration(Properties::class.java)
            .withPropertyValues(
                *entries.mapIndexed { i, e -> "inv.persistence.append-only-tables[$i]=$e" }.toTypedArray(),
            )

    private fun failureOf(vararg entries: String): String {
        var failure: Throwable? = null
        startup(*entries).run { failure = it.startupFailure }
        generateSequence(failure) { it.cause }.last().message.shouldNotBeNull()
        return generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ledger",
            "Fixture.Ledger",
            "fixture..ledger",
            "fixture.ledger.extra",
            "fixture.",
            ".ledger",
            "fixture.led-ger",
        ],
    )
    fun `a malformed entry fails startup and the message names it`(entry: String) {
        failureOf(entry) shouldContain "'$entry'"
    }

    @Test
    fun `a blank entry or one with stray spaces fails and the message names it`() {
        listOf("", " ", "fixture. ledger", " fixture.ledger", "fixture.ledger ").forEach { entry ->
            shouldThrow<IllegalArgumentException> { PersistenceGuards.requireAppendOnlyNames(listOf(entry)) }
                .message shouldContain "'$entry'"
        }
    }

    @Test
    fun `a blank entry fails startup`() {
        failureOf("") shouldContain "blank"
    }

    @Test
    fun `a duplicate entry fails startup`() {
        failureOf("fixture.ledger", "fixture.ledger") shouldContain "repeated"
    }

    @Test
    fun `well-formed distinct entries start`() {
        startup("fixture.ledger", "other.ledger").run {
            it.startupFailure.shouldBeNull()
            it.getBean(PersistenceProperties::class.java).appendOnlyTables shouldBe
                listOf("fixture.ledger", "other.ledger")
        }
    }

    private val guards = PersistenceGuards(listOf("audit_entry"), listOf("fixture.ledger"))

    private fun table(
        schema: String,
        name: String,
    ) = DSL.table(DSL.name(schema, name))

    @Test
    fun `a declared table is refused by schema-qualified equality`() {
        guards.storeRefusal(table("fixture", "ledger")).shouldNotBeNull() shouldContain "append-only"
    }

    @ParameterizedTest
    @ValueSource(strings = ["other.ledger", "fixture.ledger_2", "fixture.ledgers", "Fixture.ledger", "fixture.Ledger"])
    fun `a look-alike does not match`(qualified: String) {
        guards.storeRefusal(table(qualified.substringBefore('.'), qualified.substringAfter('.'))).shouldBeNull()
    }

    @Test
    fun `a protected table name is refused in any schema and a look-alike is not`() {
        guards.storeRefusal(table("any", "audit_entry")).shouldNotBeNull() shouldContain "protected"
        guards.storeRefusal(table("any", "audit_entries")).shouldBeNull()
        shouldThrow<IllegalArgumentException> { PersistenceGuards(emptyList(), listOf("ledger")) }
    }
}
