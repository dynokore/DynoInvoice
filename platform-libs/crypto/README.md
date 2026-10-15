# crypto

Hashing, HMAC, secure random tokens and detached CMS signatures for the whole platform, each under a named algorithm (CS-53; INV-Q-3356). Callers name an algorithm; they never configure cryptography themselves, and no `MessageDigest.getInstance` or `javax.crypto` call sits outside this module.

| | |
| --- | --- |
| Layer | L1 (the lowest: uses no other module) |
| Embedded by | `evidence-store` (content hashes), `rulepack-kernel` (pack signatures, STK-13); every service that hashes, MACs, issues tokens or signs (configuration bundles INV-REQ-1127, certification reports INV-REQ-1134) |
| Uses | Bouncy Castle (impl half only) |
| Wave | Wave 1: Contracts, harness and platform |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## Use

- `crypto-api` (pure JDK):
  - `Hashing.digest/hex(HashAlgorithm.SHA_256, bytes)`, `Hex.encode`.
  - `Macs.tag/verify(MacKey(MacAlgorithm.HMAC_SHA_256, key), data)`; `verify` compares in constant time.
  - `SecureTokens.randomBytes`, `urlSafeToken`, `numericPasscode`, all from `SecureRandom`.
  - `ConstantTime.equals` for any MAC, digest or passcode comparison.
  - Ports `DetachedSigner`, `DetachedSignatureVerifier` and `ChainedSignatureVerifier`, `SigningKey`, `SignatureAlgorithm.ECDSA_P256_SHA256` (STK-13), `DetachedVerification`.
- `crypto` (impl): `BouncyCastleCms(provider)` implements both ports on the given JCA provider (default: Bouncy Castle; bind a FIPS 140-3 provider for a FIPS installation); `JcaSigningKey` wraps a JCA private key (key file, or an HSM through PKCS#11). A KMS key implements `SigningKey` itself: the private key never leaves its holder.

Verification pins the signer to an exact trusted certificate (byte for byte), never to a certificate the message merely carries, and refuses a signature whose algorithm, digest or key curve is not the named one.

## Chained verification (`ChainedSignatureVerifier`, 1.1.0)

`verify(content, signature, algorithm, trustAnchorsDer, at)` trusts the signer through a certificate path to the caller's trust anchor set instead of a pin (INV-Q-3371 A, INV-Q-5093 A). `BouncyCastleCms` implements it on the given JCA provider. The signature checks come first and give `SignatureInvalid`; the path rules then give `UntrustedSigner` on the first failure:

- the signer is the certificate the message's `SignerInfo` names; the certificates the message carries are candidate intermediates only and are never trusted;
- the path runs from the signer through the candidates to a certificate equal, byte for byte, to one in `trustAnchorsDer` (a root, a CA, or the signing certificate itself); `Verified(trustedIndex)` is that anchor's index; at most 8 certificates;
- every link: issuer name equals the next subject name and the signature verifies under the next certificate's key;
- every certificate on the path, anchor included, is valid at `at`, the instant the caller passes (the module never reads the clock);
- every issuer has basicConstraints CA=true, keyCertSign if keyUsage is present, and its pathLenConstraint is not exceeded;
- the signer has digitalSignature if keyUsage is present and is not a CA certificate (basicConstraints CA=true);
- a certificate with an unknown critical extension is refused (understood: basicConstraints, keyUsage, subjectAltName);
- an empty `trustAnchorsDer` gives `UntrustedSigner`.

No revocation check (CRL, OCSP) is made: no published text requires one, and removing an anchor from the installation's set is the revocation path (INV-Q-5093).
