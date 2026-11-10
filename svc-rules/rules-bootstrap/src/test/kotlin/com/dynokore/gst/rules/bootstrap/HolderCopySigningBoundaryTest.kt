package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.packs.ServiceKeyHolderCopyWriter
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.application.HolderCopyPort
import com.dynokore.gst.rules.application.HolderCopyRequest
import com.dynokore.gst.rules.application.HolderCopyResult
import com.dynokore.gst.rules.application.PackActionAuthority
import com.dynokore.gst.rules.application.PackApprovalDecision
import com.dynokore.gst.rules.application.PackApprovalDecisionPort
import com.dynokore.gst.rules.application.RateSourcePort
import com.dynokore.gst.rules.served.TENANT_A
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVerificationOutcome
import com.dynokore.platform.rulepackkernel.PackVerifier
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.TrustAnchor
import com.dynokore.platform.rulepackkernel.TrustAnchors
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

private const val CONSOLE_SERVICE = "console-bff"
private const val ALT_SUFFIX = "-alt"
private const val B_TOKEN = "Bearer token-user-b"
private const val B_USER = "user-b"
private const val ENGINE = "2026.11.1"

private const val KEY = "rules/holder-copy-signing-key"
private const val CERT = "rules/holder-copy-signing-certificate"
private const val CERT_NEXT = "rules/holder-copy-signing-certificate-next"

/** The allowing fakes of the three ports other than the holder copy, which is the real adapter in this test. */
@TestConfiguration(proxyBeanMethods = false)
class HolderSigningOtherPorts {
    @Bean
    fun packActionAuthority(): PackActionAuthority = PackActionAuthority { _, _, _ -> true }

    @Bean
    fun packApprovalDecisionPort(): PackApprovalDecisionPort =
        PackApprovalDecisionPort { _ -> PackApprovalDecision.Allow(false, null, "OPTION-1", emptySet()) }

    @Bean
    fun rateSourcePort(): RateSourcePort = RateSourcePort { _, _ -> emptyList() }
}

/** A test signing identity; every value is generated here, none is read from a file of the repository. */
private class HolderIdentity(
    curve: String = "secp256r1",
) {
    val pair: KeyPair =
        KeyPairGenerator.getInstance("EC").also { it.initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
    val certificateDer: ByteArray = HolderCertificates.selfSigned(pair, "rules-test-" + UUID.randomUUID())
    val keyPem: String = holderPem("PRIVATE KEY", pair.private.encoded)
    val certificatePem: String = holderPem("CERTIFICATE", certificateDer)
    val label: String =
        "rules-svc-holder-copy:" +
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificateDer)).take(16)
}

private fun holderPem(
    label: String,
    der: ByteArray,
): String =
    "-----BEGIN $label-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
        "\n-----END $label-----\n"

/** A minimal DER X.509 v3 certificate builder (no extension): a parseable certificate and nothing more. */
private object HolderCertificates {
    private fun der(
        tag: Int,
        body: ByteArray,
    ): ByteArray {
        val length =
            when {
                body.size < 128 -> byteArrayOf(body.size.toByte())
                body.size < 256 -> byteArrayOf(0x81.toByte(), body.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
            }
        return byteArrayOf(tag.toByte()) + length + body
    }

    private fun name(cn: String) =
        der(0x30, der(0x31, der(0x30, byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03) + der(0x0c, cn.toByteArray()))))

    fun selfSigned(
        pair: KeyPair,
        cn: String,
    ): ByteArray {
        val oid = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
        val sigAlg = der(0x30, oid)
        val validity = der(0x30, der(0x17, "200101000000Z".toByteArray()) + der(0x17, "490101000000Z".toByteArray()))
        val tbs =
            der(
                0x30,
                byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02) +
                    der(0x02, BigInteger(64, SecureRandom()).add(BigInteger.ONE).toByteArray()) +
                    sigAlg + name(cn) + validity + name(cn) + pair.public.encoded,
            )
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(tbs)
                sign()
            }
        return der(0x30, tbs + sigAlg + der(0x03, byteArrayOf(0) + signature))
    }
}

/**
 * The holder copy of an activated mapping pack is signed with the service key of rules-svc, through the real
 * composition on real PostgreSQL (INV-REQ-1195; INV-AC-2181; INV-Q-14650 A, INV-Q-16531 A). The secrets are test keys
 * generated here and written under the test secret mount, as the mounted files of a deployment would be.
 */
@SpringBootTest(
    classes = [MappingDoubles::class, HolderSigningOtherPorts::class, VersionedBuild::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$CONSOLE_SERVICE",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$CONSOLE_SERVICE",
        "service-runtime.workload-identity.callers[1].service=$CONSOLE_SERVICE$ALT_SUFFIX",
        "service-runtime.workload-identity.callers[1].subject=subject-of-$CONSOLE_SERVICE$ALT_SUFFIX",
    ],
)
class HolderCopySigningBoundaryTest(
    @org.springframework.boot.test.web.server.LocalServerPort port: Int,
    @Autowired private val context: ApplicationContext,
    @Autowired private val tenants: TenantContext,
    @Autowired private val tx: TransactionRunner,
    @Autowired private val db: DSLContext,
    @Autowired private val verifier: PackVerifier,
) : MappingPackSupport(port) {
    private val secretRoot: Path = PostgresRules.secrets.resolve("rules")
    private val json = JsonMapper()

    private fun mount(
        name: String,
        text: String,
    ) {
        val file = PostgresRules.secrets.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun mountIdentity(
        id: HolderIdentity,
        next: HolderIdentity? = null,
    ) {
        mount(KEY, id.keyPem)
        mount(CERT, id.certificatePem)
        next?.let { mount(CERT_NEXT, it.certificatePem) }
    }

    @AfterEach
    fun unmount() {
        if (!Files.isDirectory(secretRoot)) return
        val held = Files.list(secretRoot).use { files -> files.toList() }
        held.filter { it.fileName.toString().startsWith("holder-copy") }.forEach(Files::delete)
    }

    private fun request(
        packId: String,
        label: String,
        effectiveTo: LocalDate? = LocalDate.parse("2027-01-01"),
    ) = HolderCopyRequest(
        TENANT_A,
        packId,
        "MAPPING",
        label,
        LocalDate.parse("2026-01-01"),
        effectiveTo,
        "1.0.0",
        """{"complete":true,"codes":[{"code":"SR"}]}""".toByteArray(),
    )

    private fun write(request: HolderCopyRequest): HolderCopyResult =
        tenants.bind(TENANT_A, B_USER) {
            tx.inTransaction { context.getBean(HolderCopyPort::class.java).write(request) }
        }

    private fun artefactOf(request: HolderCopyRequest): PackArtefact? =
        tenants.bind(TENANT_A, B_USER) {
            tx.inTransaction {
                val manifest =
                    PackManifest(
                        PackId(request.packId),
                        PackType(request.packType),
                        PackVersionLabel(request.versionLabel),
                        request.effectiveFrom,
                        request.effectiveTo,
                        request.minEngineVersion,
                    )
                PersistedPackRegistry(db, tenants).artefactOf(PackVersion(manifest, TENANT_A))
            }
        }

    private fun artefactRows(packId: String) =
        rows(
            TENANT_A,
            "select pack_id, trusted_anchor, registered_by from rules.pack_artefact where pack_id = '$packId'",
        )

    @Test
    fun `INV-AC-19690 INV-REQ-1195 the HolderCopyPort bean is the service key writer and starts without secrets`() {
        context.getBeansOfType(HolderCopyPort::class.java).values.map { it::class.java } shouldBe
            listOf(ServiceKeyHolderCopyWriter::class.java)
        val request = request("MAP-" + UUID.randomUUID(), "w-0")
        val before = snapshot()
        write(request) shouldBe HolderCopyResult.Unavailable
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-19690 INV-REQ-1195 row written for a MAPPING pack is signed and read back as the test computes it`() {
        val id = HolderIdentity()
        mountIdentity(id)
        val request = request("MAP-" + UUID.randomUUID(), "w-1")
        write(request) shouldBe HolderCopyResult.Written
        val artefact = checkNotNull(artefactOf(request)) { "no row was written" }
        val sha = MessageDigest.getInstance("SHA-256").digest(artefact.contents)
        val digest = HexFormat.of().formatHex(sha)
        json.readTree(String(artefact.checksums)).let {
            it.size() shouldBe 1
            it.get("contents").asString() shouldBe digest
        }
        val verified =
            verifier
                .verify(artefact, TrustAnchors(listOf(TrustAnchor(id.label, id.certificateDer))))
                .shouldBeInstanceOf<PackVerificationOutcome.Verified>()
        with(verified.manifest) {
            packId.value shouldBe request.packId
            packType.value shouldBe "MAPPING"
            version.value shouldBe "w-1"
            effectiveFrom shouldBe request.effectiveFrom
            effectiveTo shouldBe request.effectiveTo
            minEngineVersion shouldBe "1.0.0"
        }
        val row = artefactRows(request.packId).single()
        row["trusted_anchor"] shouldBe id.label
        verified.trustedAnchorLabel shouldBe row["trusted_anchor"]
        row["registered_by"] shouldBe B_USER
    }

    @Test
    fun `INV-AC-19690 INV-REQ-1195 every failure to sign answers Unavailable and every table of the schema is equal`() {
        val good = HolderIdentity()
        val cases =
            mapOf<String, () -> Unit>(
                "key absent" to { mount(CERT, good.certificatePem) },
                "certificate absent" to { mount(KEY, good.keyPem) },
                "key not PKCS#8 PEM" to {
                    mountIdentity(good)
                    mount(KEY, "not a key")
                },
                "P-384 key" to { mountIdentity(HolderIdentity("secp384r1")) },
                "certificate of another key" to {
                    mountIdentity(good)
                    mount(CERT, HolderIdentity().certificatePem)
                },
                "look-alike secret alone" to { mount("rules/holder-copy-key", good.keyPem) },
            )
        cases.forEach { (name, setUp) ->
            unmount()
            Files.deleteIfExists(PostgresRules.secrets.resolve("rules/holder-copy-key"))
            setUp()
            val before = snapshot()
            io.kotest.assertions.withClue(name) {
                write(request("MAP-" + UUID.randomUUID(), "f-1")) shouldBe HolderCopyResult.Unavailable
                snapshot() shouldBe before
            }
        }
        Files.deleteIfExists(PostgresRules.secrets.resolve("rules/holder-copy-key"))
    }

    @Test
    fun `INV-AC-19690 INV-REQ-1195 approvePack then activateMappingPack arms the pack with 1 verifying artefact row`() {
        val id = HolderIdentity()
        mountIdentity(id)
        val label = "act-" + UUID.randomUUID().toString().take(8)
        val packId = seedPack(label, "SUBMITTED")
        deltaRow(TENANT_A, label, "COMPLETE")
        approve(packId, B_TOKEN).statusCode() shouldBe 200
        activate(packId, "2026-01-01", B_TOKEN).statusCode() shouldBe 200
        registryRow(packId).single()["status"] shouldBe "ARMED"
        val row = artefactRows(packId).single()
        row["trusted_anchor"] shouldBe id.label
        row["registered_by"] shouldBe B_USER
        val read =
            checkNotNull(
                artefactOf(
                    request(packId, label, null).copy(minEngineVersion = ENGINE, effectiveTo = null),
                ),
            )
        val verified =
            verifier
                .verify(read, TrustAnchors(listOf(TrustAnchor(id.label, id.certificateDer))))
                .shouldBeInstanceOf<PackVerificationOutcome.Verified>()
        verified.trustedAnchorLabel shouldBe row["trusted_anchor"]
    }

    @Test
    fun `INV-AC-19690 INV-REQ-1195 with the secrets removed activateMappingPack is refused and every table is equal`() {
        val label = "gone-" + UUID.randomUUID().toString().take(8)
        val packId = seedPack(label, "APPROVED")
        val before = snapshot()
        val registryBefore = registryRow(packId)
        val answer = activate(packId, "2026-01-01", B_TOKEN)
        answer.statusCode() shouldBe 503
        snapshot() shouldBe before
        registryRow(packId) shouldBe registryBefore
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = registerCatalogue(registry)
    }
}
