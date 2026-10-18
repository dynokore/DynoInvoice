package com.dynokore.platform.serviceruntime.persistence

/** INV-T-4224 on PostgreSQL 16, 17 and 18, each with row-level security on and off. */
class PersistenceLayerPg16RlsIT : PersistenceLayerITBase("postgres:16", rls = true)

class PersistenceLayerPg17RlsIT : PersistenceLayerITBase("postgres:17", rls = true)

class PersistenceLayerPg18RlsIT : PersistenceLayerITBase("postgres:18", rls = true)

class PersistenceLayerPg16FrameworkOnlyIT : PersistenceLayerITBase("postgres:16", rls = false)

class PersistenceLayerPg17FrameworkOnlyIT : PersistenceLayerITBase("postgres:17", rls = false)

class PersistenceLayerPg18FrameworkOnlyIT : PersistenceLayerITBase("postgres:18", rls = false)
