// Desktop stand-in: Media3 marks most of its API @UnstableApi and the ported
// sources carry @OptIn(UnstableApi::class). The annotation only has to exist.
package androidx.media3.common.util

@RequiresOptIn(level = RequiresOptIn.Level.WARNING, message = "Media3-shaped API on the desktop build")
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.TYPEALIAS,
)
annotation class UnstableApi
