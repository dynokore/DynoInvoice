package com.dynokore.platform.adapterruntime.cutover

import com.dynokore.platform.adapterruntime.AdapterVersion
import com.dynokore.platform.adapterruntime.ManagedAdapter
import com.dynokore.platform.adapterruntime.fixtures.FakeManagedAdapter

class FakeManagedAdapterContractTest : ManagedAdapterContractTest() {
    override fun adapter(): ManagedAdapter = FakeManagedAdapter(AdapterVersion("1.0.0"))
}
