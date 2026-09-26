package com.dynokore.platform.evidencestore

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Instant

/**
 * [ObjectStore] over the S3 API (STK-20): AWS SDK v2, object lock in
 * compliance mode — not even the bucket owner or root credentials can
 * shorten or remove the retention this sets before [retainUntil] (the
 * guarantee the STK-20 conformance probe proves: "put, retain, refuse
 * delete"). [bucket] must already have object-lock enabled — a bucket-level
 * setting fixed at bucket creation, out of this adapter's scope (an
 * installer/deployment concern, STK-20's own probe).
 */
class S3ObjectStore(
    private val client: S3Client,
    private val bucket: String,
) : ObjectStore {
    override fun put(
        key: ObjectKey,
        bytes: ByteArray,
        retainUntil: Instant,
    ): ObjectRef {
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key.value)
            .objectLockMode(ObjectLockMode.COMPLIANCE)
            .objectLockRetainUntilDate(retainUntil)
            .build()
        client.putObject(request, RequestBody.fromBytes(bytes))
        return ObjectRef(key, retainUntil)
    }

    override fun get(key: ObjectKey): ByteArray {
        val request = GetObjectRequest.builder().bucket(bucket).key(key.value).build()
        return client.getObjectAsBytes(request).asByteArray()
    }
}
