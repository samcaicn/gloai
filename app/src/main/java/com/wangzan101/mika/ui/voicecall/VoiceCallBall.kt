package com.wangzan101.mika.ui.voicecall

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 通话球的形态:连接=网,平时=球(安静慢转),听/说=波(随真实电平),办事中=轨道。 */
enum class VoiceCallShape { Web, Globe, Wave, Orbits }

/** 点阵颜色:近处青白,远处深蓝,按投影深度插值。 */
private val NearColor = Color(0xFF8EFAFF)
private val FarColor = Color(0xFF123A72)

/** 相机距离(单位球半径的倍数):透视近大远小的强度。 */
private const val CAMERA = 3.4f

/** 电平过了多少球就从环换成波。 */
const val VOICE_WAVE_LEVEL = 0.04f

/**
 * 自绘 3D 点阵球:斐波那契球面点云绕 Y 轴慢转 + 轻微 X 倾角,透视投影(近大远小),
 * 深度明暗(近青白 → 远深蓝)。形态随 [shape] 在四种之间渐变:[level] 是驱动波形的真实电平
 * (0..1,取听/说里大的那个)。数学全部自写,无外部库;大小由调用方给(dp 参数化)。
 * 生命周期外停帧:跟着 [Lifecycle.State.STARTED] 走,不可见一帧不画;系统关动画时静止,
 * 只随电平起伏(不自己摆)。
 */
@Composable
fun VoiceCallBall(shape: VoiceCallShape, level: Float, modifier: Modifier = Modifier, dotCount: Int = 340) {
    val reduceMotion = MikaTheme.reduceMotion
    val ball = remember(dotCount) { DotBall(dotCount) }
    var timeNanos by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                withFrameNanos { timeNanos = it }
            }
        }
    }
    Canvas(modifier) {
        ball.draw(this, shape, level, if (reduceMotion) 0f else timeNanos / 1e9f, reduceMotion)
    }
}

/** 一个点:球面静止位置、初始方位角、所属轨道与轨道相位。 */
private class Dot(val x: Float, val y: Float, val z: Float, val theta: Float, val orbit: Int, val orbitAngle: Float)

/** 点云与每帧的投影计算;几何常量初始化一次,帧内只有数学,没有分配。 */
private class DotBall(count: Int) {
    private val dots: Array<Dot>
    private val webPairs: IntArray
    private val sx: FloatArray
    private val sy: FloatArray
    private val sr: FloatArray
    private val sd: FloatArray
    private val sz: FloatArray
    private val order: IntArray
    private val weights = FloatArray(VoiceCallShape.entries.size)
    private var lastT = -1f
    private var smoothLevel = 0f

    init {
        val golden = PI.toFloat() * (3f - sqrt(5f))
        dots = Array(count) { i ->
            val y = 1f - 2f * (i + 0.5f) / count
            val r = sqrt(1f - y * y)
            val theta = golden * i
            Dot(
                x = r * cos(theta), y = y, z = r * sin(theta), theta = theta,
                orbit = i % ORBIT_COUNT, orbitAngle = (i / ORBIT_COUNT) * (2f * PI.toFloat()) / (count / ORBIT_COUNT),
            )
        }
        // 网的边:球面上弦长小于阈值的点对,初始化算一次
        val pairs = ArrayList<Int>(count * 3)
        for (i in dots.indices) for (j in i + 1 until dots.size) {
            val dx = dots[i].x - dots[j].x
            val dy = dots[i].y - dots[j].y
            val dz = dots[i].z - dots[j].z
            if (dx * dx + dy * dy + dz * dz < WEB_CHORD2) {
                pairs.add(i)
                pairs.add(j)
            }
        }
        webPairs = pairs.toIntArray()
        sx = FloatArray(count)
        sy = FloatArray(count)
        sr = FloatArray(count)
        sd = FloatArray(count)
        sz = FloatArray(count)
        order = IntArray(count) { it }
    }

    /** 轨道面的倾角与转速:三条倾斜轨道,方向、速度各异。 */
    private val orbitTiltX = floatArrayOf(0.5f, -0.9f, 1.9f)
    private val orbitTiltY = floatArrayOf(0f, 0.7f, -0.5f)
    private val orbitSpeed = floatArrayOf(0.9f, -0.7f, 1.15f)

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, shape: VoiceCallShape, level: Float, t: Float, static: Boolean) {
        val radius = scope.size.minDimension / 2f
        val cx = scope.size.width / 2f
        val cy = scope.size.height / 2f
        val dt = if (lastT < 0f || static) 0f else (t - lastT).coerceIn(0f, 0.05f)
        // 形态权重渐变;静止时直接到位
        for (k in weights.indices) {
            val target = if (k == shape.ordinal) 1f else 0f
            weights[k] = when {
                lastT < 0f || static -> target
                else -> weights[k] + (target - weights[k]) * (dt * 8f).coerceAtMost(1f)
            }
        }
        lastT = t
        smoothLevel = if (static) level else smoothLevel + (level - smoothLevel) * (dt * 18f).coerceAtMost(1f)

        val angleY = t * 0.45f
        val cosY = cos(angleY)
        val sinY = sin(angleY)
        val cosT = cos(TILT_X)
        val sinT = sin(TILT_X)
        val w = weights
        // 待机的球安静地呼吸一下(很轻),显得有生命又不闹
        val breath = 1f + 0.02f * w[1] * sin(t * 0.7f)
        val n = dots.size
        for (i in 0 until n) {
            val d = dots[i]
            // 网/球共用整个球面(球安静慢转);波沿半径随电平起伏;轨道沿倾斜轨道跑
            val wave = 1f + WAVE_AMP * smoothLevel * (0.35f + 0.65f * sin(3f * d.theta + t * 3.5f) * sin(2.5f * d.y + t * 2f))
            val orbitAngle = d.orbitAngle + t * orbitSpeed[d.orbit]
            var ox = cos(orbitAngle)
            var oy = 0f
            var oz = sin(orbitAngle)
            // 轨道面:X 倾角再 Y 倾角
            var ty = oy * cos(orbitTiltX[d.orbit]) - oz * sin(orbitTiltX[d.orbit])
            var tz = oy * sin(orbitTiltX[d.orbit]) + oz * cos(orbitTiltX[d.orbit])
            oy = ty
            oz = tz
            val tx = ox * cos(orbitTiltY[d.orbit]) + oz * sin(orbitTiltY[d.orbit])
            tz = -ox * sin(orbitTiltY[d.orbit]) + oz * cos(orbitTiltY[d.orbit])
            ox = tx
            oz = tz

            val bx = (w[0] + w[1]) * d.x + w[2] * d.x * wave + w[3] * ox
            val by = (w[0] + w[1]) * d.y + w[2] * d.y * wave + w[3] * oy
            val bz = (w[0] + w[1]) * d.z + w[2] * d.z * wave + w[3] * oz
            // 整体绕 Y 慢转 + X 轻倾
            val rx = bx * cosY + bz * sinY
            val rz1 = -bx * sinY + bz * cosY
            val ry = by * cosT - rz1 * sinT
            val rz = by * sinT + rz1 * cosT
            val persp = CAMERA / (CAMERA - rz)
            sx[i] = cx + rx * radius * persp * BALL_SCALE * breath
            sy[i] = cy + ry * radius * persp * BALL_SCALE * breath
            sr[i] = radius * DOT_RADIUS * persp
            sd[i] = ((rz + 1.3f) / 2.6f).coerceIn(0f, 1f)
            sz[i] = rz
        }
        // 远的先画,近的盖上面;帧间顺序几乎不乱,插入排序就近 O(n)
        for (i in 1 until n) {
            val v = order[i]
            val vz = sz[v]
            var j = i - 1
            while (j >= 0 && sz[order[j]] > vz) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
        // 网的连线在点下面
        val webW = w[0]
        if (webW > 0.01f) {
            for (p in 0 until webPairs.size / 2) {
                val a = webPairs[2 * p]
                val b = webPairs[2 * p + 1]
                val depth = (sd[a] + sd[b]) / 2f
                scope.drawLine(
                    lerp(FarColor, NearColor, depth),
                    androidx.compose.ui.geometry.Offset(sx[a], sy[a]),
                    androidx.compose.ui.geometry.Offset(sx[b], sy[b]),
                    strokeWidth = radius * 0.006f,
                    alpha = webW * (0.05f + 0.3f * depth),
                )
            }
        }
        for (k in 0 until n) {
            val i = order[k]
            val depth = sd[i]
            var alpha = 0.38f + 0.62f * depth
            if (webW > 0.01f) alpha *= 1f - webW * (0.3f + 0.3f * sin(t * 2.4f - dots[i].y * 5f))
            scope.drawCircle(lerp(FarColor, NearColor, depth), sr[i], androidx.compose.ui.geometry.Offset(sx[i], sy[i]), alpha = alpha)
        }
    }

    private companion object {
        const val ORBIT_COUNT = 3
        const val WEB_CHORD2 = 0.09f
        const val WAVE_AMP = 0.26f
        const val TILT_X = 0.32f
        const val BALL_SCALE = 0.82f
        const val DOT_RADIUS = 0.023f
    }
}
