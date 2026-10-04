// Desktop stand-in: Media3 marks most of its API @UnstableApi and the ported
// sources carry @OptIn(UnstableApi::class). A plain marker: no opt-in checking
// is wanted against our own shim.
package androidx.media3.common.util

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD,
    AnnotationTarget.CONSTRUCTOR, AnnotationTarget.TYPEALIAS)
annotation class UnstableApi
