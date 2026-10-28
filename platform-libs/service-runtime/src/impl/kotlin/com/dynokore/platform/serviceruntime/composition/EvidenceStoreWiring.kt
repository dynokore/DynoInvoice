package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.JdbcLegalHoldRegistry
import com.dynokore.platform.evidencestore.LegalHoldRegistry
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.S3ObjectStore
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.net.URISyntaxException

/**
 * evidence-store's ports (INV-T-2875, INV-Q-3710), when the service embeds its impl half: the ledger and the
 * legal-hold register in the service's schema and the tables it names, and the §12.1 [ObjectStore] binding, the
 * implementation selected by `gst.bindings.object-store` (`s3-compatible`: [S3ObjectStore], STK-20) over the
 * service's `S3Client` and bucket.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.evidencestore.JdbcEvidenceLedger"])
class EvidenceStoreWiring {
    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnProperty(name = [Props.SCHEMA, Props.LEDGER_TABLE])
    @ConditionalOnMissingBean(EvidenceLedger::class)
    fun evidenceLedger(
        transactions: TransactionRunner,
        tenants: TenantContext,
        modules: ModuleProperties,
    ): JdbcEvidenceLedger {
        val schema = modules.requireSchema()
        val table = checkNotNull(modules.evidence.ledgerTable)
        return JdbcEvidenceLedger(dslFor(schema, table, tenants, transactions), schema, table)
    }

    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnProperty(name = [Props.SCHEMA, Props.LEGAL_HOLD_TABLE])
    @ConditionalOnMissingBean(LegalHoldRegistry::class)
    fun legalHoldRegistry(
        transactions: TransactionRunner,
        tenants: TenantContext,
        modules: ModuleProperties,
    ): JdbcLegalHoldRegistry {
        val schema = modules.requireSchema()
        val table = checkNotNull(modules.evidence.legalHoldTable)
        return JdbcLegalHoldRegistry(dslFor(schema, table, tenants, transactions), schema, table)
    }

    /**
     * The module's DSLContext (INV-T-4133, coordinator ruling INV-Q-5275): tenancy-jooq's factory over the
     * [TransactionRunner] and [TenantContext] beans, the module's table named with `DSL.name(schema, table)` and
     * declared `TENANT` (it carries `tenant_id` under forced row-level security). It is private to the class that
     * uses it and is no bean of the context.
     */
    private fun dslFor(
        schema: String,
        table: String,
        tenants: TenantContext,
        transactions: TransactionRunner,
    ): DSLContext = TenancyJooq.dslContext(mapOf(DSL.name(schema, table) to TableClass.TENANT), tenants, transactions)

    /** Its own class, so a service without the AWS SDK never loads a signature naming `S3Client`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["software.amazon.awssdk.services.s3.S3Client"])
    @ConditionalOnProperty(name = [Props.OBJECT_STORE], havingValue = "s3-compatible", matchIfMissing = true)
    class S3ObjectStoreWiring {
        /**
         * The AWS SDK v2 client of the binding (INV-T-12057, INV-Q-12045 A), declared before [objectStore] so that
         * its `@ConditionalOnBean(S3Client)` sees it. Built only when `service-runtime.object-store.region` is set and
         * no other `S3Client` bean exists. Both credentials are read through [SecretStore] on every request (STK-29).
         */
        @Bean
        @ConditionalOnProperty(name = [Props.OBJECT_STORE_REGION])
        @ConditionalOnMissingBean(S3Client::class)
        fun s3Client(
            environment: Environment,
            secrets: SecretStore,
        ): S3Client {
            val region = environment.getProperty(Props.OBJECT_STORE_REGION).orEmpty()
            require(region.isNotBlank()) { "${Props.OBJECT_STORE_REGION} must not be blank" }
            val provider =
                SecretStoreAwsCredentialsProvider(
                    secrets,
                    credentialRef(environment, Props.OBJECT_STORE_ACCESS_KEY_ID),
                    credentialRef(environment, Props.OBJECT_STORE_SECRET_ACCESS_KEY),
                )
            val builder =
                S3Client
                    .builder()
                    .region(Region.of(region.trim()))
                    .credentialsProvider(provider)
                    .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(pathStyle(environment)).build(),
                    )
            endpoint(environment)?.let { builder.endpointOverride(it) }
            try {
                provider.resolveCredentials()
            } catch (e: SecretNotFound) {
                throw IllegalStateException(
                    "${Props.OBJECT_STORE_ACCESS_KEY_ID} or ${Props.OBJECT_STORE_SECRET_ACCESS_KEY} names no secret: " +
                        e.message,
                    e,
                )
            }
            return builder.build()
        }

        @Bean
        @ConditionalOnBean(S3Client::class)
        @ConditionalOnProperty(name = [Props.BUCKET])
        @ConditionalOnMissingBean(ObjectStore::class)
        fun objectStore(
            client: S3Client,
            modules: ModuleProperties,
        ): S3ObjectStore = S3ObjectStore(client, checkNotNull(modules.evidence.bucket))
    }
}

private fun credentialRef(
    environment: Environment,
    property: String,
): SecretRef {
    val other =
        if (property == Props.OBJECT_STORE_ACCESS_KEY_ID) {
            Props.OBJECT_STORE_SECRET_ACCESS_KEY
        } else {
            Props.OBJECT_STORE_ACCESS_KEY_ID
        }
    val value = environment.getProperty(property)
    check(value != null) { "$property must be set together with $other once ${Props.OBJECT_STORE_REGION} is set" }
    return try {
        SecretRef(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("$property must name a secret: ${e.message?.substringBefore(':')}", e)
    }
}

private fun pathStyle(environment: Environment): Boolean =
    when (environment.getProperty(Props.OBJECT_STORE_PATH_STYLE)?.trim()?.lowercase()) {
        null, "false" -> false
        "true" -> true
        else -> error("${Props.OBJECT_STORE_PATH_STYLE} must be true or false")
    }

/** The optional endpoint: absolute `http` or `https` with a host, no user information, query, fragment or trailing slash. */
private fun endpoint(environment: Environment): URI? {
    val text = environment.getProperty(Props.OBJECT_STORE_ENDPOINT) ?: return null
    val problem = "${Props.OBJECT_STORE_ENDPOINT} must be an absolute http or https URI with a host, "
    val uri =
        try {
            URI(text)
        } catch (_: URISyntaxException) {
            error(problem + "and without user information, query, fragment or trailing slash")
        }
    check(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
        problem + "and without user information, query, fragment or trailing slash"
    }
    check(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && !text.endsWith("/")) {
        problem + "and without user information, query, fragment or trailing slash"
    }
    return uri
}

/**
 * Reads both references through [SecretStore] on every call and keeps neither value (STK-29: rotation by file
 * watch, no restart).
 */
private class SecretStoreAwsCredentialsProvider(
    private val secrets: SecretStore,
    private val accessKeyId: SecretRef,
    private val secretAccessKey: SecretRef,
) : AwsCredentialsProvider {
    override fun resolveCredentials(): AwsCredentials =
        AwsBasicCredentials.create(secrets.read(accessKeyId).reveal(), secrets.read(secretAccessKey).reveal())
}
