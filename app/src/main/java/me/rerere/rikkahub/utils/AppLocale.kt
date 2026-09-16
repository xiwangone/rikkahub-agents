package me.rerere.rikkahub.utils

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言切换。
 *
 * 不依赖 AppCompat：本仓 Activity 继承自 ComponentActivity、主题为 Material3，
 * `AppCompatDelegate.setApplicationLocales` 在这种组合下不会真正生效。
 *
 * - API 33+：走系统 `LocaleManager`（Android 13 起的官方「按应用设置语言」），
 *   由框架负责持久化并重建 Activity；语言标签必须已声明在 `android:localeConfig` 中，
 *   否则框架会静默忽略。
 * - API 26–32：无框架 API，退化为改写进程资源并持久化到 SharedPreferences，
 *   由调用方 `recreate()` 生效；冷启动时在 Application 里重新应用。
 *
 * 只保存语言标签本身，不含任何用户数据。
 */
object AppLocale {

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    /** 已持久化的语言标签；空串表示跟随系统。 */
    fun persistedTag(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, "")
            .orEmpty()

    /** 当前生效的语言标签；空串表示跟随系统。 */
    fun currentTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val tags = context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.toLanguageTags()
                .orEmpty()
            return tags.substringBefore(',').trim()
        }
        return persistedTag(context)
    }

    /** 应用语言。[tag] 为空表示跟随系统。 */
    fun apply(context: Context, tag: String) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TAG, tag)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = app.getSystemService(LocaleManager::class.java) ?: return
            localeManager.applicationLocales =
                if (tag.isBlank()) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(tag)
                }
            return
        }

        applyToProcessResources(app, tag)
    }

    /**
     * 冷启动兜底（仅 API 26–32）：把已持久化的语言重新写进进程资源。
     * API 33+ 由系统负责持久化，无需调用。
     */
    fun restoreOnStartup(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val tag = persistedTag(context)
        applyToProcessResources(context.applicationContext, tag)
    }

    @Suppress("DEPRECATION")
    private fun applyToProcessResources(app: Context, tag: String) {
        val locale =
            if (tag.isBlank()) {
                LocaleList.getDefault().get(0) ?: Locale.getDefault()
            } else {
                Locale.forLanguageTag(tag).takeIf { it.language.isNotEmpty() } ?: Locale.getDefault()
            }
        Locale.setDefault(locale)
        val config = Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }
}
