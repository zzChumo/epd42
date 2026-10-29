package app.chumo.epd42

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 震动反馈：优先用线性马达的「原语震动（composition primitives）」，否则回退到普通震动。 */
object Haptics {

    /** 长按反馈：线性马达上是一声干脆的 "click + tick"，普通马达则短振一下。 */
    fun longPress(context: Context) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val tick = VibrationEffect.Composition.PRIMITIVE_TICK
                val click = VibrationEffect.Composition.PRIMITIVE_CLICK
                val supported = mutableListOf<Int>()
                if (vibrator.areAllPrimitivesSupported(tick)) supported += tick
                if (vibrator.areAllPrimitivesSupported(click)) supported += click

                if (supported.isNotEmpty()) {
                    // 线性马达：震动原语组合，幅度 1.0，带一点间隔层次感
                    val composition = VibrationEffect.startComposition()
                    if (click in supported) composition.addPrimitive(click, 1.0f)
                    if (tick in supported) composition.addPrimitive(tick, 0.9f, 24)
                    vibrator.vibrate(composition.compose())
                    return
                }
            }

            // 普通转子/低版本：预定义 click，再退化为 30ms 单次震动
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
            // 部分 ROM 会抛异常，震动失败不影响功能
        }
    }
}
