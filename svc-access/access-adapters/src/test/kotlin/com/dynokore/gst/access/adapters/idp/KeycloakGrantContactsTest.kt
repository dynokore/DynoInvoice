package com.dynokore.gst.access.adapters.idp

import com.dynokore.gst.access.domain.grant.IdentityRef
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** The address of a grant holder from the Keycloak Admin REST API (INV-T-15799). */
class KeycloakGrantContactsTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val requests = CopyOnWriteArrayList<String>()
    private val tokenBodies = CopyOnWriteArrayList<String>()

    @AfterEach
    fun stop() = server.stop(0)

    private fun serve(
        users: String,
        status: Int = 200,
    ): KeycloakGrantContacts {
        server.createContext("/realms/gst/protocol/openid-connect/token") { ex ->
            tokenBodies += ex.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            reply(ex, 200, """{"access_token":"tok"}""")
        }
        server.createContext("/admin/realms/gst/users") { ex ->
            requests += ex.requestURI.toString() + " auth=" + ex.requestHeaders.getFirst("Authorization")
            reply(ex, status, users)
        }
        server.start()
        return KeycloakGrantContacts(base, "gst", "reader") { "s3cret" }
    }

    private fun reply(
        ex: com.sun.net.httpserver.HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `one matching user gives the address, asked by exact username with the bearer token`() {
        val contacts = serve("""[{"username":"alice","email":"alice@example.sg"}]""")
        assertEquals("alice@example.sg", contacts.addressOf(IdentityRef("alice")))
        assertEquals("/admin/realms/gst/users?username=alice&exact=true auth=Bearer tok", requests.single())
    }

    @Test
    fun `the token request is the client-credentials form of the group reader`() {
        serve("""[]""").addressOf(IdentityRef("alice"))
        assertEquals("grant_type=client_credentials&client_id=reader&client_secret=s3cret", tokenBodies.single())
    }

    @Test
    fun `two users give none`() {
        val contacts = serve("""[{"username":"alice","email":"a@x.sg"},{"username":"alice","email":"b@x.sg"}]""")
        assertNull(contacts.addressOf(IdentityRef("alice")))
    }

    @Test
    fun `a user whose username differs only in case gives none`() {
        val contacts = serve("""[{"username":"Alice","email":"alice@example.sg"}]""")
        assertNull(contacts.addressOf(IdentityRef("alice")))
    }

    @Test
    fun `a blank or absent email gives none`() {
        assertNull(serve("""[{"username":"alice","email":"  "}]""").addressOf(IdentityRef("alice")))
    }

    @Test
    fun `an absent email field gives none`() {
        assertNull(serve("""[{"username":"alice"}]""").addressOf(IdentityRef("alice")))
    }

    @Test
    fun `an empty list gives none`() {
        assertNull(serve("[]").addressOf(IdentityRef("alice")))
    }

    @Test
    fun `a 500 throws`() {
        val contacts = serve("{}", status = 500)
        assertThrows(IllegalStateException::class.java) { contacts.addressOf(IdentityRef("alice")) }
    }

    @Test
    fun `a body that is not a list throws`() {
        val contacts = serve("""{"error":"x"}""")
        assertThrows(IllegalStateException::class.java) { contacts.addressOf(IdentityRef("alice")) }
    }

    @Test
    fun `an unreadable body throws`() {
        val contacts = serve("<html>")
        assertThrows(RuntimeException::class.java) { contacts.addressOf(IdentityRef("alice")) }
    }

    @Test
    fun `a username with reserved characters is sent encoded and compared by equality`() {
        val contacts = serve("""[{"username":"a&b c","email":"ab@example.sg"}]""")
        assertEquals("ab@example.sg", contacts.addressOf(IdentityRef("a&b c")))
        assertEquals(
            "a&b c",
            URLDecoder.decode(
                requests.single().substringAfter("username=").substringBefore("&exact"),
                StandardCharsets.UTF_8,
            ),
        )
    }

    @Test
    fun `the source of the adapter holds no logger, so no log line can hold an address`() {
        val source =
            Files.readString(
                Path.of("src/main/kotlin/com/dynokore/gst/access/adapters/idp/KeycloakGrantContacts.kt"),
            )
        val code =
            source
                .lines()
                .filterNot {
                    it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") ||
                        it.trimStart().startsWith("//")
                }.joinToString("\n")
        assertTrue(!Regex("""(?i)logger|LoggerFactory|println|\blog\.""").containsMatchIn(code))
    }
}
