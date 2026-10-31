package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import io.kotest.matchers.shouldBe
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.sql.SQLException

/** INV-Q-13905 A: a replay is recognised by SQLState and the structured constraint name, never by message text. */
class IntakeKeyViolationTest {
    private fun violation(
        sqlState: String,
        constraint: String,
        message: String = "duplicate key value violates unique constraint",
    ): DataAccessException {
        val server = ServerErrorMessage("SERROR\u0000C$sqlState\u0000M$message\u0000n$constraint\u0000")
        return DataAccessException("jOOQ", PSQLException(server))
    }

    @Test
    fun `a localized message with the structured intake constraint is a replay`() {
        JooqAggregateStore.isIntakeKeyViolation(
            violation("23505", "aggregate_intake_key", "doppelter Schluesselwert verletzt Unique-Constraint"),
        ) shouldBe true
    }

    @Test
    fun `the text aggregate_intake_key in the message with the document number constraint is not a replay`() {
        JooqAggregateStore.isIntakeKeyViolation(
            violation("23505", "aggregate_document_number_key", "violates \"aggregate_intake_key\""),
        ) shouldBe false
    }

    @Test
    fun `SQLState 23505 with no PSQLException in the chain is not a replay`() {
        JooqAggregateStore.isIntakeKeyViolation(
            DataAccessException("aggregate_intake_key", SQLException("aggregate_intake_key", "23505")),
        ) shouldBe false
    }

    @Test
    fun `SQLState 23503 with the intake constraint is not a replay`() {
        JooqAggregateStore.isIntakeKeyViolation(violation("23503", "aggregate_intake_key")) shouldBe false
    }

    @Test
    fun `look-alike constraint names are not a replay`() {
        JooqAggregateStore.isIntakeKeyViolation(violation("23505", "AGGREGATE_INTAKE_KEY")) shouldBe false
        JooqAggregateStore.isIntakeKeyViolation(violation("23505", "aggregate_intake_key_2")) shouldBe false
    }
}
