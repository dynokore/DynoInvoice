package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.masterdata.application.identity.ConfirmerDecision
import com.dynokore.gst.masterdata.application.identity.ConfirmerPermission
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.config.BeanPostProcessor
import java.time.Clock
import java.time.Instant

/**
 * A replica fixture over the pinned library (INV-T-8118): a real [AuthorityReplica] decides against the generated
 * operation-to-roles map of the pinned gst-catalogue, and the fixture only feeds it ENTITY-scope role grants. The
 * roles a test subject holds are data ([TestRoles], or a [TestRoleResolver] per context); before a decision the fixture
 * brings the replica to those grants for that subject and entity, so `decide` itself is the production code.
 */
object TestAuthority {
    val roleMap: OperationRoleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** A replica over the real clock; the grants it holds are those the feeder wrote. */
    fun newReplica(clock: Clock): AuthorityReplica = AuthorityReplica(clock, roleMap).also { it.onSnapshotComplete(0) }

    private data class Key(
        val tenant: TenantId,
        val person: String,
        val entity: String,
        val role: String,
    )

    /** Feeds [replica] so that [person] holds exactly [roles] on [entity] in [tenant], then forgets nothing else. */
    class Feeder(
        private val replica: AuthorityReplica,
        private val clock: Clock,
    ) {
        private val fed = HashMap<Key, Long>()
        private var version = 0L

        @Synchronized
        fun sync(
            tenant: TenantId,
            person: String,
            entity: String,
            roles: Set<String>,
        ) {
            val now: Instant = clock.instant()
            fed.keys
                .filter { it.tenant == tenant && it.person == person && it.entity == entity && it.role !in roles }
                .forEach { send(it, RoleGrantState.REMOVED, now) }
            roles
                .map {
                    Key(
                        tenant,
                        person,
                        entity,
                        it,
                    )
                }.filter { it !in fed }
                .forEach { send(it, RoleGrantState.ACTIVE, now) }
        }

        private fun send(
            key: Key,
            state: RoleGrantState,
            now: Instant,
        ) {
            val v = ++version
            val ref = fed.getOrPut(key) { v }
            replica.accept(
                AuthorityChanged(
                    key.tenant,
                    key.person,
                    v,
                    AuthorityRecord.RoleGrantRecord(
                        "g$ref",
                        state,
                        RoleGrant(key.role, GrantScope.ENTITY, key.entity, now.minusSeconds(60)),
                    ),
                ),
            )
            replica.observeHead(v)
            if (state == RoleGrantState.REMOVED) fed.remove(key)
        }
    }

    /**
     * Wraps the production `ConfirmerPermission` bean: the roles of the subject come from [resolver] (an exception is
     * no grants) and are fed to the replica first; the decision is the production bean's own, unchanged.
     */
    class FeedingProcessor(
        private val feeder: Feeder,
        private val resolver: TestRoleResolver,
    ) : BeanPostProcessor,
        BeanFactoryAware {
        private lateinit var beans: BeanFactory

        override fun setBeanFactory(beanFactory: BeanFactory) {
            beans = beanFactory
        }

        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any =
            if (bean is ConfirmerPermission && bean !is Fed) {
                Fed(bean, beans, feeder, resolver)
            } else {
                bean
            }
    }

    class Fed(
        private val delegate: ConfirmerPermission,
        private val beans: BeanFactory,
        private val feeder: Feeder,
        private val resolver: TestRoleResolver,
    ) : ConfirmerPermission {
        override fun decide(
            subject: PersonRef,
            entity: EntityRef,
        ): ConfirmerDecision {
            val held = runCatching { resolver.rolesOf(subject.value, entity.value) }.getOrDefault(emptySet())
            feeder.sync(beans.getBean(TenantContext::class.java).current(), subject.value, entity.value, held)
            return delegate.decide(subject, entity)
        }
    }
}
