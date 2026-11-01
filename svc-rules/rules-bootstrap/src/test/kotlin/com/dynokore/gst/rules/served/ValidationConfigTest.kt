package com.dynokore.gst.rules.served

import com.dynokore.gst.rules.adapters.web.PacksController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import jakarta.validation.Validation
import jakarta.validation.ValidationException
import org.hibernate.validator.HibernateValidator
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** PacksController overrides one operation of two generated interfaces: Hibernate Validator needs validation.xml. */
class ValidationConfigTest {
    private fun metadata(parallelAllowed: Boolean) =
        Validation
            .byProvider(HibernateValidator::class.java)
            .configure()
            .ignoreXmlConfiguration()
            .apply { if (parallelAllowed) allowParallelMethodsDefineParameterConstraints(true) }
            .buildValidatorFactory()
            .validator
            .getConstraintsForClass(PacksController::class.java)

    @Test
    fun `probe - without the setting validation.xml carries the controller is refused with HV000152`() {
        shouldThrow<ValidationException> { metadata(parallelAllowed = false) }.message shouldContain "HV000152"
    }

    @Test
    fun `with the setting the controller metadata builds`() {
        metadata(parallelAllowed = true)
    }

    @Test
    fun `validation.xml carries exactly that setting`() {
        val xml = Files.readString(Path.of("src/main/resources/META-INF/validation.xml"))
        xml shouldContain "<property name=\"hibernate.validator.allow_parallel_method_parameter_constraint\">true</property>"
    }
}
