package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.gst.masterdata.adapters.http.REGISTER_SUGGESTION_CURSOR_KEY_SECRET
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * INV-T-4946 (INV-Q-10175 A): the `nextCursor` of `listRegisterSuggestions` is a payload and an HMAC-SHA256 tag
 * over it; a cursor that is altered, used with another state or by another principal, or used after its 24 hours
 * is refused 400 with no items; the key is read through SecretStore at a fixed path and no cursor is issued without it.
 */
class RegistryCursorTest {
    private val gstn = "M2-1234567-0"

    private fun page(
        env: RegistryEnv,
        state: String? = null,
        cursor: String? = null,
        actor: String = OPERATOR,
        limit: Int = 1,
    ) = env.ce.inTenant(actor) { env.suggestions.listRegisterSuggestions(null, state, limit, cursor).body!! }

    private fun refused(block: () -> Any?) =
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ApiRefusal::class.java) {
            assertThat(it.code.httpStatus).isEqualTo(400)
        }

    @Test
    fun `the key path is the constant the task names`() {
        assertThat(REGISTER_SUGGESTION_CURSOR_KEY_SECRET).isEqualTo("masterdata/register-suggestion-cursor-key")
    }

    @Test
    fun `a signed cursor pages and is refused when altered, rebound or older than 24 hours`() =
        RegistryStub().use { stub ->
            val gstnBody = { uen: String, name: String ->
                "{\"uen\":\"$uen\",\"gstRegistrationNumber\":\"$gstn\",\"status\":\"REGISTERED\"," +
                    "\"registeredName\":\"$name\"}"
            }
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                repeat(2) {
                    val uen = unique("U")
                    stub.found(uen, gstnBody(uen, "NEW $it PTE. LTD."))
                    val party = unique("CTP")
                    env.ce.write(
                        party,
                        env.ce.request("UEN" to uen, "GSTN" to gstn, name = "Old $it", address = ADDRESS),
                    )
                    env.lookup(entity, uen, party)
                }
                val first = page(env)
                assertThat(first.items).hasSize(1)
                val cursor = checkNotNull(first.nextCursor)
                assertThat(page(env, cursor = cursor).items).hasSize(1)
                // one payload character changed
                val flipped = (if (cursor[0] == 'A') 'B' else 'A') + cursor.substring(1)
                refused { page(env, cursor = flipped) }
                refused { page(env, cursor = cursor.substringBefore('.') + "." + "A".repeat(43)) }
                refused { page(env, cursor = "not-a-cursor") }
                // another state, another principal
                refused { page(env, state = "CONFIRMED", cursor = cursor) }
                refused { page(env, cursor = cursor, actor = "IDN-ADVISOR") }
                // 23 hours 59 minutes is accepted; 24 hours and one second is refused
                env.advance(Duration.ofHours(23).plusMinutes(59))
                assertThat(page(env, cursor = cursor).items).hasSize(1)
                env.advance(Duration.ofMinutes(1).plusSeconds(1))
                refused { page(env, cursor = cursor) }
                // a limit above 500 answers 400
                assertThatThrownBy { page(env, limit = 501) }
                    .hasMessageContaining("must be less than or equal to 500")
            }
        }

    @Test
    fun `no cursor is issued when the key cannot be read, the look-alike path included`() =
        RegistryStub().use { stub ->
            runRegistry(
                registerUrl = stub.baseUrl,
                customize = {
                    it.withPropertyValues(
                        "gst.bindings.secret-store.mount-root=" +
                            TestSecrets.mount(
                                BoundaryFixture.RUNTIME_PASSWORD,
                                cursorKeyPath = "masterdata/register-suggestions-cursor-key",
                            ),
                    )
                },
            ) { env ->
                val entity = env.entity()
                repeat(2) {
                    val uen = unique("U")
                    stub.found(
                        uen,
                        "{\"uen\":\"$uen\",\"gstRegistrationNumber\":\"$gstn\",\"status\":\"REGISTERED\"," +
                            "\"registeredName\":\"NEW $it\"}",
                    )
                    val party = unique("CTP")
                    env.ce.write(
                        party,
                        env.ce.request("UEN" to uen, "GSTN" to gstn, name = "Old $it", address = ADDRESS),
                    )
                    env.lookup(entity, uen, party)
                }
                assertThatThrownBy { page(env) }.isInstanceOf(RuntimeException::class.java)
                // a page that needs no cursor is still served
                assertThat(page(env, limit = 5).items).hasSize(2)
            }
        }
}
