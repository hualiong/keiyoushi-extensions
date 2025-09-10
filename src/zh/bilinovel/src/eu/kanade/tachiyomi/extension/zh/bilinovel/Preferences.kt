package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat

const val PREF_POPULAR_DISPLAY = "POPULAR_DISPLAY"
const val PREF_SCREEN_BG_COLOR = "SCREEN_BG_COLOR"
const val PREF_DARK_MODE = "DARK_MODE"
const val PREF_SCREEN_FONT_COLOR = "FONT_COLOR"

val RGB_REGEX = Regex("^#[0-9A-F]{6}$", RegexOption.IGNORE_CASE)

fun preferencesInternal(context: Context, pref: SharedPreferences): Array<Preference> {
    return arrayOf(
        ListPreference(context).apply {
            key = PREF_POPULAR_DISPLAY
            title = "热门漫画显示内容"
            summary = "%s"
            entries = arrayOf(
                "月点击榜",
                "周点击榜",
                "月推荐榜",
                "周推荐榜",
                "月鲜花榜",
                "周鲜花榜",
                "月鸡蛋榜",
                "周鸡蛋榜",
                "最新入库",
                "收藏榜",
                "新书榜",
            )
            entryValues = arrayOf(
                "/top/monthvisit/%d.html",
                "/top/weekvisit/%d.html",
                "/top/monthvote/%d.html",
                "/top/weekvote/%d.html",
                "/top/monthflower/%d.html",
                "/top/weekflower/%d.html",
                "/top/monthegg/%d.html",
                "/top/weekegg/%d.html",
                "/top/postdate/%d.html",
                "/top/goodnum/%d.html",
                "/top/newhot/%d.html",
            )
            setDefaultValue("/top/weekvisit/%d.html")
        },
        EditTextPreference(context).apply {
            key = PREF_SCREEN_BG_COLOR
            title = "阅读页面背景颜色"
            summary = pref.getString(key, "#FAFAF8")
            dialogMessage = "请输入正确的十六进制颜色代码，否则不生效，如：#FAFAF8"
            setDefaultValue("#FAFAF8")
            setOnPreferenceChangeListener { _, newValue ->
                if (RGB_REGEX.matches(newValue as String)) {
                    summary = newValue
                    Toast.makeText(context, "清除章节缓存后生效", Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(
                        context,
                        "“$newValue” 不是一个正确的十六进制颜色代码！",
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        },
        EditTextPreference(context).apply {
            key = PREF_SCREEN_FONT_COLOR
            title = "阅读页面字体颜色"
            summary = pref.getString(key, "#000000")
            dialogMessage = "请输入正确的十六进制颜色代码，否则不生效，如：#000000"
            setDefaultValue("#000000")
            setOnPreferenceChangeListener { _, newValue ->
                if (RGB_REGEX.matches(newValue as String)) {
                    summary = newValue
                    Toast.makeText(context, "清除章节缓存后生效", Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(
                        context,
                        "“$newValue” 不是一个正确的十六进制颜色代码！",
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        },
        SwitchPreferenceCompat(context).apply {
            key = PREF_DARK_MODE
            title = "深色模式"
            summary = "开启后，阅读页面的样式将强制使用黑底白字"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(context, "清除章节缓存后生效", Toast.LENGTH_LONG).show()
                true
            }
        },
    )
}
