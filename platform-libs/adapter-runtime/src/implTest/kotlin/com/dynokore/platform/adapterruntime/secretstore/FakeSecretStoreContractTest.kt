package com.dynokore.platform.adapterruntime.secretstore

import com.dynokore.platform.adapterruntime.SecretRef
import com.dynokore.platform.adapterruntime.SecretStore
import com.dynokore.platform.adapterruntime.fixtures.FakeSecretStore

class FakeSecretStoreContractTest : SecretStoreContractTest() {
    override fun store(): SecretStore = FakeSecretStore(mapOf(seedRef.value to seedValue))

    override fun unboundRef(): SecretRef = SecretRef("never-bound")
}
