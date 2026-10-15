package com.dynokore.platform.crypto

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.SignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.bouncycastle.util.Selector
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.Provider
import java.security.Security
import java.time.Instant

/**
 * Detached CMS sign and verify over Bouncy Castle (STK-13): `SignedData` without encapsulated
 * content, one `SignerInfo`, the signer's certificate carried in the message, and on verification
 * the signer pinned to an exact trusted certificate (see [DetachedSignatureVerifier]).
 *
 * [provider] is the JCA provider every digest and signature check runs on. It defaults to the
 * Bouncy Castle provider already registered in this JVM, or a new one. A FIPS 140-3 installation
 * binds its validated provider (for example Bouncy Castle FIPS) here, with no code change.
 */
class BouncyCastleCms(
    private val provider: Provider = defaultProvider(),
) : DetachedSigner,
    DetachedSignatureVerifier,
    ChainedSignatureVerifier {
    override fun sign(
        content: ByteArray,
        key: SigningKey,
    ): ByteArray {
        val certificate = X509CertificateHolder(key.certificateDer)
        val digestProvider =
            JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
        val signerInfoGenerator =
            SignerInfoGeneratorBuilder(digestProvider).build(SigningKeyContentSigner(key), certificate)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(signerInfoGenerator)
        generator.addCertificates(JcaCertStore(listOf(certificate)))
        // false: detached, the content travels separately and is not encapsulated in the signature
        return generator.generate(CMSProcessableByteArray(content), false).encoded
    }

    override fun verify(
        content: ByteArray,
        signature: ByteArray,
        algorithm: SignatureAlgorithm,
        trustedCertificatesDer: List<ByteArray>,
    ): DetachedVerification =
        when (val checked = checkSignature(content, signature, algorithm)) {
            is SignatureCheck.Failed -> checked.outcome
            is SignatureCheck.Signed -> trustedSigner(checked.signer, trustedCertificatesDer)
        }

    override fun verify(
        content: ByteArray,
        signature: ByteArray,
        algorithm: SignatureAlgorithm,
        trustAnchorsDer: List<ByteArray>,
        at: Instant,
    ): DetachedVerification =
        when (val checked = checkSignature(content, signature, algorithm)) {
            is SignatureCheck.Failed -> checked.outcome
            is SignatureCheck.Signed -> chainedSigner(checked, trustAnchorsDer, at, algorithm)
        }

    private fun chainedSigner(
        checked: SignatureCheck.Signed,
        trustAnchorsDer: List<ByteArray>,
        at: Instant,
        algorithm: SignatureAlgorithm,
    ): DetachedVerification {
        val signer =
            try {
                CertificatePathValidator.parseCertificate(provider, checked.signer.encoded)
            } catch (failure: IOException) {
                return invalid("signer certificate could not be re-encoded: ${failure.message}")
            } ?: return invalid("signer certificate is not a parseable X.509 certificate")
        if (checked.carried.size > CertificatePathValidator.MAX_CARRIED + 1) {
            return invalid(
                "message carries ${checked.carried.size - 1} certificates, more than " +
                    "${CertificatePathValidator.MAX_CARRIED} besides the signer",
            )
        }
        val carried =
            checked.carried.mapNotNull {
                try {
                    CertificatePathValidator.parseCertificate(provider, it.encoded)
                } catch (_: IOException) {
                    null
                }
            }
        val index =
            CertificatePathValidator(
                provider,
                trustAnchorsDer,
                at,
                algorithm.signatureOid,
            ).trustedIndex(signer, carried)
        return if (index == null) DetachedVerification.UntrustedSigner else DetachedVerification.Verified(index)
    }

    /** The signature steps shared by both verifiers: parse, name the signer, check algorithm, check the signature. */
    @Suppress("ReturnCount") // CS-50: one early return per verification step reads as the documented step order
    private fun checkSignature(
        content: ByteArray,
        signature: ByteArray,
        algorithm: SignatureAlgorithm,
    ): SignatureCheck {
        val signedData =
            try {
                CMSSignedData(CMSProcessableByteArray(content), signature)
            } catch (failure: CMSException) {
                return failed("not a well-formed CMS SignedData: ${failure.message}")
            }

        val signerInfo =
            signedData.signerInfos.signers.firstOrNull()
                ?: return failed("CMS SignedData carries no SignerInfo")

        @Suppress("UNCHECKED_CAST") // CS-50: bc-java's SignerId implements the raw (unparameterised) Selector type
        val signerIdSelector = signerInfo.getSID() as Selector<X509CertificateHolder>
        val signerCertificate =
            signedData.certificates
                .getMatches(signerIdSelector)
                .filterIsInstance<X509CertificateHolder>()
                .firstOrNull()
                ?: return failed("no certificate in the CMS message matches its own SignerInfo")

        algorithmMismatch(algorithm, signerInfo.encryptionAlgOID, signerInfo.digestAlgOID, signerCertificate)
            ?.let { return failed(it) }

        val mathematicallyValid =
            try {
                val verifier =
                    JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider(provider)
                        .build(signerCertificate)
                signerInfo.verify(verifier)
            } catch (failure: CMSException) {
                return failed("signature verification failed: ${failure.message}")
            }
        if (!mathematicallyValid) {
            return failed("CMS signature does not verify against its own signer certificate")
        }
        return SignatureCheck.Signed(
            signerCertificate,
            signedData.certificates.getMatches(null).filterIsInstance<X509CertificateHolder>(),
        )
    }

    private fun failed(detail: String): SignatureCheck.Failed = SignatureCheck.Failed(invalid(detail))

    private sealed interface SignatureCheck {
        class Failed(
            val outcome: DetachedVerification,
        ) : SignatureCheck

        class Signed(
            val signer: X509CertificateHolder,
            val carried: List<X509CertificateHolder>,
        ) : SignatureCheck
    }

    /** The last verification step: the signer certificate's position among the trusted certificates, if any. */
    private fun trustedSigner(
        signerCertificate: X509CertificateHolder,
        trustedCertificatesDer: List<ByteArray>,
    ): DetachedVerification {
        val signerCertificateDer =
            try {
                signerCertificate.encoded
            } catch (failure: IOException) {
                return invalid("signer certificate could not be re-encoded: ${failure.message}")
            }
        val trustedIndex = trustedCertificatesDer.indexOfFirst { it.contentEquals(signerCertificateDer) }
        return if (trustedIndex < 0) {
            DetachedVerification.UntrustedSigner
        } else {
            DetachedVerification.Verified(trustedIndex)
        }
    }

    /** Why the signer's algorithms differ from [algorithm], or `null` when they match. */
    private fun algorithmMismatch(
        algorithm: SignatureAlgorithm,
        signatureOid: String,
        digestOid: String,
        signerCertificate: X509CertificateHolder,
    ): String? {
        val keyParameters = signerCertificate.subjectPublicKeyInfo.algorithm.parameters
        val curveOid = (keyParameters as? ASN1ObjectIdentifier)?.id
        return when {
            signatureOid != algorithm.signatureOid -> {
                "signer uses signature algorithm $signatureOid, not ${algorithm.name}"
            }

            digestOid != algorithm.digestOid -> {
                "signer uses digest algorithm $digestOid, not ${algorithm.name}"
            }

            curveOid != algorithm.curveOid -> {
                "signer key is not on the ${algorithm.name} curve (key parameters $curveOid)"
            }

            else -> {
                null
            }
        }
    }

    private fun invalid(detail: String): DetachedVerification = DetachedVerification.SignatureInvalid(detail)

    private companion object {
        fun defaultProvider(): Provider =
            Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) ?: BouncyCastleProvider()
    }

    /** A Bouncy Castle [ContentSigner] that buffers the bytes to sign and hands them to a [SigningKey]. */
    private class SigningKeyContentSigner(
        private val key: SigningKey,
    ) : ContentSigner {
        private val buffer = ByteArrayOutputStream()

        override fun getAlgorithmIdentifier(): AlgorithmIdentifier =
            AlgorithmIdentifier(ASN1ObjectIdentifier(key.algorithm.signatureOid))

        override fun getOutputStream(): OutputStream = buffer

        override fun getSignature(): ByteArray = key.sign(buffer.toByteArray())
    }
}
