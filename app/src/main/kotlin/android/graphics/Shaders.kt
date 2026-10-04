package android.graphics

import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

/*
 * Android's RuntimeShader and RenderEffect over Skia, which is what Android
 * ran them on too: AGSL is Skia's SkSL with Android's name on it, and
 * Compose for Desktop draws with Skia. So the liquid-glass shaders, their
 * uniforms and their RenderEffect wiring run unchanged, and the look that
 * docs/ui-invariants.md pins is the same program on the same renderer.
 */

/** A pixel bitmap; on the desktop it is Skia's, which is what Coil decodes to. */
typealias Bitmap = org.jetbrains.skia.Bitmap

/** Base of the shader shims; [skiaShader] is the Skia object a draw uses. */
abstract class Shader {
    abstract val skiaShader: org.jetbrains.skia.Shader

    enum class TileMode(internal val skia: FilterTileMode) {
        CLAMP(FilterTileMode.CLAMP),
        REPEAT(FilterTileMode.REPEAT),
        MIRROR(FilterTileMode.MIRROR),
        DECAL(FilterTileMode.DECAL),
    }
}

class BitmapShader(
    private val bitmap: Bitmap,
    private val tileX: TileMode,
    private val tileY: TileMode,
) : Shader() {
    private var filterMode = FILTER_MODE_DEFAULT
    private var cached: org.jetbrains.skia.Shader? = null

    fun setFilterMode(mode: Int) {
        if (mode != filterMode) cached = null
        filterMode = mode
    }

    fun getFilterMode(): Int = filterMode

    override val skiaShader: org.jetbrains.skia.Shader
        get() = cached ?: bitmap.makeShader(
            tileX.skia,
            tileY.skia,
            if (filterMode == FILTER_MODE_LINEAR) SamplingMode.LINEAR else SamplingMode.DEFAULT,
        ).also { cached = it }

    companion object {
        const val FILTER_MODE_DEFAULT = 0
        const val FILTER_MODE_NEAREST = 1
        const val FILTER_MODE_LINEAR = 2
    }
}

/**
 * Android's RuntimeShader. Compiling a bad program throws, as on Android,
 * so the callers' `runCatching { RuntimeShader(SRC) }` availability probes
 * keep their meaning.
 */
class RuntimeShader(sksl: String) : Shader() {
    private val effect: RuntimeEffect = RuntimeEffect.makeForShader(sksl)
    internal val builder = RuntimeShaderBuilder(effect)

    fun setFloatUniform(name: String, value: Float) = builder.uniform(name, value)
    fun setFloatUniform(name: String, v1: Float, v2: Float) = builder.uniform(name, v1, v2)
    fun setFloatUniform(name: String, v1: Float, v2: Float, v3: Float) = builder.uniform(name, v1, v2, v3)
    fun setFloatUniform(name: String, v1: Float, v2: Float, v3: Float, v4: Float) = builder.uniform(name, v1, v2, v3, v4)
    fun setFloatUniform(name: String, values: FloatArray) = builder.uniform(name, values)

    fun setIntUniform(name: String, value: Int) = builder.uniform(name, value)
    fun setIntUniform(name: String, v1: Int, v2: Int) = builder.uniform(name, v1, v2)
    fun setIntUniform(name: String, v1: Int, v2: Int, v3: Int) = builder.uniform(name, v1, v2, v3)
    fun setIntUniform(name: String, v1: Int, v2: Int, v3: Int, v4: Int) = builder.uniform(name, v1, v2, v3, v4)

    /** `layout(color) uniform half4`: an ARGB int, unpremultiplied, as Android passes it. */
    fun setColorUniform(name: String, color: Int) = builder.uniform(
        name,
        ((color shr 16) and 0xFF) / 255f,
        ((color shr 8) and 0xFF) / 255f,
        (color and 0xFF) / 255f,
        ((color ushr 24) and 0xFF) / 255f,
    )

    fun setInputShader(name: String, shader: Shader) = builder.child(name, shader.skiaShader)
    fun setInputBuffer(name: String, shader: BitmapShader) = builder.child(name, shader.skiaShader)

    override val skiaShader: org.jetbrains.skia.Shader get() = builder.makeShader()
}

/**
 * Android's RenderEffect. Each factory snapshots the shader's uniforms at
 * the moment it is called, as Android's does, so the per-frame pattern of
 * set-uniforms-then-create keeps working.
 */
class RenderEffect private constructor(val imageFilter: ImageFilter) {
    companion object {
        fun createRuntimeShaderEffect(shader: RuntimeShader, uniformShaderName: String): RenderEffect =
            RenderEffect(ImageFilter.makeRuntimeShader(shader.builder, uniformShaderName, null))

        fun createBlurEffect(radiusX: Float, radiusY: Float, edgeTreatment: Shader.TileMode): RenderEffect =
            RenderEffect(ImageFilter.makeBlur(radiusX, radiusY, edgeTreatment.skia, null, null))

        fun createBlurEffect(radiusX: Float, radiusY: Float, inputEffect: RenderEffect, edgeTreatment: Shader.TileMode): RenderEffect =
            RenderEffect(ImageFilter.makeBlur(radiusX, radiusY, edgeTreatment.skia, inputEffect.imageFilter, null))

        /** [outer] applied to the result of [inner], as Android's chain. */
        fun createChainEffect(outer: RenderEffect, inner: RenderEffect): RenderEffect =
            RenderEffect(ImageFilter.makeCompose(outer.imageFilter, inner.imageFilter))

        fun createShaderEffect(shader: Shader): RenderEffect =
            RenderEffect(ImageFilter.makeShader(shader.skiaShader, false, null))
    }
}
