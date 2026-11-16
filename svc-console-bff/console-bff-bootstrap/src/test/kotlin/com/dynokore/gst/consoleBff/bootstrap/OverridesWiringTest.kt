package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.application.override.OverrideException
import com.dynokore.gst.consoleBff.contract.api.ExceptionsApi
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path

/** INV-AC-21206 in the composed application (TPL-18): one controller per generated interface; the context starts. */
class OverridesWiringTest {
    /** A look-alike: a RestController that implements no generated interface. */
    @RestController
    class LookAlike

    /** A second implementation of the generated interface of the exceptions tag. */
    @RequestMapping
    class SecondExceptions : ExceptionsApi

    private val apiPackage = "com.dynokore.gst.consoleBff.contract.api"

    private fun generatedInterfaces(): List<Class<*>> {
        val scanner =
            object : ClassPathScanningCandidateComponentProvider(false) {
                override fun isCandidateComponent(definition: AnnotatedBeanDefinition) =
                    definition.metadata.isInterface
            }
        scanner.addIncludeFilter(AssignableTypeFilter(Any::class.java))
        return scanner
            .findCandidateComponents(apiPackage)
            .map { Class.forName(it.beanClassName) }
            .filter { it.packageName == apiPackage }
    }

    private fun controllers(context: AssertableApplicationContext): List<Any> =
        context.getBeansWithAnnotation(RestController::class.java).values.toList()

    private fun targetClass(bean: Any): Class<*> = AopUtils.getTargetClass(bean)

    private fun implementers(
        beans: List<Any>,
        api: Class<*>,
    ) = beans.count { api.isAssignableFrom(targetClass(it)) }

    @Test
    fun `INV-AC-21206 the offline context has one OverrideException and the controllers build one MVC mapping`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(OverrideException::class.java).size shouldBe 1
            context.getBeansOfType(OverridesProblemMapper::class.java).size shouldBe 1
            val beans = controllers(context)
            (beans.size >= 2) shouldBe true
            MockMvcBuilders.standaloneSetup(*beans.toTypedArray()).build()
        }
    }

    @Test
    fun `INV-AC-21206 at most one RestController implements each generated interface and exactly one ExceptionsApi`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            val beans = controllers(context)
            val apis = generatedInterfaces()
            (ExceptionsApi::class.java in apis) shouldBe true
            for (api in apis) {
                (implementers(beans, api) <= 1) shouldBe true
            }
            implementers(beans, ExceptionsApi::class.java) shouldBe 1
            // the look-alike implements no generated interface and does not count
            implementers(beans + LookAlike(), ExceptionsApi::class.java) shouldBe 1
            apis.all { implementers(listOf(LookAlike()), it) == 0 } shouldBe true
            // a second implementer is counted
            implementers(beans + SecondExceptions(), ExceptionsApi::class.java) shouldBe 2
        }
    }

    @Test
    fun `INV-AC-21206 a second object implementing ExceptionsApi gives Ambiguous mapping`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            val failure =
                runCatching {
                    MockMvcBuilders.standaloneSetup(*(controllers(context) + SecondExceptions()).toTypedArray()).build()
                }.exceptionOrNull()
            failure?.message.orEmpty() shouldContain "Ambiguous mapping"
        }
    }
}
