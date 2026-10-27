package com.dynokore.gst.ingestion.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** INV-T-11023: `ingestion.canonical-svc-url` is required and not blank; the service does not start without it (TPL-16). */
class CanonicalUrlRequiredTest {
    @Test
    fun `the context starts with the url and fails when it is blank or unresolved`(
        @TempDir dir: Path,
    ) {
        IngestionContext.offline(dir).run { (it.startupFailure == null) shouldBe true }
        IngestionContext
            .offline(dir)
            .withPropertyValues("ingestion.canonical-svc-url=")
            .run { (it.startupFailure != null) shouldBe true }
        IngestionContext
            .offline(dir)
            .withPropertyValues("ingestion.canonical-svc-url=\${INGESTION_CANONICAL_SVC_URL_UNSET_IN_TEST}")
            .run { (it.startupFailure != null) shouldBe true }
    }
}
