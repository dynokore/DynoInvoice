# testcontainers-pins

INV-T-5604. The one table that pins the Testcontainers images of the platform-libs tests by digest. Test only: no
publication, not in `platform-libs-bom`.

## How it works

- `ImagePins.kt` maps exactly `postgres:16`, `postgres:17` and `postgres:18` to `postgres:<tag>@sha256:<digest of the
  multi-arch index>`, and holds the Ryuk reaper reference (Testcontainers 1.21.4's default `testcontainers/ryuk:0.12.0`).
- `src/main/resources/testcontainers.properties` registers `PinnedImageNameSubstitutor` (`image.substitutor`) and sets
  `ryuk.container.image` to the pinned Ryuk reference.
- The root `build.gradle.kts` adds this module's jar to the classpath of every `Test` task of the other modules.
- The substitutor returns a reference that already contains `@sha256:` unchanged (MinIO, Ryuk), resolves the three table
  keys, and throws for anything else, so a test naming an unpinned image fails closed.

## Digests read on 2026-10-16

Source: the registry index digest (`docker buildx imagetools inspect <image>`, line `Digest:`).

| Reference | Digest |
| --- | --- |
| `postgres:16` | `sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d` |
| `postgres:17` | `sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3` |
| `postgres:18` | `sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336` |
| `testcontainers/ryuk:0.12.0` | `sha256:dd3f023a6ed7015b3f95a49ccd65a2daf0c56e681422c12952b19a810dfa6298` |

## How to bump

1. Read the new index digest with `docker buildx imagetools inspect postgres:<tag>` (use the `Digest:` of the index, not a
   per-platform manifest).
2. Edit `ImagePins.kt`; for Ryuk edit `ImagePins.RYUK` and `ryuk.container.image` in `testcontainers.properties` to the same
   value (a unit test compares them).
3. Update the date and the table above, then run `:testcontainers-pins:test`.
4. After a Testcontainers upgrade, check the default Ryuk image (`RyukContainer` in the new jar) and pin that one.
