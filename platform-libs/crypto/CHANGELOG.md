# Changelog

All notable changes to `crypto` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.1.1] - 2026-10-19
### Changed
- detekt type-resolution findings fixed (platform-build 0.21.0, INV-Q-4629); no behaviour or API change

## [1.1.0] - 2026-10-15
### Added
- `ChainedSignatureVerifier` port in `crypto-api` and its implementation in `BouncyCastleCms` (INV-T-5097,
  coordinator ruling INV-Q-5093 A, owner ruling INV-Q-3371 A, INV-REQ-1127, INV-REQ-1134): a detached CMS signature is
  trusted when a valid certificate path leads from the signer certificate the message names, through the certificates
  the message carries (candidates only, never trusted), to a certificate byte-equal to one in the caller's trust anchor
  list, at the instant the caller passes. Rules, each failing closed: issuer name equals next subject name and the
  signature verifies under the next key; every certificate on the path, anchor included, is valid at the instant;
  every issuer has basicConstraints CA=true, keyCertSign when keyUsage is present, and an unexceeded pathLenConstraint;
  the signer has digitalSignature when keyUsage is present and is not a CA certificate; an unknown critical extension
  is refused; the path has at most 8 certificates; an empty anchor list is `UntrustedSigner`. No revocation check
  (CRL, OCSP): removing an anchor from the installation's set is the revocation path (INV-Q-5093).
- `DetachedSignatureVerifier` (exact-certificate pinning) is unchanged.

## [1.0.2] - 2026-10-14
### Changed
- Metadata-only release (INV-T-4310, coordinator ruling INV-Q-4552): no behaviour or API change.
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426, INV-T-4556); formatting only.

## [1.0.1] - 2026-10-13
### Fixed
- The published impl POM (`crypto`) declared no dependency (INV-T-4356; defect in INV-T-2860's and INV-T-3937's
  per-module publishing, STK-60, STK-01 rule (a), TPL-01): the `impl` publication was built with `artifact(implJar)`
  and no software component, so a consumer declaring platform-libs-bom and this impl artefact had to list `crypto-api`
  and the other modules itself. The impl POM now declares `crypto-api` at the same version and the first-level
  dependencies of the impl runtime classpath, generated and checked by the root build.gradle.kts (`verifyImplPoms`):
  crypto-api:1.0.1 (compile), org.bouncycastle:bcpkix-jdk18on:1.86 (runtime), org.bouncycastle:bcprov-jdk18on:1.86
  (runtime), org.jetbrains.kotlin:kotlin-stdlib:2.4.20 (runtime). The classes are unchanged.

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
