# gateway-authz

INV gateway-authz: extAuth token and revocation validator in the API gateway pod (INV-ADR-4051, INV-MOD-2752).

This repository currently holds the Gradle build only: the platform-build conventions (`gst.bootstrap`, Spring Boot),
dependency locking and strict dependency verification (STK-72). The service code and the image come with INV-T-4056.

## Build

`settings.gradle.kts` reads the platform-build release from the `platformBuild` line of `gradle/libs.versions.toml`
(a release published in platform-build's package registry, project 225). In CI the job token reads the registries;
locally export `GST_REGISTRY_TOKEN`, a personal access token with `read_api`.

```sh
./gradlew check
./gradlew build --dependency-verification strict
```

## Dependency locking and verification

`gradle.lockfile` locks every configuration. Every artefact the build downloads, plugins, POMs and module metadata
included, is verified against `gradle/verification-metadata.xml` in strict mode, so an artefact without a matching
entry fails the build. No build script or CI job disables verification.

Key servers are off, as in platform-build: CI has no key-server egress. This repository commits no keyring, so each
signing key is an `ignored-key` with a reason and its artefact is pinned by SHA-256. Check every new SHA-256 against
the checksum the artefact's repository publishes before you commit it.

When a dependency or plugin changes:

1. Use an empty Gradle user home (for example `--gradle-user-home "$(mktemp -d)"`). With a warm cache, artefacts that
   are already cached get no entry, and CI fails.
2. Write the locks and the checksums in one pass, naming only tasks that exist in this build:

   ```sh
   ./gradlew compileKotlin compileTestKotlin check test --continue \
     --write-locks --write-verification-metadata sha256 --no-configuration-cache
   ```

3. Run the CI resolve-all and licence-input init scripts of platform-build (`ci/build.gitlab-ci.yml`) the same way, then
   add an `ignored-key` for each new signing key, and keep the comment in the metadata's `configuration` block.
4. Delete the `settings-gradle.lockfile` Gradle writes for the version catalogue (it holds only
   `empty=incomingCatalogForLibs0` and is not committed).
