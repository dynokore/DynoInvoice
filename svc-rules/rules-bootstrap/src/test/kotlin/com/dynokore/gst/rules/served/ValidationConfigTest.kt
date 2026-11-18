package com.dynokore.gst.rules.served

import com.dynokore.gst.rules.adapters.web.DryRunsController
import com.dynokore.gst.rules.adapters.web.EvaluationController
import com.dynokore.gst.rules.adapters.web.PacksController
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

private const val PROPERTY = "hibernate.validator.allow_parallel_method_parameter_constraint"
private val CONTROLLERS =
    listOf(PacksController::class.java, EvaluationController::class.java, DryRunsController::class.java)

/**
 * The parallel-constraint rule of Hibernate Validator is on: no `validation.xml` relaxes it, and the metadata of every
 * `@RestController` of rules-adapters builds without the property (INV-AC-25015). The validator API is on the runtime
 * classpath only (no new test dependency), so it is driven by reflection.
 */
class ValidationConfigTest {
    private fun metadata(type: Class<*>) {
        try {
            val bootstrap =
                Class
                    .forName("jakarta.validation.Validation")
                    .getMethod("byDefaultProvider")
                    .invoke(null)
            val configuration =
                Class
                    .forName("jakarta.validation.bootstrap.GenericBootstrap")
                    .getMethod("configure")
                    .invoke(bootstrap)
            val configurationType = Class.forName("jakarta.validation.Configuration")
            configurationType.getMethod("ignoreXmlConfiguration").invoke(configuration)
            val factory = configurationType.getMethod("buildValidatorFactory").invoke(configuration)
            val validator =
                Class
                    .forName("jakarta.validation.ValidatorFactory")
                    .getMethod("getValidator")
                    .invoke(factory)
            Class
                .forName("jakarta.validation.Validator")
                .getMethod("getConstraintsForClass", Class::class.java)
                .invoke(validator, type)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `INV-AC-25015 the metadata of every RestController of rules-adapters builds with the rule on`() {
        CONTROLLERS.forEach { metadata(it) }
    }

    @Test
    fun `INV-AC-25015 validation xml does not exist`() {
        Files.exists(Path.of("src/main/resources/META-INF/validation.xml")) shouldBe false
    }

    @Test
    fun `INV-AC-25015 no source or resource of rules-adapters and rules-bootstrap sets the property`() {
        val self = Path.of("src/test/kotlin/com/dynokore/gst/rules/served/ValidationConfigTest.kt").toAbsolutePath()
        val hits =
            listOf(Path.of("src"), Path.of("../rules-adapters/src"))
                .filter { Files.isDirectory(it) }
                .flatMap { root -> Files.walk(root).use { stream -> stream.filter(Files::isRegularFile).toList() } }
                .filter { it.toAbsolutePath() != self }
                .filter { runCatching { Files.readString(it) }.getOrDefault("").contains(PROPERTY) }
        hits shouldBe emptyList()
    }
}
