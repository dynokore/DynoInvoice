package com.dynokore.gst.access.bootstrap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.access.application.breakglass.SealedAccountRules
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.accept
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.movableClock
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.provisioned
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.token
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.viaConsole
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.operation
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantId
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The first acceptance of a tenant: its invitation, the 200 and the plaintext secret of the 200 (null: absent). */
class Activation(
    val invitation: Invitation,
    val response: MockHttpServletResponse,
) {
    val tenant: TenantId get() = invitation.tenant
    val sealing: String? get() = json(response).get("sealingCredential")?.asString()
}

/** The helpers of the sealed break-glass account tests (INV-T-8853), through the real context over PostgreSQL. */
object SealFixture {
    /** Holds `deployment.operator` at installation scope in the test contexts (BoundaryFixture.INSTALLATION_ROLES). */
    const val OPERATOR = "user:op-1"
    const val ADMIN = "IDN-ADM"
    const val APPROVER = "IDN-APP"
    private const val BASE = "/internal/v1/access/break-glass"

    /** Provisions the first administrator of a new tenant and accepts the invitation once (step 3 of INV-FLOW-227). */
    fun ServedContext.activated(tenant: TenantId = newTenant()): Activation {
        val invitation = provisioned(tenant)
        val response = accept(invitation.ref, invitation.code, token("invitee-" + UUID.randomUUID(), null))
        check(response.status == 200) { "the acceptance answered ${response.status}: ${response.contentAsString}" }
        return Activation(invitation, response)
    }

    /** The two people who may unlock: a tenant.admin and a tax.approver of [tenant]. */
    fun ServedContext.pair(tenant: TenantId) {
        Seeds.grant(application, tenant, SeedSpec(ADMIN, Role.TENANT_ADMIN))
        Seeds.grant(application, tenant, SeedSpec(APPROVER, Role.TAX_APPROVER))
    }

    /** An unlock by the pair of [tenant]; the reference and the expiry the 201 carries. */
    fun ServedContext.unlocked(tenant: TenantId): Pair<String, Instant> {
        val body = """{"tenantRef":"$tenant","approvedBy":["$ADMIN","$APPROVER"]}"""
        val response =
            post(
                operation("unlockBreakGlass"),
                viaConsole(token(ADMIN, tenant)),
                body,
                UUID.randomUUID(),
            )
        check(response.status == 201) { "the unlock answered ${response.status}: ${response.contentAsString}" }
        val node = json(response)
        return node["unlockRef"].asString() to Instant.parse(node["expiresAt"].asString())
    }

    fun ServedContext.signIn(
        tenant: TenantId,
        unlockRef: String,
        presented: String,
        key: UUID = UUID.randomUUID(),
    ): MockHttpServletResponse =
        post(
            operation("signInBreakGlass"),
            viaConsole(token(ADMIN, tenant)),
            """{"sealingCredential":"$presented"}""",
            key,
            "$BASE/$unlockRef/sign-in",
        )

    fun ServedContext.reseal(
        tenant: TenantId,
        unlockRef: String,
        caller: String = OPERATOR,
        key: UUID = UUID.randomUUID(),
    ): MockHttpServletResponse =
        post(operation("resealBreakGlass"), viaConsole(token(caller, tenant)), "{}", key, "$BASE/$unlockRef/reseal")

    /** Moves the test clock to one second after [expiresAt]. */
    fun ServedContext.passExpiry(expiresAt: Instant) {
        val clock = movableClock()
        val now = clock.freeze()
        clock.advance(Duration.between(now, expiresAt.plusSeconds(1)))
    }

    /** The rows of the sealed-account table for [tenant], one JSON text per row. */
    fun accountRows(tenant: TenantId): List<String> = rows("access.break_glass_account", tenant)

    /** The stored verifier of [tenant]'s account, or null when it has no row. */
    fun verifier(tenant: TenantId): String? =
        accountRows(tenant).singleOrNull()?.let { json(it)["verifier"].asString() }

    fun sessionStatus(
        tenant: TenantId,
        unlockRef: String,
    ): String? =
        rows("access.break_glass_session", tenant)
            .map { json(it) }
            .singleOrNull { it["unlock_ref"].asString() == unlockRef }
            ?.get("status")
            ?.asString()

    /** Every row of every table of the access schema as jsonb text, so every column of every row is searched. */
    fun everyStoredValue(): List<String> =
        BoundaryFixture.asSuperuser { c ->
            val tables =
                c.createStatement().use { s ->
                    s
                        .executeQuery(
                            "select table_name from information_schema.tables " +
                                "where table_schema = 'access' and table_type = 'BASE TABLE'",
                        ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            tables.flatMap { table ->
                c.createStatement().use { s ->
                    s.executeQuery("select to_jsonb(t)::text from access.\"$table\" t").use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
            }
        }

    /** The text of the events the service captured instead of publishing (audit entries and outbox payloads). */
    fun capturedEvents(served: ServedContext): List<String> =
        served.application
            .getBean(CapturedEvents::class.java)
            .envelopes
            .map { it.toString() + it.dataJson }

    /** Runs [block] and returns the log lines (message and exception text) every logger wrote during it, at DEBUG. */
    fun <T> logged(block: () -> T): Pair<T, List<String>> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val before = root.level
        root.addAppender(appender)
        root.level = Level.DEBUG
        try {
            val result = block()
            return result to
                appender.list.map { event ->
                    event.formattedMessage + event.throwableProxy?.let { " " + it.message }.orEmpty()
                }
        } finally {
            root.detachAppender(appender)
            root.level = before
        }
    }

    /** The decoded size in bytes of a base64url value without padding. */
    fun bytesOf(value: String): Int =
        java.util.Base64
            .getUrlDecoder()
            .decode(value)
            .size

    fun digestOf(value: String): String = SealedAccountRules.digestOf(value)
}
