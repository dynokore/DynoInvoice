package consumercheck;

import com.dynokore.platform.auditlog.AuditWriter;
import com.dynokore.platform.configresolver.Acknowledger;
import com.dynokore.platform.outboxandqueue.ConsumerSubscription;
import com.dynokore.platform.serviceruntime.binding.IdentityProvider;
import com.dynokore.platform.tenancykernel.LicenceCheck;
import java.lang.reflect.Method;

/**
 * INV-T-4356: compiles against a type of each module's {@code -api} (the parameters of {@link #usesApiTypes}), which
 * the build reaches only through the impl artefacts' POMs, and at run time loads one impl class of each module and
 * resolves its declared methods, so every type in their signatures must be on the transitively resolved classpath.
 */
public final class ConsumerCheck {
    private static final String[] IMPL_CLASSES = {
        "com.dynokore.platform.tenancykernel.TenantAwareDataSource",
        "com.dynokore.platform.outboxandqueue.JdbcConsumerReplay",
        "com.dynokore.platform.auditlog.JdbcOverrideService",
        "com.dynokore.platform.configresolver.JdbcPolicyHistory",
        "com.dynokore.platform.serviceruntime.composition.ServiceRuntimeWiring",
    };

    private ConsumerCheck() {}

    static int usesApiTypes(
            LicenceCheck tenancyKernel,
            ConsumerSubscription outboxAndQueue,
            AuditWriter auditLog,
            Acknowledger configResolver,
            IdentityProvider serviceRuntime) {
        return 5;
    }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = ConsumerCheck.class.getClassLoader();
        for (String name : IMPL_CLASSES) {
            Class<?> impl = Class.forName(name, false, loader);
            Method[] methods = impl.getDeclaredMethods();
            String jar = impl.getProtectionDomain().getCodeSource().getLocation().getPath();
            System.out.println("consumer-check: loaded " + name + " (" + methods.length + " methods) from "
                    + jar.substring(jar.lastIndexOf('/') + 1));
        }
        System.out.println("consumer-check: " + IMPL_CLASSES.length + " impl classes loaded, "
                + usesApiTypes(null, null, null, null, null) + " api types compiled against");
    }
}
