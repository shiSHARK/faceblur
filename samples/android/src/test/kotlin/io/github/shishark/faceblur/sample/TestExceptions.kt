// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import io.github.shishark.faceblur.FaceMaskException

/**
 * The library's exceptions have internal constructors (an app cannot make them): tests build them by reflection with
 * neutral arguments. The demo only looks at the class and, for model output errors, at the finding codes.
 */
fun libraryException(type: Class<out FaceMaskException>, findings: List<String> = listOf("X")): FaceMaskException {
    val ctor = type.declaredConstructors
        .filter { c -> c.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") } }
        .maxBy { it.parameterCount }
    ctor.isAccessible = true
    val args = ctor.parameterTypes.map {
        when {
            it == String::class.java -> "reason"
            List::class.java.isAssignableFrom(it) -> findings
            it == Int::class.javaPrimitiveType -> 1
            it == Long::class.javaPrimitiveType -> 1L
            else -> null
        }
    }
    return ctor.newInstance(*args.toTypedArray()) as FaceMaskException
}
