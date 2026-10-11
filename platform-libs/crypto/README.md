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
  - Ports `DetachedSigner` and `DetachedSignatureVerifier`, `SigningKey`, `SignatureAlgorithm.ECDSA_P256_SHA256` (STK-13), `DetachedVerification`.
- `crypto` (impl): `BouncyCastleCms` implements both ports; `JcaSigningKey` wraps a JCA private key (key file, or an HSM through PKCS#11). A KMS key implements `SigningKey` itself: the private key never leaves its holder.

Verification pins the signer to an exact trusted certificate (byte for byte), never to a certificate the message merely carries, and refuses a signature whose algorithm, digest or key curve is not the named one.
