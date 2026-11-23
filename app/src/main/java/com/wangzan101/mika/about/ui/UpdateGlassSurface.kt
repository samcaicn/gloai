package com.wangzan101.mika.about.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.theme.MikaTheme

/** 由调样台导出的参数。网页像素在安卓布局里按 dp 使用。 */
internal enum class UpdateGlassMaterial(
    val width: Int,
    val height: Int,
    val radius: Int,
    val blur: Int,
    val tint: Float,
    val rim: Int,
    val refraction: Float,
    val dispersion: Float,
    val saturation: Float,
    val brightness: Float,
    val shadow: Float,
    val darkBackdrop: Color,
    val scrim: Float = 0f,
) {
    Capsule(207, 48, 40, 4, 0.42f, 20, 1.35f, 0.18f, 1.45f, 1.08f, 0.30f, Color(0xFF172C29)),
    Confirmation(236, 178, 35, 12, 0.55f, 31, 1.40f, 0.17f, 1.40f, 1.06f, 0.35f, Color(0xFF202D35), 0.28f),
}

internal val UpdateGlassInk: Color
    @Composable get() = if (MikaTheme.colors.isDark) Color.White.copy(alpha = 0.94f) else MikaTheme.colors.ink
internal val UpdateGlassSecondaryInk: Color
    @Composable get() = if (MikaTheme.colors.isDark) Color(0xFFEBEBF5).copy(alpha = 0.62f) else MikaTheme.colors.ink2
internal val UpdateGlassScrim = Color(0xFF16221B)
private val NightTint = Color(0xFF0C1028)

@Composable
internal fun UpdateGlassSurface(
    material: UpdateGlassMaterial,
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MikaTheme.colors.isDark
    val base = if (dark) material.darkBackdrop else Color(0xFFE9F1E6)
    val tint = if (dark) NightTint else Color.White
    val shape = RoundedCornerShape(material.radius.dp)
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.shadow(20.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = material.shadow),
            spotColor = Color.Black.copy(alpha = material.shadow * 2f))
            .clip(shape)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .border(1.dp, Brush.linearGradient(listOf(
                Color.White.copy(alpha = if (dark) 0.52f else 0.96f),
                Color.White.copy(alpha = 0.09f),
                if (dark) Color.White.copy(alpha = 0.21f) else Color.Black.copy(alpha = 0.11f),
            )), shape),
    ) {
        if (backdrop != null) {
            Box(Modifier.matchParentSize()
                .graphicsLayer {
                    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && size.width > 0f && size.height > 0f) {
                        refractedEffect(material, size.width, size.height, density, base, dark)
                    } else {
                        val blur = material.blur.dp.toPx()
                        BlurEffect(blur, blur, TileMode.Clamp)
                    }
                    clip = true
                }
                .drawBehind {
                    val delta = backdrop.origin - origin
                    translate(delta.x, delta.y) { drawLayer(backdrop.layer) }
                    if (material.scrim > 0f) drawRect(UpdateGlassScrim.copy(alpha = material.scrim))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        drawRect(base.copy(alpha = if (dark) 0.90f else 0.67f))
                    }
                })
        }
        Box(Modifier.matchParentSize().background(if (backdrop != null) tint.copy(alpha = material.tint) else base))
        Box(Modifier.matchParentSize().drawBehind {
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.23f), Color.Transparent),
                center = Offset.Zero, radius = size.width * 0.82f))
        })
        content()
    }
}

@RequiresApi(33)
private fun refractedEffect(
    material: UpdateGlassMaterial,
    width: Float,
    height: Float,
    density: Float,
    base: Color,
    dark: Boolean,
): androidx.compose.ui.graphics.RenderEffect {
    val shader = RuntimeShader(GLASS_SHADER).apply {
        setFloatUniform("extent", width, height)
        setFloatUniform("lens", material.radius * density, material.rim * density, material.refraction, material.dispersion)
        setFloatUniform("filter", material.saturation, material.brightness)
        setFloatUniform("backdropTone", base.red, base.green, base.blue, if (dark) 0.095f else 0.33f)
    }
    val refraction = RenderEffect.createRuntimeShaderEffect(shader, "image")
    val blur = material.blur * density
    return RenderEffect.createChainEffect(refraction,
        RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP)).asComposeRenderEffect()
}

// 采样本地聊天绘制层。圆角边缘向内折射，RGB 取不同偏移；中心只做轻微放大。
private const val GLASS_SHADER = """
    uniform shader image;
    uniform float2 extent;
    uniform float4 lens;
    uniform float2 filter;
    uniform float4 backdropTone;

    half3 sampleAt(float2 p) {
        return image.eval(clamp(p, float2(0.5), extent - 0.5)).rgb;
    }

    half4 main(float2 p) {
        float2 halfSize = extent * 0.5;
        float radius = min(lens.x, min(halfSize.x, halfSize.y));
        float2 center = p - halfSize;
        float2 q = abs(center) - (halfSize - radius);
        float2 corner = max(q, float2(0));
        float cornerLength = length(corner);
        float distance = radius - cornerLength - min(max(q.x, q.y), 0.0);
        float2 normal = cornerLength > 0.001
            ? sign(center) * corner / cornerLength
            : (q.x > q.y ? float2(sign(center.x), 0) : float2(0, sign(center.y)));
        float rim = min(lens.y, min(halfSize.x, halfSize.y));
        float strength = pow(clamp(1.0 - distance / rim, 0.0, 1.0), 2.6);
        float2 shift = normal * (rim * lens.z * strength);
        float2 base = p - center * 0.05;
        half3 rgb = half3(
            sampleAt(base - shift * (1.0 + lens.w)).r,
            sampleAt(base - shift).g,
            sampleAt(base - shift * (1.0 - lens.w)).b
        );
        half gray = dot(rgb, half3(0.2126, 0.7152, 0.0722));
        half3 filtered = clamp(mix(half3(gray), rgb, half(filter.x)) * half(filter.y), 0.0, 1.0);
        // 用对应主题的底色保持可读性，并保留真实聊天纹理和边缘折射。
        return half4(mix(half3(backdropTone.rgb), filtered, half(backdropTone.a)), 1.0);
    }
"""
