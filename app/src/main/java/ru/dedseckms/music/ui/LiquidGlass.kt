package ru.dedseckms.music.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// A shared scene lets the lens sample displaced background coordinates without copying UI pixels every frame.
internal const val GLASS_SHADER = """
uniform float2 resolution;
uniform float2 viewport;
uniform float2 origin;
uniform float time;
uniform float lens;
uniform float black;
uniform float3 base;
uniform float3 accent;
uniform float3 tint;
float3 scene(float2 p) {
    float w = max(viewport.x, 1.0);
    float2 a = (p - float2(w * (0.86 + 0.04*sin(time)), viewport.y * 0.20)) / (w * 1.1);
    float2 b = (p - float2(w * 0.08, viewport.y * (0.68 + 0.025*cos(time)))) / w;
    float x = max(0.0, 1.0-length(a)) * 0.34 * (1.0-black);
    float y = max(0.0, 1.0-length(b)) * 0.27 * (1.0-black);
    return mix(mix(base, accent, x), tint, y);
}
half4 main(float2 p) {
    if (lens < 0.5) return half4(clamp(scene(origin+p), 0.0, 1.0), 1.0);
    float d = min(min(p.x, resolution.x-p.x), min(p.y, resolution.y-p.y));
    float rim = 1.0-smoothstep(0.0, 26.0, d);
    float2 normal = normalize((p-resolution*0.5)/max(resolution, float2(1.0)) + float2(0.0001));
    float2 bend = normal * rim * rim * 18.0 * lens;
    float2 ripple = float2(sin(p.y*0.016+time), cos(p.x*0.012-time))*lens*1.4;
    float3 rgb = scene(origin+p+bend+ripple);
    float3 chroma = float3(scene(origin+p+bend*1.16).r, rgb.g, scene(origin+p+bend*0.84).b);
    rgb = mix(rgb, chroma, lens*rim*0.65);
    float shine = pow(max(0.0, normal.x*(-0.65)+normal.y*(-0.75)), 8.0);
    rgb = mix(rgb, float3(1.0), lens*(0.035 + rim*0.07 + shine*rim*0.28));
    rgb += lens * accent * rim * 0.035;
    return half4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

private data class GlassScene(val phase: State<Float>, val viewport: Size, val origin: Offset, val colors: ColorScheme, val amoled: Boolean)
private val LocalGlassScene = staticCompositionLocalOf<GlassScene?> { null }

@Composable private fun glassShader(): RuntimeShader? = remember {
    if (Build.VERSION.SDK_INT >= 33) runCatching { RuntimeShader(GLASS_SHADER) }.getOrNull() else null
}

private fun DrawScope.drawScene(shader: RuntimeShader?, scene: GlassScene, origin: Offset, lens: Float) {
    if (Build.VERSION.SDK_INT >= 33 && shader != null) {
        shader.setFloatUniform("resolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
        shader.setFloatUniform("viewport", scene.viewport.width.coerceAtLeast(1f), scene.viewport.height.coerceAtLeast(1f))
        shader.setFloatUniform("origin", origin.x, origin.y)
        shader.setFloatUniform("time", scene.phase.value)
        shader.setFloatUniform("lens", lens)
        shader.setFloatUniform("black", if (scene.amoled) 1f else 0f)
        fun color(name: String, value: Color) = shader.setFloatUniform(name, value.red, value.green, value.blue)
        color("base", scene.colors.background); color("accent", scene.colors.primary); color("tint", scene.colors.tertiary)
        drawRect(ShaderBrush(shader))
    } else {
        drawRect(if (scene.amoled) Color.Black else scene.colors.background)
        if (!scene.amoled) drawRect(Brush.linearGradient(listOf(scene.colors.primary.copy(alpha = .18f), Color.Transparent, scene.colors.tertiary.copy(alpha = .15f))))
        if (lens > 0) drawRect(Color.White.copy(alpha = .055f))
    }
}

@Composable internal fun LiquidBackdrop(amoled: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val reduced = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val phase = if (reduced) remember { mutableFloatStateOf(0f) } else rememberInfiniteTransition(label = "glass-light")
        .animateFloat(0f, (Math.PI * 2).toFloat(), infiniteRepeatable(tween(28_000, easing = LinearEasing)), label = "glass-phase")
    var viewport by remember { mutableStateOf(Size.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val scene = GlassScene(phase, viewport, origin, colors, amoled)
    val shader = glassShader()
    Box(Modifier.fillMaxSize().background(colors.background).onGloballyPositioned { viewport = Size(it.size.width.toFloat(), it.size.height.toFloat()); origin = it.positionInRoot() }) {
        Canvas(Modifier.fillMaxSize()) { drawScene(shader, scene, Offset.Zero, 0f) }
        CompositionLocalProvider(LocalGlassScene provides scene, LocalContentColor provides colors.onBackground) { content() }
    }
}

/** A refractive rim, colour separation and moving specular light; text stays sharp. */
internal fun Modifier.liquidGlass(): Modifier = composed {
    val scene = LocalGlassScene.current
    if (LocalVisualStyle.current != "glass" || scene == null) return@composed this
    val shader = glassShader()
    var position by remember { mutableStateOf(Offset.Zero) }
    this.clip(RoundedCornerShape(30.dp)).onGloballyPositioned { position = it.positionInRoot() }
        .drawWithContent {
            drawScene(shader, scene, position - scene.origin, 1f)
            drawContent()
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .75f), Color.White.copy(alpha = .04f), scene.colors.primary.copy(alpha = .32f), Color.White.copy(alpha = .4f))),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(30.dp.toPx()), style = Stroke(1.3.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = .08f), topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = Size((size.width - 4.dp.toPx()).coerceAtLeast(0f), (size.height - 4.dp.toPx()).coerceAtLeast(0f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()), style = Stroke(.6.dp.toPx()))
        }
}
