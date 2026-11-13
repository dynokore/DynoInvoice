package com.dynokore.gst.access.application

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SignInOldClassesRemovedTest {
    @Test
    fun `INV-AC-16513 the old sign-in use case no longer exists`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.dynokore.gst.access.application.breakglass.SignInBreakGlassUseCase")
        }
    }

    @Test
    fun `INV-AC-16513 the old sign-in outcome no longer exists`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.dynokore.gst.access.application.breakglass.SignInBreakGlassOutcome")
        }
    }

    @Test
    fun `INV-AC-16513 the back-off use case and outcome load`() {
        assertNotNull(Class.forName("com.dynokore.gst.access.application.breakglass.SignInWithBackoffUseCase"))
        assertNotNull(Class.forName("com.dynokore.gst.access.application.breakglass.SignInWithBackoffOutcome"))
    }

    @Test
    fun `INV-AC-16513 a look-alike with a suffix does not load`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.dynokore.gst.access.application.breakglass.SignInBreakGlassUseCaseV2")
        }
    }
}
