package com.dynokore.gst.access.bootstrap.served

import com.lemonappdev.konsist.api.Konsist
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val REPLICA_PACKAGE = "com.dynokore.platform.serviceruntime.replica"
private const val HTTP_PACKAGE = "com.dynokore.gst.access.adapters.http"

private val HIGH_RISK =
    setOf(
        "grantRole",
        "revokeGrant",
        "revokePrincipal",
        "grantConsent",
        "withdrawConsent",
        "unlockBreakGlass",
        "signInBreakGlass",
        "resealBreakGlass",
        "recordBreakGlassAction",
    )

/**
 * The package text of an import name: the leading segments up to the first one that starts with an upper-case letter
 * (a class), so a nested class and a wildcard import keep the package of their outer class. Compared by equality.
 */
internal fun importPackage(name: String): String =
    name
        .removeSuffix(".*")
        .split('.')
        .takeWhile { it.isNotEmpty() && !it.first().isUpperCase() }
        .joinToString(".")

internal fun importsReplica(name: String): Boolean = importPackage(name) == REPLICA_PACKAGE

/**
 * INV-AC-16548: the operations of access-svc that carry `x-high-risk-action` are decided from its own tables, never
 * from the authority replica (INV-Q-9245 (A); INV-REQ-1201). The marked operations are fixed by the contract module's
 * `META-INF/gst/high-risk-operations.tsv`; the HTTP adapter package must not import the replica package.
 */
class HighRiskLiveDecisionTest {
    @Test
    fun `INV-AC-16548 the marked operations of the contract are exactly the nine access operations`() {
        val urls =
            ContractDocument::class.java.classLoader
                .getResources("META-INF/gst/high-risk-operations.tsv")
                .toList()
        assertThat(urls).describedAs("high-risk-operations.tsv resources on the class path").isNotEmpty()
        val lines =
            urls.flatMap { url ->
                url
                    .openStream()
                    .use { String(it.readAllBytes(), Charsets.UTF_8) }
                    .lines()
                    .filter(String::isNotEmpty)
            }
        assertThat(lines.toSet()).isEqualTo(HIGH_RISK)
    }

    @Test
    fun `INV-AC-16548 no production file of the http adapter package imports the authority replica package`() {
        val files =
            Konsist
                .scopeFromProduction()
                .files
                .filter { it.packagee?.name == HTTP_PACKAGE }
        println("INV-AC-16548 checked files: ${files.map { it.path }.sorted()}")
        assertThat(files).describedAs("files of $HTTP_PACKAGE checked").isNotEmpty()
        val offenders =
            files.filter { file -> file.imports.any { importsReplica(it.name) } }.map { it.path }
        assertThat(offenders).describedAs("files importing $REPLICA_PACKAGE").isEmpty()
    }

    @Test
    fun `INV-AC-16548 the import matcher compares package text by equality`() {
        assertThat(importsReplica("$REPLICA_PACKAGE.AuthorityReplica")).isTrue()
        assertThat(importsReplica("$REPLICA_PACKAGE.*")).isTrue()
        assertThat(importsReplica("$REPLICA_PACKAGE.AuthorityReplica.Inner")).isTrue()
        assertThat(importsReplica("${REPLICA_PACKAGE}s.Foo")).isFalse()
        assertThat(importsReplica("$REPLICA_PACKAGE.sub.Foo")).isFalse()
        assertThat(importsReplica("x.$REPLICA_PACKAGE.Foo")).isFalse()
        assertThat(importsReplica("com.dynokore.platform.serviceruntime.Foo")).isFalse()
    }
}
