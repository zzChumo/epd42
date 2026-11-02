package app.chumo.epd42

import android.content.Context

/** 设置项存储：开发者模式 / 取模算法。 */
object SettingsStore {
    private const val PREFS = "epd42_settings"
    private const val KEY_DEVELOPER = "developer_mode"
    private const val KEY_DITHER = "dither_mode"

    const val MODE_4GRAY = "4gray"

    fun isDeveloperMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEVELOPER, false)

    fun setDeveloperMode(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEVELOPER, value).apply()
    }

    fun ditherMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DITHER, MODE_4GRAY) ?: MODE_4GRAY

    fun setDitherMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DITHER, mode).apply()
    }

    /** 开发者模式关闭时，始终使用四阶灰度取模。 */
    fun effectiveDitherMode(context: Context): String =
        if (isDeveloperMode(context)) ditherMode(context) else MODE_4GRAY
}
