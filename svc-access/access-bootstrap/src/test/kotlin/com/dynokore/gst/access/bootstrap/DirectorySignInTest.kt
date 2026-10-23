package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-AC-2244, directory half (INV-REQ-1262): a tenant switched to its customer OIDC directory signs its users in
 * through that directory. The directory is a certification-kit identity-provider simulator named as the issuer of the
 * `IdentityProvider` binding; a user of that directory, and no one else, is admitted by the production principal
 * source. That the sealed break-glass account still signs in (the other half) is asserted by INV-T-8853.
 */
class DirectorySignInTest {
    @Test
    fun `INV-AC-2244 a user of the customer directory signs in through it and a user of another provider does not`() {
        IdentityProviderSimulator().start().use { customer ->
            IdentityProviderSimulator().start().use { other ->
                // both directories emit the audience this service requires (STK-27), as an installation configures them
                listOf(customer, other).forEach { it.addAudience("access-svc") }
                val own = customer.userToken("tenant-admin-1")
                val foreign = other.userToken("tenant-admin-1")
                BoundaryFixture
                    .runner()
                    .withPropertyValues("access.identity-provider.issuer-uri=${customer.issuer}")
                    .run { context ->
                        context.startupFailure?.let { throw it }
                        val resolver = context.getBean(EndUserPrincipalResolver::class.java)
                        val signedIn = resolver.resolve("Bearer $own")
                        assertThat(signedIn).describedAs("a user of the customer directory").isNotNull()
                        assertThat(signedIn!!.actor).isNotBlank()
                        // the same user at another provider (other issuer and keys) is not admitted
                        assertThat(resolver.resolve("Bearer $foreign")).isNull()
                        assertThat(resolver.resolve("Bearer ${own}x")).isNull()
                        assertThat(resolver.resolve(null)).isNull()
                    }
            }
        }
    }
}
