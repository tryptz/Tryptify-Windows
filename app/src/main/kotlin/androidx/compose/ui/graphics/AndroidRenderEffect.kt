package androidx.compose.ui.graphics

/**
 * Android's `android.graphics.RenderEffect.asComposeRenderEffect()`, for the
 * Skia-backed RenderEffect shim; it sits beside Compose's own
 * `ImageFilter.asComposeRenderEffect()` and differs only in its receiver.
 */
fun android.graphics.RenderEffect.asComposeRenderEffect(): RenderEffect = imageFilter.asComposeRenderEffect()

/** The Compose shader for an android.graphics.Shader shim; on Android the two were the same type. */
fun android.graphics.Shader.asComposeShader(): Shader = skiaShader.asComposeShader()

/** A Compose brush from an android.graphics.Shader shim (Android's ShaderBrush took one directly). */
fun ShaderBrush(shader: android.graphics.Shader): ShaderBrush = ShaderBrush(shader.skiaShader.asComposeShader())
