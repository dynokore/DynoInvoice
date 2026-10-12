package com.dynokore.platform.serviceruntime.health

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType
import org.springframework.core.env.Environment

/**
 * API-33, owner ruling INV-Q-4103 A: health, metrics and diagnostics are served only on the management port,
 * which carries no workload token, so they must never share the application port, whose
 * WorkloadTokenAuthenticationFilter covers every path. service-runtime's defaults set `management.server.port`
 * to 8081; a service may change that number in its own configuration, but this guard fails start-up when the
 * management server would share the application port (Spring Boot's [ManagementPortType.SAME]: the port equal
 * to `server.port`, or 8080 while `server.port` is unset). A port of -1 (no HTTP management server) is not
 * sharing and is not refused here.
 */
class ManagementPortGuard(
    private val environment: Environment,
) : InitializingBean {
    override fun afterPropertiesSet() {
        check(ManagementPortType.get(environment) != ManagementPortType.SAME) {
            "management.server.port (${environment.getProperty("management.server.port")}) must differ from " +
                "server.port (${environment.getProperty("server.port") ?: "8080, default"}): health, metrics and " +
                "diagnostics are served only on the management port, never on the application port (API-33, INV-Q-4103)"
        }
    }
}
