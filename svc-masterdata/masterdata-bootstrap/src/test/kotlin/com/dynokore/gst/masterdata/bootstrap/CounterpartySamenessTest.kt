package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.platform.productruntime.ProductEventTypes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `sameTenant` and `sameGroup` from the service's own structure table, fed by the structure events (INV-Q-8427 A). */
class CounterpartySamenessTest {
    private val both = true to true
    private val tenantOnly = true to false
    private val neither = false to false

    @Test
    fun `INV-AC-1823 INV-AC-760 resolution sets sameTenant and sameGroup from the structure events`() =
        runCounterparty { env ->
            val tag = unique("S").takeLast(6)
            val group = "GRP-$tag"
            val x = "X-$tag"
            val y = "Y-$tag"
            val tenantNode = "TEN-$tag"
            env.structure(tenantNode, "TENANT", null, null)
            env.structure(group, "GROUP", tenantNode, null, gstn = "M9-$tag")
            env.structure(x, "ENTITY", group, "UX-$tag")
            env.structure(y, "ENTITY", group, "UY-$tag", type = ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED)
            env.structure("Z-$tag", "ENTITY", tenantNode, "UZ-$tag")
            env.triple(x)
            listOf("Y" to "UY-$tag", "Z" to "UZ-$tag", "N" to "UY-$tag".dropLast(1) + "9").forEach { (name, uen) ->
                env.write("P-$name-$tag", env.request("UEN" to uen, name = name, address = ADDRESS))
            }

            fun flags(uen: String) = env.resolve(x, "UEN", uen).let { it.sameTenant to it.sameGroup }
            assertThat(flags("UY-$tag")).isEqualTo(both)
            val nested = env.resolve(x, "UEN", "UY-$tag").counterparty!!
            assertThat(nested.sameTenant to nested.sameGroup).isEqualTo(both)
            assertThat(flags("UZ-$tag")).isEqualTo(tenantOnly)
            assertThat(flags("UY-$tag".dropLast(1) + "9")).describedAs("one character apart").isEqualTo(neither)
        }

    @Test
    fun `INV-AC-1823 a retired node, a lower structure version and a group without a GSTN are read as the rules say`() =
        runCounterparty { env ->
            val tag = unique("S").takeLast(6)
            val group = "GRP-$tag"
            env.structure(group, "GROUP", null, null, gstn = "M9-$tag")
            env.structure("X-$tag", "ENTITY", group, "UX-$tag")
            env.structure("Y-$tag", "ENTITY", group, "UY-$tag")
            env.triple("X-$tag")
            env.write("P-$tag", env.request("UEN" to "UY-$tag", name = "Y", address = ADDRESS))

            fun flags() = env.resolve("X-$tag", "UEN", "UY-$tag").let { it.sameTenant to it.sameGroup }
            assertThat(flags()).isEqualTo(both)
            // a state at or below the held structure version is ignored; a newer one replaces the node whole
            env.structure("Y-$tag", "ENTITY", group, "UY-$tag", retired = true, version = 1)
            assertThat(flags()).isEqualTo(both)
            env.structure("Y-$tag", "ENTITY", group, "UY-$tag", retired = true, version = 2)
            assertThat(flags()).isEqualTo(neither)
            env.structure("Y-$tag", "ENTITY", group, "UY-$tag", retired = false, version = 3)
            assertThat(flags()).isEqualTo(both)
            // a group node without a GSTN is no GST group
            env.structure(group, "GROUP", null, null, gstn = null, version = 2)
            assertThat(flags()).isEqualTo(tenantOnly)
        }

    @Test
    fun `INV-AC-1823 a node of another tenant with the same UEN gives neither flag`() =
        runCounterparty { env ->
            val tag = unique("S").takeLast(6)
            env.structure("X-$tag", "ENTITY", null, "UX-$tag")
            env.structure("Y-$tag", "ENTITY", null, "UY-$tag")
            val other = env.anotherTenant()
            other.triple("X-$tag")
            other.write("P-$tag", other.request("UEN" to "UY-$tag", name = "Y", address = ADDRESS))
            val result = other.resolve("X-$tag", "UEN", "UY-$tag")
            assertThat(result.sameTenant to result.sameGroup).isEqualTo(neither)
        }
}
