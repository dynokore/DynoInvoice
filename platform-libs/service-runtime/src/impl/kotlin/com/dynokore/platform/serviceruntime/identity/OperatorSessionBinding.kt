package com.dynokore.platform.serviceruntime.identity

import com.nimbusds.jwt.JWTParser
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import java.text.ParseException

/**
 * The operator session the current request runs under (owner ruling INV-Q-4119; coordinator ruling INV-Q-4521):
 * bound by [OperatorSessionBindingFilter] for the whole request, so every audit entry recorded in it carries the
 * case. Absent for any other request and for work with no request behind it (background jobs, consumers).
 */
object CurrentOperatorSession {
    private val key: ScopedValue<OperatorSession> = ScopedValue.newInstance()

    fun current(): OperatorSession? = if (key.isBound) key.get() else null

    fun <T> callWith(
        session: OperatorSession,
        block: () -> T,
    ): T = ScopedValue.where(key, session).call<T, RuntimeException>(block)
}

/**
 * Binds the resolved operator session for the request (coordinator ruling INV-Q-4521): a bearer token carrying
 * `act` is resolved by the service's [EndUserPrincipalResolver], and only a verified session is bound. Every other
 * request, a refused token included, passes unbound; refusing it (401) stays with the handler that resolves the
 * principal. A token without `act` is never resolved here, so the identity provider is not asked twice.
 */
class OperatorSessionBindingFilter(
    private val resolvers: ObjectProvider<EndUserPrincipalResolver>,
) : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val authorization = (request as HttpServletRequest).getHeader(HttpHeaders.AUTHORIZATION)
        val session =
            if (carriesAct(authorization)) resolvers.ifAvailable?.resolve(authorization)?.operatorSession else null
        if (session == null) {
            chain.doFilter(request, response)
        } else {
            CurrentOperatorSession.callWith(session) { chain.doFilter(request, response) }
        }
    }

    private fun carriesAct(authorization: String?): Boolean {
        val parts = authorization?.trim()?.split(' ', limit = 2)
        val token = parts?.getOrNull(1)?.trim()
        if (token.isNullOrEmpty() || !parts[0].equals("Bearer", ignoreCase = true)) return false
        return try {
            JWTParser.parse(token).jwtClaimsSet?.claims?.containsKey(OperatorSessionTokens.ACT_CLAIM) == true
        } catch (malformed: ParseException) {
            false
        }
    }
}
