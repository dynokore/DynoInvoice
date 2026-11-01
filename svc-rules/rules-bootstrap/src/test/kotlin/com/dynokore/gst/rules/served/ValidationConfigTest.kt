package com.dynokore.gst.rules.served

import com.dynokore.gst.rules.adapters.web.PacksController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

private const val PROPERTY = "hibernate.validator.allow_parallel_method_parameter_constraint"

/**
 * PacksController overrides one operation of two generated interfaces: Hibernate Validator needs validation.xml.
 * The validator API is on the runtime classpath only (no new test dependency), so it is driven by reflection.
 */
class ValidationConfigTest {
    private fun metadata(parallelAllowed: Boolean) {
        try {
            val configuration =
                Class
                    .forName("jakarta.validation.Validation")
                    .getMethod("byDefaultProvider")
                    .invoke(null)
                    .let { it.javaClass.getMethod("configure").invoke(it) }
            val type = Class.forName("jakarta.validation.Configuration")
            type.getMethod("ignoreXmlConfiguration").invoke(configuration)
            if (parallelAllowed) type.getMethod("addProperty", String::class.java, String::class.java).invoke(configuration, PROPERTY, "true")
            val factory = type.getMethod("buildValidatorFactory").invoke(configuration)
            val validator = Class.forName("jakarta.validation.ValidatorFactory").getMethod("getValidator").invoke(factory)
            Class
                .forName("jakarta.validation.Validator")
                .getMethod("getConstraintsForClass", Class::class.java)
                .invoke(validator, PacksController::class.java)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `probe - without the setting validation xml carries the controller is refused with HV000152`() {
        shouldThrow<Exception> { metadata(parallelAllowed = false) }.message shouldContain "HV000152"
    }

    @Test
    fun `with the setting the controller metadata builds`() {
        metadata(parallelAllowed = true)
    }

    @Test
    fun `the validation config file carries exactly that setting`() {
        val xml = Files.readString(Path.of("src/main/resources/META-INF/validation.xml"))
        xml shouldContain "<property name=\"$PROPERTY\">true</property>"
    }
}
