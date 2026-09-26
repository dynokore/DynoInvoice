package com.dynokore.platform.adapterruntime.connection

import com.dynokore.platform.adapterruntime.ConnectionStatusReporter

class InMemoryConnectionStatusReporterContractTest : ConnectionStatusReporterContractTest() {
    override fun reporter(): ConnectionStatusReporter = InMemoryConnectionStatusReporter()
}
