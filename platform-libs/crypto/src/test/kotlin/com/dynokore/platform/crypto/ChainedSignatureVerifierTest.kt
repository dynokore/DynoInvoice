package com.dynokore.platform.crypto

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
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
import org.junit.jupiter.api.Assertions.assertTrue
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
        val inter = ca("inter", impostor, Opts(nameOfIssuer = root.cert.subject))
        val lower = leaf("signer2", inter)
        assertEquals(untrusted, run(lower, listOf(lower, inter), listOf(root.der)))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der)))
    }

    @Test
    fun `a link whose issuer name differs from the next subject is untrusted although the key signed it`() {
        val root = ca("root")
        val signer = leaf("signer", root, Opts(nameOfIssuer = X500Name("CN=somebody-else")))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der)))
    }

    @Test
    fun `an intermediate that is not a CA is untrusted`() {
        val root = ca("root")
        val inter = ca("inter", root, Opts(isCa = false))
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der)))
    }

    @Test
    fun `an intermediate without keyCertSign is untrusted`() {
        val root = ca("root")
        val inter = ca("inter", root, Opts(usage = KeyUsage.digitalSignature))
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der)))
    }

    @Test
    fun `an exceeded pathLenConstraint is untrusted`() {
        val root = ca("root", opts = Opts(pathLen = 0))
        val inter = ca("inter", root)
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der)))
    }

    @Test
    fun `a pathLenConstraint that is met is accepted`() {
        val root = ca("root", opts = Opts(pathLen = 1))
        val inter = ca("inter", root, Opts(pathLen = 0))
        val signer = leaf("signer", inter)
        assertEquals(verified(0), run(signer, listOf(signer, inter), listOf(root.der)))
    }

    @Test
    fun `a signer without digitalSignature is untrusted`() {
        val root = ca("root")
        val signer = leaf("signer", root, Opts(usage = KeyUsage.keyEncipherment))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der)))
    }

    @Test
    fun `a CA certificate as the signer is untrusted`() {
        val root = ca("root")
        val signer = ca("signing-ca", root, Opts(usage = KeyUsage.keyCertSign or KeyUsage.digitalSignature))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der)))
    }

    @Test
    fun `an unknown critical extension is untrusted`() {
        val root = ca("root")
        val inter = ca("inter", root, Opts(criticalUnknown = true))
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der)))
        val signer2 = leaf("signer2", root, Opts(criticalUnknown = true))
        assertEquals(untrusted, run(signer2, listOf(signer2), listOf(root.der)))
    }

    @Test
    fun `a path longer than 8 certificates is untrusted`() {
        val (root, signer, carried) = chain(intermediates = 7)
        assertEquals(untrusted, run(signer, carried, listOf(root.der)))
    }

    @Test
    fun `about 40 self-issued CAs are refused quickly`() {
        val root = ca("root")
        val signer = leaf("signer", ca("unrelated"))
        // same subject, each its own key: every one is a candidate issuer of every other
        val shared = keyPair()
        val first = ca("crowd", opts = Opts(pair = shared))
        val crowd = listOf(first) + List(39) { ca("crowd", first, Opts(pair = shared)) }
        val started = System.nanoTime()
        val outcome = run(signer, listOf(signer) + crowd, listOf(root.der))
        assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
        assertTrue((System.nanoTime() - started) < 20_000_000_000L)
    }

    @Test
    fun `a bounded crowd of look-alike CAs is searched to a refusal in bounded time`() {
        val root = ca("root")
        val shared = keyPair()
        val first = ca("crowd", opts = Opts(pair = shared))
        // cross-certificates: one name and one key, many certificates, each valid under every other
        val crowd = listOf(first) + List(14) { ca("crowd", first, Opts(pair = shared)) }
        val signer = leaf("signer", first)
        val started = System.nanoTime()
        assertEquals(untrusted, run(signer, listOf(signer) + crowd, listOf(root.der)))
        assertTrue((System.nanoTime() - started) < 20_000_000_000L)
    }

    @Test
    fun `a link signed with another signature algorithm is untrusted`() {
        val root = ca("root")
        val signer = leaf("signer", root, Opts(linkSignature = "SHA384withECDSA"))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der)))
    }

    @Test
    fun `a CA signer that is itself the listed anchor is untrusted`() {
        val signer = ca("signing-ca", null, Opts(usage = KeyUsage.keyCertSign or KeyUsage.digitalSignature))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(signer.der)))
    }

    @Test
    fun `a critical extendedKeyUsage is untrusted and a non-critical one is ignored`() {
        val root = ca("root")
        val inter = ca("inter", root, Opts(eku = true))
        val underCritical = leaf("signer", inter)
        assertEquals(untrusted, run(underCritical, listOf(underCritical, inter), listOf(root.der)))
        val critical = leaf("signer2", root, Opts(eku = true))
        assertEquals(untrusted, run(critical, listOf(critical), listOf(root.der)))
        val relaxedInter = ca("inter2", root, Opts(eku = false))
        val relaxed = leaf("signer3", relaxedInter, Opts(eku = false))
        assertEquals(verified(0), run(relaxed, listOf(relaxed, relaxedInter), listOf(root.der)))
    }

    @Test
    fun `a self-issued intermediate counts against pathLenConstraint`() {
        val strict = ca("root", opts = Opts(pathLen = 0))
        val strictRollover = ca("root", strict) // self-issued: issuer name equals subject name
        val strictSigner = leaf("signer", strictRollover)
        assertEquals(untrusted, run(strictSigner, listOf(strictSigner, strictRollover), listOf(strict.der)))
        val relaxed = ca("root", opts = Opts(pathLen = 1))
        val relaxedRollover = ca("root", relaxed)
        val relaxedSigner = leaf("signer2", relaxedRollover)
        assertEquals(verified(0), run(relaxedSigner, listOf(relaxedSigner, relaxedRollover), listOf(relaxed.der)))
    }

    @Test
    fun `16 carried certificates besides the signer are searched and 17 are refused`() {
        val root = ca("root")
        val signer = leaf("signer", root)
        val sixteen = List(16) { ca("other-$it") }
        assertEquals(verified(0), run(signer, listOf(signer) + sixteen, listOf(root.der)))
        val outcome = run(signer, listOf(signer) + sixteen + ca("other-16"), listOf(root.der))
        val invalid = assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
        assertEquals("message carries 17 certificates, more than 16 besides the signer", invalid.detail)
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
        val signer = leaf("signer", root, Opts(notAfter = soon))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der), at = late))
    }

    @Test
    fun `a not-yet-valid signer is untrusted`() {
        val root = ca("root")
        val signer = leaf("signer", root, Opts(notBefore = now.minus(30, ChronoUnit.DAYS)))
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der), at = now.minus(60, ChronoUnit.DAYS)))
    }

    @Test
    fun `an expired intermediate is untrusted`() {
        val root = ca("root")
        val inter = ca("inter", root, Opts(notAfter = soon))
        val signer = leaf("signer", inter)
        assertEquals(untrusted, run(signer, listOf(signer, inter), listOf(root.der), at = late))
    }

    @Test
    fun `an expired anchor is untrusted`() {
        val root = ca("root", opts = Opts(notAfter = soon))
        val signer = leaf("signer", root)
        assertEquals(untrusted, run(signer, listOf(signer), listOf(root.der), at = late))
    }

    @Test
    fun `an expired self-listed signing certificate is untrusted`() {
        val signer = leaf("signer", null, Opts(notAfter = soon))
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
        val outcome = run(signer, listOf(signer), listOf(root.der), options = RunOptions(signed = content + 1))
        assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
    }

    @Test
    fun `another algorithm is a signature failure`() {
        val root = ca("root")
        val signer = leaf("signer", root)
        val options = RunOptions(signatureName = "SHA384withECDSA")
        val outcome = run(signer, listOf(signer), listOf(root.der), options = options)
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
        options: RunOptions = RunOptions(),
    ): DetachedVerification {
        val digests = JcaDigestCalculatorProviderBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
        val contentSigner =
            JcaContentSignerBuilder(options.signatureName)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(signer.pair.private)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(SignerInfoGeneratorBuilder(digests).build(contentSigner, signer.cert))
        generator.addCertificates(JcaCertStore(carried.map { it.cert }))
        val signature = generator.generate(CMSProcessableByteArray(content), false).encoded
        return cms.verify(options.signed ?: content, signature, alg, anchors, at)
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
        opts: Opts = Opts(),
    ): Node =
        issue(
            name,
            issuer,
            if (opts.pathLen == null) BasicConstraints(opts.isCa) else BasicConstraints(opts.pathLen),
            opts.usage ?: (KeyUsage.keyCertSign or KeyUsage.cRLSign),
            opts,
        )

    /** A signing certificate; [issuer] `null` makes it self-signed. */
    private fun leaf(
        name: String,
        issuer: Node?,
        opts: Opts = Opts(),
    ): Node = issue(name, issuer, null, opts.usage ?: KeyUsage.digitalSignature, opts)

    private fun issue(
        name: String,
        issuer: Node?,
        basic: BasicConstraints?,
        usage: Int,
        opts: Opts,
    ): Node {
        val pair = opts.pair ?: keyPair()
        val subject = X500Name("CN=$name")
        val signingPair = issuer?.pair ?: pair
        val issuerX500 = opts.nameOfIssuer ?: issuer?.cert?.subject ?: subject
        val builder =
            JcaX509v3CertificateBuilder(
                issuerX500,
                BigInteger.valueOf(serial.incrementAndGet()),
                Date.from(opts.notBefore ?: now.minus(90, ChronoUnit.DAYS)),
                Date.from(opts.notAfter ?: now.plus(30, ChronoUnit.DAYS)),
                subject,
                pair.public,
            )
        if (basic != null) {
            builder.addExtension(Extension.basicConstraints, true, basic)
        }
        builder.addExtension(Extension.keyUsage, true, KeyUsage(usage))
        if (opts.eku != null) {
            builder.addExtension(Extension.extendedKeyUsage, opts.eku, ExtendedKeyUsage(KeyPurposeId.id_kp_codeSigning))
        }
        if (opts.criticalUnknown) {
            builder.addExtension(ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1"), true, DEROctetString(byteArrayOf(1)))
        }
        val signer =
            JcaContentSignerBuilder(opts.linkSignature)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(signingPair.private)
        return Node(name, pair, builder.build(signer))
    }

    /** The optional properties of a certificate; a `null` date or usage takes the default of [ca] or [leaf]. */
    private data class Opts(
        val pathLen: Int? = null,
        val isCa: Boolean = true,
        val usage: Int? = null,
        val nameOfIssuer: X500Name? = null,
        val notBefore: Instant? = null,
        val notAfter: Instant? = null,
        val criticalUnknown: Boolean = false,
        val pair: KeyPair? = null,
        val eku: Boolean? = null,
        val linkSignature: String = "SHA256withECDSA",
    )

    /** The bytes the verifier is given (`null`: the signed content) and the signature algorithm. */
    private class RunOptions(
        val signed: ByteArray? = null,
        val signatureName: String = "SHA256withECDSA",
    )

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
