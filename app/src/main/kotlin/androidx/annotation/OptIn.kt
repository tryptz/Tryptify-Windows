// Desktop stand-in for androidx.annotation.OptIn (it lives in the Android-only
// annotation-experimental artifact). The ported code writes
// @OptIn(UnstableApi::class); since UnstableApi is a plain marker here, this
// annotation only has to exist.
package androidx.annotation

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD,
    AnnotationTarget.CONSTRUCTOR, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.EXPRESSION, AnnotationTarget.FILE, AnnotationTarget.TYPEALIAS)
@Retention(AnnotationRetention.SOURCE)
annotation class OptIn(vararg val markerClass: KClass<out Annotation>)
