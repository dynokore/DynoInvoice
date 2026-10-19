package com.dynokore.platform.serviceruntime.persistence

/** INV-T-4224 on PostgreSQL 16, 17 and 18, each with row-level security on and off. */
class PersistenceLayerPg16RlsIT : PersistenceVersionsITBase() {
    override val image = "postgres:16"
    override val rls = true
}

class PersistenceLayerPg17RlsIT : PersistenceVersionsITBase() {
    override val image = "postgres:17"
    override val rls = true
}

class PersistenceLayerPg18RlsIT : PersistenceVersionsITBase() {
    override val image = "postgres:18"
    override val rls = true
}

class PersistenceLayerPg16FrameworkOnlyIT : PersistenceVersionsITBase() {
    override val image = "postgres:16"
    override val rls = false
}

class PersistenceLayerPg17FrameworkOnlyIT : PersistenceVersionsITBase() {
    override val image = "postgres:17"
    override val rls = false
}

class PersistenceLayerPg18FrameworkOnlyIT : PersistenceVersionsITBase() {
    override val image = "postgres:18"
    override val rls = false
}
