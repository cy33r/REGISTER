package app.tether.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * REGISTER: the app's own language (Persian, or English in capitals) and look (like the phone, light or dark), chosen
 * in the app whatever the phone's own settings are. Persian is the default.
 */
object UiPrefs {
    private const val FILE = "register_ui"
    const val FA = "fa"
    const val EN = "en"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lang(ctx: Context): String = prefs(ctx).getString("lang", FA) ?: FA
    fun theme(ctx: Context): String = prefs(ctx).getString("theme", "system") ?: "system"
    fun isFa(ctx: Context) = lang(ctx) == FA

    /** An activity's base context in the chosen language, direction and look (every Android version). */
    fun wrap(base: Context): Context {
        val c = Configuration(base.resources.configuration)
        val l = Locale.forLanguageTag(lang(base))
        c.setLocale(l); c.setLayoutDirection(l)
        val night = when (theme(base)) {
            "light" -> Configuration.UI_MODE_NIGHT_NO
            "dark" -> Configuration.UI_MODE_NIGHT_YES
            else -> null
        }
        if (night != null) c.uiMode = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(c)
    }

    /** At process start: Android 13+ also gets the language for the whole app (notifications, services). */
    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            val lm = ctx.getSystemService(LocaleManager::class.java)
            val want = LocaleList.forLanguageTags(lang(ctx))
            if (lm.applicationLocales != want) lm.applicationLocales = want
        }
    }

    fun setLang(a: Activity, l: String) {
        if (l == lang(a)) return
        prefs(a).edit().putString("lang", l).commit()
        init(a)
        a.recreate()
    }

    fun setTheme(a: Activity, t: String) {
        if (t == theme(a)) return
        prefs(a).edit().putString("theme", t).commit()
        a.recreate()
    }
}
