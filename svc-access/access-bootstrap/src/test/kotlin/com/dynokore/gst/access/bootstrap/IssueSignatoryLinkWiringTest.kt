package com.dynokore.gst.access.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-AC-17419: the bean method of the issue use case takes no AuthorityReplica and no agent-scope collaborator. */
class IssueSignatoryLinkWiringTest {
    @Test
    fun `INV-AC-17419 the issue use case bean is wired without AuthorityReplica or agent scope`() {
        val method = SignedLinkIssueUseCases::class.java.methods.single { it.name == "issueSignedLinkUseCase" }
        val types = method.parameterTypes.map { it.name }
        assertTrue(types.isNotEmpty())
        assertTrue(types.none { it.contains("AuthorityReplica") || it.contains("AgentScope") }, types.toString())
        assertEquals("system:access-svc", SignedLinkIssueUseCases.SYSTEM_ACTOR)
    }
}
