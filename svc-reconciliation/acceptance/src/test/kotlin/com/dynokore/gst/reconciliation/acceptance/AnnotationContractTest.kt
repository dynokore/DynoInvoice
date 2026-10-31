package com.dynokore.gst.reconciliation.acceptance

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationRefusal
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-T-7247: the refusal code is in the error catalogue generated from catalogue/errors/error-codes.yaml. */
class AnnotationContractTest {
    @Test
    fun `the code of CLASS_NOT_ASSIGNABLE is a catalogue error code`() {
        ErrorCode.entries.map { it.code }.contains(AnnotationRefusal.CLASS_NOT_ASSIGNABLE.code) shouldBe true
    }
}
