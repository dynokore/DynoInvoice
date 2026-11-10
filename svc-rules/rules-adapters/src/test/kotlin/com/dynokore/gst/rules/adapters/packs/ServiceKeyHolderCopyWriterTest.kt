package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.application.HolderCopyRequest
import com.dynokore.gst.rules.application.HolderCopyResult
import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.HashAlgorithm
import com.dynokore.platform.crypto.Hashing
import com.dynokore.platform.crypto.JcaSigningKey
import com.dynokore.platform.crypto.SignatureAlgorithm
import com.dynokore.platform.rulepackkernel.BouncyCastlePackVerifier
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackRegistry
import com.dynokore.platform.rulepackkernel.PackRejectionReason
import com.dynokore.platform.rulepackkernel.PackVerificationOutcome
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.VerifiedPack
import com.dynokore.platform.serviceruntime.binding.Secret
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/** A `SecretStore` over a map that records every name read, in order. */
private class RecordingSecrets : SecretStore {
    val values = linkedMapOf<String, String>()
    val reads = mutableListOf<String>()

    override fun read(ref: SecretRef): Secret {
        reads += ref.value
        return Secret(values[ref.value] ?: throw SecretNotFound(ref))
    }
}

private class RecordingRegistry : PackRegistry {
    val registered = mutableListOf<VerifiedPack>()

    override fun register(version: PackVersion): Unit = throw UnsupportedOperationException()

    override fun register(pack: VerifiedPack) {
        registered += pack
    }

    override fun resolve(
        packType: com.dynokore.platform.rulepackkernel.PackType,
        tenant: TenantId?,
        resolutionDate: LocalDate,
    ): PackVersion? = null
}

/** One signing identity: key pair, a self-signed certificate and their PEM forms. All values are generated here. */
private class Identity(
    curve: String = "secp256r1",
    subject: String = "rules-svc-test-" + UUID.randomUUID(),
) {
    val pair: KeyPair =
        KeyPairGenerator.getInstance("EC").also { it.initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
    val certificateDer: ByteArray = TestCertificates.selfSigned(pair, subject)
    val keyPem: String = pem("PRIVATE KEY", pair.private.encoded)
    val certificatePem: String = pem("CERTIFICATE", certificateDer)
    val label: String = "rules-svc-holder-copy:" + Hashing.hex(HashAlgorithm.SHA_256, certificateDer).take(16)

    fun signingKey() = JcaSigningKey(SignatureAlgorithm.ECDSA_P256_SHA256, pair.private, certificateDer)
}

private fun pem(
    label: String,
    der: ByteArray,
): String =
    "-----BEGIN $label-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
        "\n-----END $label-----\n"

/** A minimal DER X.509 v3 certificate builder (no extension): a parseable certificate and nothing more. */
private object TestCertificates {
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
                    der(0x02, BigInteger(64, java.security.SecureRandom()).add(BigInteger.ONE).toByteArray()) +
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

/** The log of the writer: every message and argument it was given, as text. */
private class CapturedLog {
    val lines = mutableListOf<String>()
    val logger: Logger =
        Proxy.newProxyInstance(Logger::class.java.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
            if (method.name in setOf("warn", "error", "info", "debug", "trace")) {
                lines +=
                    (args ?: emptyArray()).joinToString(" ") { a ->
                        if (a is Array<*>) a.joinToString(" ") else a.toString()
                    }
            }
            if (method.returnType == Boolean::class.javaPrimitiveType) true else null
        } as Logger
}

/** The holder copy signing adapter over the real CMS signer and pack verifier, with test keys (INV-REQ-1195). */
class ServiceKeyHolderCopyWriterTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val secrets = RecordingSecrets()
    private val registry = RecordingRegistry()
    private val log = CapturedLog()
    private val verifier = BouncyCastlePackVerifier()
    private val keys = HolderCopySigningKeys(secrets)
    private val writer =
        ServiceKeyHolderCopyWriter(keys, BouncyCastleCms(), verifier, registry, { it }, log = log.logger)
    private val contents = """{"complete":true,"codes":[]}""".toByteArray()

    private fun request(effectiveTo: LocalDate? = LocalDate.parse("2027-01-01")) =
        HolderCopyRequest(
            tenant,
            "pack-1",
            "MAPPING",
            "2026.1",
            LocalDate.parse("2026-01-01"),
            effectiveTo,
            "1.0.0",
            contents,
        )

    private fun mount(
        identity: Identity,
        next: Identity? = null,
        previous: Identity? = null,
    ) {
        secrets.values["rules/holder-copy-signing-key"] = identity.keyPem
        secrets.values["rules/holder-copy-signing-certificate"] = identity.certificatePem
        next?.let { secrets.values["rules/holder-copy-signing-certificate-next"] = it.certificatePem }
        previous?.let { secrets.values["rules/holder-copy-signing-certificate-previous"] = it.certificatePem }
        secrets.reads.clear()
    }

    private fun assertUnavailableNamed(secret: String) {
        writer.write(request()) shouldBe HolderCopyResult.Unavailable
        registry.registered shouldBe emptyList()
        log.lines.joinToString("\n").let {
            it shouldContain secret
            it shouldNotContain "PRIVATE KEY"
            it shouldNotContain "BEGIN"
            it shouldNotContain "garbage"
        }
    }

    @Test
    fun `INV-REQ-1195 the copy is signed over manifest then checksums and carries the label`() {
        val id = Identity()
        mount(id)
        writer.write(request()) shouldBe HolderCopyResult.Written
        val pack = registry.registered.single()
        pack.version.tenant shouldBe tenant
        pack.trustedAnchorLabel shouldBe id.label
        val m = pack.version.manifest
        m.packId.value shouldBe "pack-1"
        m.packType.value shouldBe "MAPPING"
        m.version.value shouldBe "2026.1"
        m.effectiveFrom shouldBe LocalDate.parse("2026-01-01")
        m.effectiveTo shouldBe LocalDate.parse("2027-01-01")
        m.minEngineVersion shouldBe "1.0.0"
        String(pack.artefact.checksums) shouldBe """{"contents":"${Hashing.hex(HashAlgorithm.SHA_256, contents)}"}"""
        pack.artefact.contents.contentEquals(contents) shouldBe true
        // an artefact whose signature covers the contents instead of manifest then checksums is Rejected
        val wrong = BouncyCastleCms().sign(pack.artefact.contents, id.signingKey())
        val tampered = PackArtefact(pack.artefact.manifest, pack.artefact.checksums, wrong, pack.artefact.contents)
        verifier.verify(tampered, keys.load().anchors).shouldBeInstanceOf<PackVerificationOutcome.Rejected>()
        verifier.verify(pack.artefact, keys.load().anchors).shouldBeInstanceOf<PackVerificationOutcome.Verified>()
    }

    @Test
    fun `INV-REQ-1195 an open-ended pack has a null effectiveTo in the signed manifest`() {
        mount(Identity())
        writer.write(request(effectiveTo = null)) shouldBe HolderCopyResult.Written
        registry.registered
            .single()
            .version.manifest.effectiveTo shouldBe null
    }

    @Test
    fun `INV-REQ-1195 the label is the hash of the certificate DER and not its subject text`() {
        val a = Identity(subject = "same-subject")
        val b = Identity(subject = "same-subject")
        mount(a)
        writer.write(request())
        mount(b)
        writer.write(request())
        registry.registered.map { it.trustedAnchorLabel } shouldBe listOf(a.label, b.label)
        (a.label == b.label) shouldBe false
    }

    @Test
    fun `INV-REQ-1195 a write reads exactly the key and the certificate, and the optional ones when present`() {
        mount(Identity())
        writer.write(request())
        secrets.reads shouldBe
            listOf(
                "rules/holder-copy-signing-key",
                "rules/holder-copy-signing-certificate",
                "rules/holder-copy-signing-certificate-next",
                "rules/holder-copy-signing-certificate-previous",
            )
        mount(Identity(), next = Identity(), previous = Identity())
        writer.write(request())
        secrets.reads shouldBe
            listOf(
                "rules/holder-copy-signing-key",
                "rules/holder-copy-signing-certificate",
                "rules/holder-copy-signing-certificate-next",
                "rules/holder-copy-signing-certificate-previous",
            )
        secrets.reads.none { it == "rules/active-packs-cursor-key" } shouldBe true
    }

    @Test
    fun `INV-REQ-1195 a replaced key pair takes effect on the next write without a restart`() {
        val first = Identity()
        val second = Identity()
        mount(first)
        writer.write(request())
        mount(second)
        writer.write(request())
        registry.registered.map { it.trustedAnchorLabel } shouldBe listOf(first.label, second.label)
    }

    @Test
    fun `INV-REQ-1195 the look-alike secret names are not read and a write with them alone is Unavailable`() {
        val id = Identity()
        secrets.values["rules/holder-copy-key"] = id.keyPem
        secrets.values["rules/holder-copy-signing-keys"] = id.keyPem
        writer.write(request()) shouldBe HolderCopyResult.Unavailable
        secrets.reads shouldBe listOf("rules/holder-copy-signing-key")
        registry.registered shouldBe emptyList()
    }

    @Test
    fun `INV-REQ-1195 an absent key secret is Unavailable and the log names it`() {
        val id = Identity()
        secrets.values["rules/holder-copy-signing-certificate"] = id.certificatePem
        assertUnavailableNamed("rules/holder-copy-signing-key")
    }

    @Test
    fun `INV-REQ-1195 an absent certificate secret is Unavailable and the log names it`() {
        secrets.values["rules/holder-copy-signing-key"] = Identity().keyPem
        assertUnavailableNamed("rules/holder-copy-signing-certificate")
    }

    @Test
    fun `INV-REQ-1195 a key that is not PKCS8 PEM is Unavailable and no key byte is logged`() {
        val id = Identity()
        mount(id)
        val keyText = Base64.getEncoder().encodeToString(id.pair.private.encoded)
        secrets.values["rules/holder-copy-signing-key"] = "not a key $keyText"
        assertUnavailableNamed("rules/holder-copy-signing-key")
        log.lines.joinToString("\n") shouldNotContain keyText
        secrets.values["rules/holder-copy-signing-key"] = pem("EC PRIVATE KEY", id.pair.private.encoded)
        writer.write(request()) shouldBe HolderCopyResult.Unavailable
    }

    @Test
    fun `INV-REQ-1195 a P-384 key is Unavailable`() {
        val id = Identity(curve = "secp384r1")
        mount(id)
        assertUnavailableNamed("rules/holder-copy-signing-key")
    }

    @Test
    fun `INV-REQ-1195 a certificate that does not belong to the key is Unavailable`() {
        mount(Identity())
        secrets.values["rules/holder-copy-signing-certificate"] = Identity().certificatePem
        writer.write(request()) shouldBe HolderCopyResult.Unavailable
        registry.registered shouldBe emptyList()
        log.lines.joinToString("\n") shouldContain "rules/holder-copy-signing-certificate"
        log.lines.joinToString("\n") shouldNotContain "PRIVATE KEY"
    }

    @Test
    fun `INV-REQ-1195 a certificate secret that is not X509 PEM is Unavailable naming that secret`() {
        mount(Identity())
        secrets.values["rules/holder-copy-signing-certificate-next"] = "garbage"
        assertUnavailableNamed("rules/holder-copy-signing-certificate-next")
    }

    @Test
    fun `INV-REQ-1195 a previous certificate secret that is not X509 PEM is Unavailable naming that secret`() {
        mount(Identity())
        secrets.values["rules/holder-copy-signing-certificate-previous"] = "garbage"
        assertUnavailableNamed("rules/holder-copy-signing-certificate-previous")
    }

    @Test
    fun `INV-REQ-1195 the exception text names the secret`() {
        val e = shouldThrow<HolderCopyKeyUnavailable> { keys.load() }
        e.message!! shouldContain "rules/holder-copy-signing-key"
    }

    @Test
    fun `INV-REQ-1195 an artefact signed with the next key verifies while its certificate is present and not after`() {
        val current = Identity()
        val next = Identity()
        mount(current, next = next)
        val artefact = signedBy(next)
        val verified = verifier.verify(artefact, keys.load().anchors)
        verified.shouldBeInstanceOf<PackVerificationOutcome.Verified>().trustedAnchorLabel shouldBe next.label
        secrets.values.remove("rules/holder-copy-signing-certificate-next")
        verifier
            .verify(artefact, keys.load().anchors)
            .shouldBeInstanceOf<PackVerificationOutcome.Rejected>()
            .reason shouldBe PackRejectionReason.UNTRUSTED_SIGNER
    }

    @Test
    fun `INV-REQ-1195 an older row of the retired key verifies while its certificate is previous`() {
        val retired = Identity()
        val current = Identity()
        mount(current, previous = retired)
        val artefact = signedBy(retired)
        verifier
            .verify(artefact, keys.load().anchors)
            .shouldBeInstanceOf<PackVerificationOutcome.Verified>()
            .trustedAnchorLabel shouldBe retired.label
        secrets.values.remove("rules/holder-copy-signing-certificate-previous")
        verifier.verify(artefact, keys.load().anchors).shouldBeInstanceOf<PackVerificationOutcome.Rejected>()
    }

    private fun signedBy(id: Identity): PackArtefact {
        val checksums = """{"contents":"${Hashing.hex(HashAlgorithm.SHA_256, contents)}"}""".toByteArray()
        val manifest =
            (
                """{"packId":"p","packType":"MAPPING","version":"1","effectiveFrom":"2026-01-01",""" +
                    """"effectiveTo":null,"minEngineVersion":"1"}"""
            ).toByteArray()
        val signature = BouncyCastleCms().sign(manifest + checksums, id.signingKey())
        return PackArtefact(manifest, checksums, signature, contents)
    }
}
