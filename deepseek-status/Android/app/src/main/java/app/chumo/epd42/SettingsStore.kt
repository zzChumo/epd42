package app.chumo.epd42

import android.content.Context

/** 设置项存储：开发者模式 / 取模算法。 */
object SettingsStore {
    private const val PREFS = "epd42_settings"
    private const val KEY_DEVELOPER = "developer_mode"
    private const val KEY_DITHER = "dither_mode"
    private const val KEY_MAC = "specified_mac"

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

    // ── 指定 MAC 地址（开发者模式）──
    fun specifiedMac(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAC, "") ?: ""

    fun setSpecifiedMac(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MAC, value).apply()
    }

    /** 规范化 MAC（支持 : / - / . 分隔）；格式非法返回 null。 */
    fun normalizeMac(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val parts = text.split(':', '-', '.')
        if (parts.size != 6) return null
        for (part in parts) {
            if (part.length != 2) return null
            for (c in part) {
                if (!(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')) return null
            }
        }
        return parts.joinToString(":") { it.uppercase() }
    }

    /** 开发者模式下用户指定了合法 MAC 时返回规范化地址，否则 null。 */
    fun effectiveMac(context: Context): String? {
        if (!isDeveloperMode(context)) return null
        return normalizeMac(specifiedMac(context))
    }
}
