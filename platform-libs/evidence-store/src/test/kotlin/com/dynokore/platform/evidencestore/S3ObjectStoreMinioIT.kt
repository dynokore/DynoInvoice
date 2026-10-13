package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ObjectLockRetention
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The done-when's own object-lock proof: "integration test against the
 * object-lock simulator image once available, else MinIO in Testcontainers"
 * — no `certification-kit` image exists yet (AGT-13), so this is MinIO.
 * `S3ObjectStore` itself is coded only against the AWS SDK v2 S3 API (STK-20)
 * — MinIO never appears above this one test file, and `io.minio` is never a
 * dependency at all (banned-dependencies.yaml).
 */
@Tag("integration")
class S3ObjectStoreMinioIT {
    companion object {
        // `minio/minio` no longer exists on Docker Hub, and its current home
        // (`quay.io/minio/minio`) now needs an authenticated pull — confirmed:
        // 401 on the manifest even anonymously, so it cannot run on inv-runner
        // as `:latest` was (review must-fix on !5). This is the coordinator's
        // chosen replacement (AGT-51 decision, not this coder's own pick):
        // Chainguard's public MinIO build, anonymously pullable, and pinned by
        // digest rather than a tag (CS-50 — a floating tag re-opens the exact
        // failure this fix is for; a name-only "latest" is never trustworthy
        // for a required CI job). Verified pullable and already cached on the
        // inv-runner host. The guarantee this test proves is the S3 Object
        // Lock API `S3ObjectStore` codes against (STK-20), not anything
        // specific to MinIO's own distribution, so a different anonymous-pull
        // image satisfying the same Object Lock semantics is a drop-in
        // replacement here, never a change to `S3ObjectStore`.
        private const val MINIO_IMAGE =
            "cgr.dev/chainguard/minio@sha256:bd014394a80898e68c149f2311fdf8d5a2c2f3bb2c33b9327ae6d02b4b065ae1"

        private fun minioClient(container: MinIOContainer): S3Client =
            S3Client
                .builder()
                .endpointOverride(URI.create(container.s3URL))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(container.userName, container.password),
                    ),
                ).forcePathStyle(true)
                .build()

        // Refuse delete: the port itself has no delete method (ObjectStore.kt); this proves the
        // *server* refuses one too, attempted directly against the underlying client, bypassing
        // the port entirely — the STK-20 conformance probe's own "refuse delete". Object lock
        // requires bucket versioning, under which a bare delete (no version id) merely adds a
        // delete marker and leaves the protected version intact — not a WORM violation, so not
        // what this proves. The real guarantee is that the server refuses to remove *that locked
        // version itself*, so the delete targets it by version id explicitly (verified against
        // this same image with a boto3 probe: a bare delete succeeds/no-ops, a version-targeted
        // delete of a WORM-protected version is refused).
        private fun assertVersionedDeleteRefused(
            client: S3Client,
            bucket: String,
            key: ObjectKey,
        ) {
            val versionId =
                client
                    .headObject(
                        HeadObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(key.value)
                            .build(),
                    ).versionId()
            assertThrows(S3Exception::class.java) {
                client.deleteObject(
                    DeleteObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key.value)
                        .versionId(versionId)
                        .build(),
                )
            }
        }

        // Refuse overwrite of the lock itself: compliance mode (STK-20, ObjectStore.kt's own doc —
        // "not even the storage administrator can shorten it") must refuse an attempt to pull the
        // retention date earlier, exactly the mutation object lock exists to block, proven against
        // the real server, not just against the port's own missing method.
        private fun assertRetentionShorteningRefused(
            client: S3Client,
            bucket: String,
            key: ObjectKey,
        ) {
            val earlierRetention =
                ObjectLockRetention
                    .builder()
                    .mode(ObjectLockRetentionMode.COMPLIANCE)
                    .retainUntilDate(Instant.now().plus(1, ChronoUnit.HOURS))
                    .build()
            assertThrows(S3Exception::class.java) {
                client.putObjectRetention(
                    PutObjectRetentionRequest
                        .builder()
                        .bucket(bucket)
                        .key(key.value)
                        .retention(earlierRetention)
                        .build(),
                )
            }
        }
    }

    @Test
    fun `put retains under object lock, get returns the bytes, delete and retention-shortening are refused`() {
        // Chainguard's image is not published under `minio/minio`, so Testcontainers' own
        // compatibility check needs to be told this is a substitute for it (`MinIOContainer`
        // hard-codes `assertCompatibleWith` against that name). Its entrypoint is the `minio`
        // binary itself (unlike the upstream image's wrapper script), which takes exactly the
        // same `server --console-address :<port> /data` arguments `MinIOContainer` already sets
        // as the container command — so no command/entrypoint override is needed here.
        val image = DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio")
        val container = MinIOContainer(image)
        container.start()
        try {
            val client = minioClient(container)
            val bucket = "evidence-store-it"
            client.createBucket(
                CreateBucketRequest
                    .builder()
                    .bucket(bucket)
                    .objectLockEnabledForBucket(true)
                    .build(),
            )

            val store = S3ObjectStore(client, bucket)
            val key = ObjectKey("entity-1/2026-Q1/payload-1")
            val bytes = "the transmitted payload".toByteArray()
            val retainUntil = Instant.now().plus(1, ChronoUnit.DAYS)

            val ref = store.put(key, bytes, retainUntil)
            assertArrayEquals(bytes, store.get(key))
            assertEquals(key, ref.key)

            assertVersionedDeleteRefused(client, bucket, key)
            assertRetentionShorteningRefused(client, bucket, key)
        } finally {
            container.stop()
        }
    }
}
