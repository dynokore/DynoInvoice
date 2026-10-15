package com.dynokore.platform.crypto

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.SignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/** Path rules of [ChainedSignatureVerifier] over a PKI generated here (ECDSA P-256; no stored key material). */
class ChainedSignatureVerifierTest {
    private val cms = BouncyCastleCms()
    private val alg = SignatureAlgorithm.ECDSA_P256_SHA256
    private val content = """{"bundle":"configuration","version":"7"}""".toByteArray()

    // The CMS signingTime attribute is the wall clock, so the PKI is built around it;
    // the verifier is handed `at` explicitly.
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val untrusted = DetachedVerification.UntrustedSigner

    private class Node(
        val name: String,
        val pair: KeyPair,
        val cert: X509CertificateHolder,
    ) {
        val der: ByteArray get() = cert.encoded
    }

    // ---- accepted -------------------------------------------------------------------------------------

    @Test
    fun `a signer issued directly by a listed root is accepted with the right index`() {
        val other = ca("other")
        val root = ca("root")
        val signer = leaf("signer", root)
        assertEquals(verified(1), run(signer, listOf(signer), listOf(other.der, root.der)))
    }

    @Test
    fun `a signer issued by a carried intermediate under a listed root is accepted`() {
        val root = ca("root")
        val inter = ca("inter", root)
        val signer = leaf("signer", inter)
        assertEquals(verified(0), run(signer, listOf(signer, inter), listOf(root.der)))
    }

    @Test
    fun `a signing certificate listed itself as the anchor is accepted`() {
        val signer = leaf("signer", null)
        assertEquals(verified(0), run(signer, listOf(signer), listOf(signer.der)))
    }

    @Test
    fun `an intermediate listed as the anchor is accepted`() {
        val root = ca("root")
        val inter = ca("inter", root)
        val signer = leaf("signer", inter)
        assertEquals(verified(0), run(signer, listOf(signer), listOf(inter.der)))
    }

    @Test
    fun `a path of exactly 8 certificates is accepted`() {
        val (root, signer, carried) = chain(intermediates = 6)
        assertEquals(verified(0), run(signer, carried, listOf(root.der)))
    }

    // ---- untrusted ------------------------------------------------------------------------------------

    @Test
    fun `a signer issued by an unlisted CA is untrusted`() {
        val listed = ca("listed")
        val unlisted = ca("unlisted")
        val signer = leaf("signer", unlisted)
        assertEquals(untrusted, run(signer, listOf(signer, unlisted), listOf(listed.der)))
    }

    @Test
    fun `a message carrying its own unlisted self-signed root is untrusted`() {
        val listed = ca("listed")
        val ownRoot = ca("own-root")
        val signer = leaf("signer", ownRoot)
        assertEquals(untrusted, run(signer, listOf(signer, ownRoot), listOf(listed.der)))
    }

    @Test
    fun `the signer is the certificate the SignerInfo names, not another carried one`() {
        val root = ca("root")
        val listedDecoy = leaf("decoy", root)
        val signer = leaf("signer", null)
        assertEquals(untrusted, run(signer, listOf(listedDecoy, signer), listOf(listedDecoy.der)))
    }

    @Test
    fun `an anchor equal to the issuer only by subject name is not an anchor`() {
        val root = ca("root")
        val impostor = ca("root") // same subject, another key
        val signer = leaf("signer", impostor)
        assertEquals(untrusted, run(signer, listOf(signer, impostor), listOf(root.der)))
    }

    @Test
    fun `an issuer name that matches while another key made the signature is untrusted`() {
        val root = ca("root")
        val impostor = ca("root")
        val signer = leaf("signer", impostor)
        // the carried intermediate claims the listed root's name but is signed by the impostor key
        val inter = ca("inter", impostor, nameOfIssuer = root.cert.subject)
        val lower = leaf("signer2", inter)
        assertEquals(untrusted, run(lower, listOf(lower, inter), listOf(root.der)))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der)))
    }

    @Test
    fun `a link whose issuer name differs from the next subject is untrusted although the key signed it`() {
        val root = ca("root")
        val signer = leaf("signer", root, nameOfIssuer = X500Name("CN=somebody-else"))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der)))
    }

    @Test
    fun `an intermediate that is not a CA is untrusted`() {
        val root = ca("root")
        val inter = ca("inter", root, isCa = false)
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der)))
    }

    @Test
    fun `an intermediate without keyCertSign is untrusted`() {
        val root = ca("root")
        val inter = ca("inter", root, usage = KeyUsage.digitalSignature)
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der)))
    }

    @Test
    fun `an exceeded pathLenConstraint is untrusted`() {
        val root = ca("root", pathLen = 0)
        val inter = ca("inter", root)
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der)))
    }

    @Test
    fun `a pathLenConstraint that is met is accepted`() {
        val root = ca("root", pathLen = 1)
        val inter = ca("inter", root, pathLen = 0)
        val signer = leaf("signer", inter)
        assertEquals(verified(0), run(signer, listOf(signer, inter), listOf(root.der)))
    }

    @Test
    fun `a signer without digitalSignature is untrusted`() {
        val root = ca("root")
        val signer = leaf("signer", root, usage = KeyUsage.keyEncipherment)
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der)))
    }

    @Test
    fun `a CA certificate as the signer is untrusted`() {
        val root = ca("root")
        val signer = ca("signing-ca", root, usage = KeyUsage.keyCertSign or KeyUsage.digitalSignature)
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der)))
    }

    @Test
    fun `an unknown critical extension is untrusted`() {
        val root = ca("root")
        val inter = ca("inter", root, criticalUnknown = true)
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der)))
        val signer2 = leaf("signer2", root, criticalUnknown = true)
        assertEquals(untrusted, run(signer2, listOf(signer2), listOf(root.der)))
    }

    @Test
    fun `a path longer than 8 certificates is untrusted`() {
        val (root, signer, carried) = chain(intermediates = 7)
        assertEquals(untrusted, run(signer, carried, listOf(root.der)))
    }

    @Test
    fun `an empty anchor list is untrusted`() {
        val signer = leaf("signer", null)
        assertEquals(untrusted, run(signer, listOf(signer), emptyList()))
    }

    // ---- validity at the injected instant -------------------------------------------------------------

    private val late = now.plus(20, ChronoUnit.DAYS)
    private val soon = now.plus(10, ChronoUnit.DAYS)

    @Test
    fun `an expired signer is untrusted`() {
        val root = ca("root")
        val signer = leaf("signer", root, notAfter = soon)
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der), at = late))
    }

    @Test
    fun `a not-yet-valid signer is untrusted`() {
        val root = ca("root")
        val signer = leaf("signer", root, notBefore = now.minus(30, ChronoUnit.DAYS))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der), at = now.minus(60, ChronoUnit.DAYS)))
    }

    @Test
    fun `an expired intermediate is untrusted`() {
        val root = ca("root")
        val inter = ca("inter", root, notAfter = soon)
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der), at = late))
    }

    @Test
    fun `an expired anchor is untrusted`() {
        val root = ca("root", notAfter = soon)
        val signer = leaf("signer", root)
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der), at = late))
    }

    @Test
    fun `an expired self-listed signing certificate is untrusted`() {
        val signer = leaf("signer", null, notAfter = soon)
        assertEquals(untrusted, run(signer, listOf(signer), listOf(signer.der), at = late))
    }

    @Test
    fun `the same PKI is accepted at an instant inside every validity`() {
        val root = ca("root")
        val signer = leaf("signer", root)
        assertEquals(verified(0), run(signer, listOf(signer), listOf(root.der), at = now.plus(1, ChronoUnit.HOURS)))
    }

    // ---- signature problems ---------------------------------------------------------------------------

    @Test
    fun `tampered content is a signature failure`() {
        val root = ca("root")
        val signer = leaf("signer", root)
        val outcome = run(signer, listOf(signer), listOf(root.der), signed = content + 1)
        assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
    }

    @Test
    fun `another algorithm is a signature failure`() {
        val root = ca("root")
        val signer = leaf("signer", root)
        val outcome = run(signer, listOf(signer), listOf(root.der), signatureName = "SHA384withECDSA")
        assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
    }

    @Test
    fun `a malformed signature is a signature failure`() {
        val root = ca("root")
        val outcome = cms.verify(content, byteArrayOf(1, 2, 3), alg, listOf(root.der), now)
        assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    private fun verified(index: Int) = DetachedVerification.Verified(index)

    private fun run(
        signer: Node,
        carried: List<Node>,
        anchors: List<ByteArray>,
        at: Instant = now,
        signed: ByteArray = content,
        signatureName: String = "SHA256withECDSA",
    ): DetachedVerification {
        val digests = JcaDigestCalculatorProviderBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
        val contentSigner =
            JcaContentSignerBuilder(signatureName)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(signer.pair.private)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(SignerInfoGeneratorBuilder(digests).build(contentSigner, signer.cert))
        generator.addCertificates(JcaCertStore(carried.map { it.cert }))
        val signature = generator.generate(CMSProcessableByteArray(content), false).encoded
        return cms.verify(signed, signature, alg, anchors, at)
    }

    /** A root, [intermediates] CAs below it and a signer: the root, the signer and the certificates to carry. */
    private fun chain(intermediates: Int): Triple<Node, Node, List<Node>> {
        val root = ca("root")
        var parent = root
        val carried = mutableListOf<Node>()
        repeat(intermediates) {
            parent = ca("inter-$it", parent)
            carried += parent
        }
        val signer = leaf("signer", parent)
        return Triple(root, signer, listOf(signer) + carried)
    }

    private fun ca(
        name: String,
        issuer: Node? = null,
        pathLen: Int? = null,
        isCa: Boolean = true,
        usage: Int = KeyUsage.keyCertSign or KeyUsage.cRLSign,
        nameOfIssuer: X500Name? = null,
        notBefore: Instant = now.minus(90, ChronoUnit.DAYS),
        notAfter: Instant = now.plus(30, ChronoUnit.DAYS),
        criticalUnknown: Boolean = false,
    ): Node =
        issue(
            name,
            issuer,
            if (pathLen == null) BasicConstraints(isCa) else BasicConstraints(pathLen),
            usage,
            nameOfIssuer,
            notBefore,
            notAfter,
            criticalUnknown,
        )

    /** A signing certificate; [issuer] `null` makes it self-signed. */
    private fun leaf(
        name: String,
        issuer: Node?,
        usage: Int = KeyUsage.digitalSignature,
        notBefore: Instant = now.minus(90, ChronoUnit.DAYS),
        notAfter: Instant = now.plus(30, ChronoUnit.DAYS),
        criticalUnknown: Boolean = false,
        nameOfIssuer: X500Name? = null,
    ): Node = issue(name, issuer, null, usage, nameOfIssuer, notBefore, notAfter, criticalUnknown)

    private fun issue(
        name: String,
        issuer: Node?,
        basic: BasicConstraints?,
        usage: Int,
        issuerName: X500Name?,
        notBefore: Instant,
        notAfter: Instant,
        criticalUnknown: Boolean,
    ): Node {
        val pair = keyPair()
        val subject = X500Name("CN=$name")
        val signingPair = issuer?.pair ?: pair
        val issuerX500 = issuerName ?: issuer?.cert?.subject ?: subject
        val builder =
            JcaX509v3CertificateBuilder(
                issuerX500,
                BigInteger.valueOf(serial.incrementAndGet()),
                Date.from(notBefore),
                Date.from(notAfter),
                subject,
                pair.public,
            )
        if (basic != null) {
            builder.addExtension(Extension.basicConstraints, true, basic)
        }
        builder.addExtension(Extension.keyUsage, true, KeyUsage(usage))
        if (criticalUnknown) {
            builder.addExtension(ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1"), true, DEROctetString(byteArrayOf(1)))
        }
        val signer =
            JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(signingPair.private)
        return Node(name, pair, builder.build(signer))
    }

    private fun keyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private companion object {
        val serial = AtomicLong(1000)

        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}
