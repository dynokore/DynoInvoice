package com.dynokore.gst.masterdata.adapters.registry

import com.dynokore.gst.masterdata.domain.request.SendMethod
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-T-18221 (INV-REQ-1291, VP-30): the policy class reads VP-30 through `PolicyReader.resolve` at tenant scope on the
 * business date, returns the stored option key as it is, and an option the service does not know is refused, never
 * guessed.
 */
class PolicySendMethodReaderTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)

    private val tenants: TenantContext =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantContext::class.java)) { _, method, _ ->
            check(method.name.startsWith("current")) { "unexpected call ${method.name}" }
            tenant.value
        } as TenantContext

    private fun policy(
        key: String,
        seen: MutableList<Triple<VariationPointKey, PolicyScope, LocalDate>> = mutableListOf(),
    ): PolicySendMethod {
        val reader =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PolicyReader::class.java)) { _, method, args ->
                check(method.name.startsWith("resolve")) { "unexpected call ${method.name}" }
                val vp = args[0].let { if (it is String) VariationPointKey(it) else it as VariationPointKey }
                val at = args[2] as LocalDate
                seen += Triple(vp, args[1] as PolicyScope, at)
                ResolvedSetting(vp, at, listOf(SelectedOption(key, null)), PolicySource.CatalogueDefault)
            } as PolicyReader
        return PolicySendMethod(reader, tenants, clock)
    }

    @Test
    fun `INV-AC-18220 VP-30 is read at tenant scope on the business date and the key is returned as stored`() {
        val seen = mutableListOf<Triple<VariationPointKey, PolicyScope, LocalDate>>()
        assertThat(policy("BOTH", seen).option()).isEqualTo("BOTH")
        assertThat(seen.single().first).isEqualTo(VariationPointKey("VP-30"))
        assertThat(seen.single().second.toString()).startsWith("TENANT:${tenant.value} ")
        assertThat(seen.single().third).isEqualTo(LocalDate.of(2026, 11, 10))
    }

    @Test
    fun `INV-AC-18220 each catalogue option of VP-30 is returned and known to the send`() {
        listOf("SIGNED_LINK", "CSV_ONLY", "BOTH", "OFF").forEach {
            assertThat(SendMethod.parse(policy(it).option())).describedAs(it).isEqualTo(SendMethod.valueOf(it))
        }
    }

    @Test
    fun `INV-AC-18220 an unknown or look-alike VP-30 option is refused, not guessed`() {
        listOf("EMAIL", "signed_link", "SIGNED_LINK ", " BOTH", "BOTH,OFF", "SIGNED_LINK2", "").forEach {
            assertThat(SendMethod.parse(policy(it).option())).describedAs("'$it'").isNull()
        }
    }

    @Test
    fun `INV-AC-18220 a VP-30 resolution that selects no option or two options fails`() {
        val none =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PolicyReader::class.java)) { _, _, args ->
                val vp = args[0].let { if (it is String) VariationPointKey(it) else it as VariationPointKey }
                ResolvedSetting(vp, args[2] as LocalDate, emptyList(), PolicySource.CatalogueDefault)
            } as PolicyReader
        assertThatThrownBy {
            PolicySendMethod(
                none,
                tenants,
                clock,
            ).option()
        }.isInstanceOf(RuntimeException::class.java)
    }
}
