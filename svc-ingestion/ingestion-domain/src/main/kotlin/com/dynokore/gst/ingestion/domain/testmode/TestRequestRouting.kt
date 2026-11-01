package com.dynokore.gst.ingestion.domain.testmode

enum class WorkspaceKind { PRODUCTION, NON_PRODUCTION }

sealed interface TestRouting {
    data object Live : TestRouting

    data object InRequestWorkspace : TestRouting

    data class InPairedWorkspace(
        val workspaceRef: String,
    ) : TestRouting

    data class Refused(
        val code: String,
        val httpStatus: Int,
    ) : TestRouting
}

object TestRequestRouting {
    const val ING_021 = "ING-021"
    const val UNPROCESSABLE = 422

    fun route(
        headerMarked: Boolean,
        requestWorkspace: WorkspaceKind,
        pairedWorkspaceRef: String?,
    ): TestRouting =
        when (requestWorkspace) {
            WorkspaceKind.NON_PRODUCTION -> {
                TestRouting.InRequestWorkspace
            }

            WorkspaceKind.PRODUCTION -> {
                when (headerMarked) {
                    false -> TestRouting.Live
                    true -> pairedOrRefused(pairedWorkspaceRef)
                }
            }
        }

    private fun pairedOrRefused(pairedWorkspaceRef: String?): TestRouting =
        if (pairedWorkspaceRef != null && !pairedWorkspaceRef.isBlank()) {
            TestRouting.InPairedWorkspace(pairedWorkspaceRef)
        } else {
            TestRouting.Refused(ING_021, UNPROCESSABLE)
        }
}
