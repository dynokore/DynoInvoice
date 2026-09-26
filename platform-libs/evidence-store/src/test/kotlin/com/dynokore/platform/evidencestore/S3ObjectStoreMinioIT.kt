package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MinIOContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
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
    @Test
    fun `put retains under object lock, get returns the same bytes, and a raw delete is refused`() {
        // MinIO's own image moved off Docker Hub (`minio/minio` no longer exists there,
        // confirmed against the registry); `quay.io/minio/minio` is its current home.
        // If this pull needs credentials this environment does not have, that is an
        // open external question the MR notes (AGT-04): the guarantee this test proves
        // is about the S3 Object Lock API `S3ObjectStore` codes against (STK-20), not
        // about MinIO itself, so a different anonymous-pull image satisfying the same
        // Object Lock semantics is a drop-in replacement here, never a change to
        // `S3ObjectStore`.
        val container = MinIOContainer("quay.io/minio/minio:latest")
        container.start()
        try {
            val client =
                S3Client.builder()
                    .endpointOverride(URI.create(container.s3URL))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(container.userName, container.password),
                        ),
                    )
                    .forcePathStyle(true)
                    .build()

            val bucket = "evidence-store-it"
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).objectLockEnabledForBucket(true).build())

            val store = S3ObjectStore(client, bucket)
            val key = ObjectKey("entity-1/2026-Q1/payload-1")
            val bytes = "the transmitted payload".toByteArray()
            val retainUntil = Instant.now().plus(1, ChronoUnit.DAYS)

            val ref = store.put(key, bytes, retainUntil)
            assertArrayEquals(bytes, store.get(key))
            assertEquals(key, ref.key)

            // Refuse delete: the port itself has no delete method (ObjectStore.kt); this proves the
            // *server* refuses one too, attempted directly against the underlying client, bypassing
            // the port entirely — the STK-20 conformance probe's own "refuse delete".
            assertThrows(S3Exception::class.java) {
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.value).build())
            }
        } finally {
            container.stop()
        }
    }
}
