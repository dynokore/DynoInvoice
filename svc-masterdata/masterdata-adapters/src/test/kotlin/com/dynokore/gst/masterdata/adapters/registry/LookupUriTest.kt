package com.dynokore.gst.masterdata.adapters.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

/** The looked-up value stays one path segment under `/lookup/` whatever it holds (OWASP ASVS V5, CWE-22). */
class LookupUriTest {
    private val base = URI("http://registry.test:8080/api/")

    @Test
    fun `a Peppol ID keeps its colon`() {
        assertEquals("/api/lookup/0195:sguen201912345a", lookupUri(base, "0195:sguen201912345a").rawPath)
    }

    @Test
    fun `slashes and dot segments are encoded so the value cannot leave the lookup path`() {
        for (hostile in listOf("../../admin", "a/b", "..", "%2e%2e/x", "x?y=1#z", "a b", "ü")) {
            val uri = lookupUri(base, hostile)
            assertEquals("registry.test:8080", uri.rawAuthority, hostile)
            val tail = uri.rawPath.removePrefix("/api/lookup/")
            assertEquals(false, tail.contains('/'), hostile)
            assertEquals(null, uri.rawQuery, hostile)
            assertEquals(null, uri.rawFragment, hostile)
        }
        assertEquals("/api/lookup/%2E%2E%2F%2E%2E%2Fadmin", lookupUri(base, "../../admin").rawPath)
    }
}
