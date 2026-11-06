package com.dynokore.platform.serviceruntime.policy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLDecoder
import java.util.jar.JarFile

/**
 * INV-T-10128 (INV-Q-10107 C): the public members of `com.dynokore.platform.serviceruntime.policy`, compared with the
 * expected names by equality. `PolicyRoleResolver` and its `rolesOf` no longer exist (no code compares a role).
 */
class PolicyPackageMembersTest {
    private val pkg = "com.dynokore.platform.serviceruntime.policy"

    private fun classNames(): Set<String> {
        val path = pkg.replace('.', '/')
        val urls =
            Thread
                .currentThread()
                .contextClassLoader
                .getResources(path)
                .toList()
        return urls
            .flatMap { url ->
                when (url.protocol) {
                    "file" -> {
                        File(URLDecoder.decode(url.path, Charsets.UTF_8)).list().orEmpty().toList()
                    }

                    "jar" -> {
                        val jar = url.path.substringAfter("file:").substringBefore("!")
                        JarFile(URLDecoder.decode(jar, Charsets.UTF_8)).use { file ->
                            file
                                .entries()
                                .toList()
                                .map { it.name }
                                .filter { it.startsWith("$path/") }
                                .map { it.removePrefix("$path/") }
                        }
                    }

                    else -> {
                        error("unexpected protocol ${url.protocol}")
                    }
                }
            }.filter { it.endsWith(".class") && !it.endsWith("Kt.class") && !it.startsWith("PolicyPackageMembersTest") }
            .map { it.removeSuffix(".class") }
            .toSet()
    }

    @Test
    fun `the public classes of the policy package are exactly the expected names`() {
        classNames() shouldBe setOf("RequestBoundSetterPermission", "RequestBoundSetterPermission\$Companion")
    }

    @Test
    fun `PolicyRoleResolver no longer exists`() {
        runCatching { Class.forName("$pkg.PolicyRoleResolver") }.isFailure shouldBe true
    }

    @Test
    fun `no class of the policy package or the policy servlet declares rolesOf`() {
        val names = classNames().map { Class.forName("$pkg.$it") }
        names.flatMap { c -> c.methods.map { it.name } }.contains("rolesOf") shouldBe false
        val servlet = Class.forName("com.dynokore.platform.serviceruntime.http.PolicyCommandServlet")
        servlet.declaredMethods.map { it.name }.contains("rolesOf") shouldBe false
    }
}
