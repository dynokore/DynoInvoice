package com.dynokore.gst.submission.acceptance

import org.springframework.http.ResponseEntity
import org.springframework.objenesis.ObjenesisStd
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Finds the operations that still answer 501 (INV-T-5964). An operation is a stub when its controller method returns
 * a `ResponseEntity` with status 501 for placeholder arguments. A method that throws (an implemented operation
 * reaching a dependency the placeholder instance does not have) or answers anything else is not a stub; the gate
 * therefore never reads a source file or a method name, only what the controller does.
 */
object OperationsStubs {
    private val objenesis = ObjenesisStd(false)

    fun stubs(controllers: List<Class<*>>): Set<String> =
        controllers.flatMap { controller ->
            val instance = objenesis.newInstance(controller)
            controller.methods
                .filter { it.declaringClass != Any::class.java && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
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

    private fun placeholder(type: Class<*>): Any? =
        when {
            type == String::class.java -> "x"
            type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> 0
            type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> 0L
            type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> false
            type == Double::class.javaPrimitiveType || type == Double::class.javaObjectType -> 0.0
            type.isEnum -> type.enumConstants.first()
            List::class.java.isAssignableFrom(type) -> emptyList<Any>()
            Map::class.java.isAssignableFrom(type) -> emptyMap<Any, Any>()
            type.isInterface || Modifier.isAbstract(type.modifiers) -> null
            else -> objenesis.newInstance(type)
        }
}
