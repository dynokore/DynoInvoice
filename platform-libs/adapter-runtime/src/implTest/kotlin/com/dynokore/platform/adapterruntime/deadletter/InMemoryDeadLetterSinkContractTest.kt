package com.dynokore.platform.adapterruntime.deadletter

import com.dynokore.platform.adapterruntime.DeadLetterEntry
import com.dynokore.platform.adapterruntime.DeadLetterSink

class InMemoryDeadLetterSinkContractTest : DeadLetterSinkContractTest() {
    override fun sink(): DeadLetterSink = InMemoryDeadLetterSink()

    override fun received(sink: DeadLetterSink): List<DeadLetterEntry> = (sink as InMemoryDeadLetterSink).entries
}
