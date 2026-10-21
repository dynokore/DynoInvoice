package com.dynokore.gst.transport.acceptance

import org.springframework.http.ResponseEntity
import org.springframework.objenesis.ObjenesisStd
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Finds the operations that still answer 501 (INV-T-4981). An operation is a stub when its controller method returns
 * a `ResponseEntity` with status 501 for placeholder arguments. A method that throws (an implemented operation
 * reaching a dependency the placeholder instance does not have) or answers anything else is not a stub; the gate
 * therefore never reads a source file or a method name, only what the controller does.
 */
object OperationsStubs {
    private val objenesis = ObjenesisStd(false)

    fun stubs(controllers: List<Class<*>>): Set<String> =
        controllers
            .flatMap { controller ->
                val instance = objenesis.newInstance(controller)
                controller.methods
                    .filter { it.declaringClass != Any::class.java && !Modifier.isStatic(it.modifiers) }
                    .filter { !it.isSynthetic }
                    .filter { ResponseEntity::class.java.isAssignableFrom(it.returnType) }
                    .filter { answers501(instance, it) }
                    .map { it.name }
            }.toSet()

    private fun answers501(
        instance: Any,
        method: Method,
    ): Boolean {
        val result = runCatching { method.invoke(instance, *method.parameterTypes.map(::placeholder).toTypedArray()) }
        val response = result.getOrNull() as? ResponseEntity<*> ?: return false
        return response.statusCode.value() == 501
    }

    private val SIMPLE: Map<Class<*>, Any> =
        mapOf(
            String::class.java to "x",
            Int::class.javaPrimitiveType!! to 0,
            Int::class.javaObjectType to 0,
            Long::class.javaPrimitiveType!! to 0L,
            Long::class.javaObjectType to 0L,
            Boolean::class.javaPrimitiveType!! to false,
            Boolean::class.javaObjectType to false,
            Double::class.javaPrimitiveType!! to 0.0,
            Double::class.javaObjectType to 0.0,
        )

    private fun placeholder(type: Class<*>): Any? =
        SIMPLE[type] ?: when {
            type.isEnum -> type.enumConstants.first()
            List::class.java.isAssignableFrom(type) -> emptyList<Any>()
            Map::class.java.isAssignableFrom(type) -> emptyMap<Any, Any>()
            type.isInterface || Modifier.isAbstract(type.modifiers) -> null
            else -> objenesis.newInstance(type)
        }
}
