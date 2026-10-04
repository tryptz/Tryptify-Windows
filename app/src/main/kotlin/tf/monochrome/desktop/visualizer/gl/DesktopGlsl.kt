package tf.monochrome.desktop.visualizer.gl

private const val ES_VERSION_LINE = "#version 300 es"
private const val CORE_VERSION_LINE = "#version 330 core"

/**
 * A GLSL ES 3.00 shader, as the Android app compiles it, restated for the
 * desktop's OpenGL 3.3 core profile.
 *
 * Only the version line changes. Everything the ambient shaders use exists in
 * GLSL 3.30 with the same meaning — `layout(location)` inputs, `in`/`out`,
 * `texture()`, integer uniforms — and the `precision` statement is accepted
 * and ignored: desktop GLSL has carried precision qualifiers since 1.30 purely
 * so that ES sources compile unchanged. Desktop floats are always full
 * precision, which can only bring the shader closer to its Kotlin twin in
 * `AmbientVisualizer.kt`, never further from it.
 *
 * Derived rather than copied so the arithmetic exists once. docs/ui-invariants.md
 * asks for the GLSL and its Kotlin twin to be changed together; a second copy
 * of the shader here would be a third place to forget.
 */
internal fun desktopGlsl(esSource: String): String {
    val lineEnd = esSource.indexOf('\n')
    require(lineEnd > 0 && esSource.substring(0, lineEnd).trim() == ES_VERSION_LINE) {
        "expected a GLSL ES 3.00 shader starting with \"$ES_VERSION_LINE\""
    }
    return CORE_VERSION_LINE + esSource.substring(lineEnd)
}
