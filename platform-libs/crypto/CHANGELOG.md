# Changelog

All notable changes to `crypto` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-10-11
### Added
- The platform crypto module (CS-53; INV-Q-3356 M-A; INV-T-3358 / FU-E2). Every algorithm is named
  by the caller from an enum; no caller configures cryptography itself.
- `crypto-api`: `Hashing` (`HashAlgorithm.SHA_256`) and `Hex`; `Macs` with `MacKey`
  (`MacAlgorithm.HMAC_SHA_256`, key at least the tag length); `SecureTokens` (random bytes,
  URL-safe tokens of at least 128 bits, numeric passcodes, all from `SecureRandom`); `ConstantTime`
  (`MessageDigest.isEqual`); the `DetachedSigner` and `DetachedSignatureVerifier` ports,
  `SigningKey`, `SignatureAlgorithm.ECDSA_P256_SHA256` (STK-13) and `DetachedVerification`.
- `crypto` (impl): `BouncyCastleCms`, detached CMS `SignedData` sign and verify (STK-13); the
  verifier refuses a signer whose signature algorithm, digest or key curve is not the named
  algorithm, and pins the signer to an exact trusted certificate. Its JCA `Provider` is a
  constructor parameter (default: the registered Bouncy Castle provider), so a FIPS 140-3 provider
  is a binding choice, not a code change. `JcaSigningKey` signs with a JCA
  private key (key file or PKCS#11 HSM); a KMS key implements `SigningKey` itself.
