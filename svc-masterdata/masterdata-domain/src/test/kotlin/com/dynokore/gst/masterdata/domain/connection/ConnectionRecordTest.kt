package com.dynokore.gst.masterdata.domain.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class ConnectionRecordTest {
    @Test
    fun `INV-AC-18780 the record has exactly six properties and a seventh fails this list`() {
        val names =
            ConnectionRecord::class.java.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()
        assertEquals(
            setOf("connectionRef", "externalService", "endpoint", "credentialRef", "environmentTag", "ownerService"),
            names,
        )
    }

    @Test
    fun `the detail of a result is a code and raw error text is refused`() {
        ConnectionTestResult(TestClass.UNREACHABLE, "DNS_FAILED")
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionTestResult(TestClass.UNREACHABLE, "java.net.UnknownHostException: host")
        }
        assertThrows(IllegalArgumentException::class.java) { ConnectionTestResult(TestClass.SUCCESS, "a") }
        assertThrows(IllegalArgumentException::class.java) { ConnectionTestResult(TestClass.SUCCESS, "dns_failed") }
    }

    @Test
    fun `the environment tag has exactly the members PRODUCTION and SANDBOX and parses exactly`() {
        assertEquals(listOf("PRODUCTION", "SANDBOX"), EnvironmentTag.entries.map { it.name })
        assertEquals(EnvironmentTag.SANDBOX, EnvironmentTag.parse("SANDBOX"))
        for (text in listOf("WORKSPACE", "sandbox", "SANDBOX ", "")) {
            assertEquals(null, EnvironmentTag.parse(text))
        }
    }
}
