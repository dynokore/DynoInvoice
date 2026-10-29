package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object

class S3ObjectStoreListingTest {
    private class PagedClient(
        private val failOnPage: Int? = null,
        private val failure: RuntimeException = IllegalStateException("page failed"),
    ) : S3Client {
        val requests = mutableListOf<ListObjectsV2Request>()
        private val sizes = listOf(100L, 200L, 300L)

        override fun serviceName(): String = "s3"

        override fun close() = Unit

        override fun listObjectsV2(request: ListObjectsV2Request): ListObjectsV2Response {
            requests += request
            val page = request.continuationToken()?.removePrefix("t")?.toInt() ?: 0
            if (page == failOnPage) throw failure
            return ListObjectsV2Response
                .builder()
                .contents(
                    S3Object
                        .builder()
                        .key("p/T/E1/$page")
                        .size(sizes[page])
                        .build(),
                ).isTruncated(page < sizes.lastIndex)
                .nextContinuationToken(if (page < sizes.lastIndex) "t${page + 1}" else null)
                .build()
        }
    }

    @Test
    fun `sums every page and asks for the adapter bucket and the prefix`() {
        val client = PagedClient()
        assertEquals(600L, S3ObjectStore(client, "bkt").bytesUnder(ObjectKeyPrefix("p/T/E1/")))
        assertEquals(3, client.requests.size)
        assertEquals(setOf("bkt"), client.requests.map { it.bucket() }.toSet())
        assertEquals(setOf("p/T/E1/"), client.requests.map { it.prefix() }.toSet())
    }

    @Test
    fun `a failing second page propagates the exception and returns no value`() {
        val failure = IllegalStateException("page 2 failed")
        val client = PagedClient(failOnPage = 1, failure = failure)
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                S3ObjectStore(client, "bkt").bytesUnder(ObjectKeyPrefix("p/T/E1/"))
            }
        assertSame(failure, thrown)
    }

    @Test
    fun `no object gives zero`() {
        val client =
            object : S3Client {
                override fun serviceName(): String = "s3"

                override fun close() = Unit

                override fun listObjectsV2(request: ListObjectsV2Request): ListObjectsV2Response =
                    ListObjectsV2Response.builder().isTruncated(false).build()
            }
        assertEquals(0L, S3ObjectStore(client, "bkt").bytesUnder(ObjectKeyPrefix("none/")))
    }
}
