package com.example.xhamsterlive

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Per-provider in-app settings for XHamsterLive: proxy URL and enabled home
 * rows. Persisted in SharedPreferences so they survive restarts; the dialog is
 * opened from the gear button CloudStream shows for this plugin.
 */
object Settings {
    private const val PREFS = "TotalGirls_settings"

    private const val KEY_PROXY = "xhamsterlive_proxy"
    private const val KEY_ROWS = "xhamsterlive_rows"

    const val DEFAULT_PROXY = ""

    /** Full home row set (union across genders). */
    val ALL_ROWS = listOf(
        "All Girls", "Girls Popular", "Girls Arab", "Girls Mobile", "Girls New", "Girls VR",
        "Couples", "Couples Popular", "Group Sex", "Lesbians",
        "Men", "Men Popular", "Men Gays", "Men Straight",
        "Trans", "Trans Popular", "Trans Couples",
        "Arabic", "Asian Teens", "Teens", "Young", "White", "Ebony", "Latina", "Asian",
        "Indian", "Athletic", "Grannies", "Shaven", "Trimmed"
    )

    private fun ctx(): Context? = XhamsterliveProviderPlugin.appContext

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Proxy prefix (already includes ?url=). Blank means direct (no proxy). */
    fun proxy(): String {
        val c = ctx() ?: return DEFAULT_PROXY
        return prefs(c).getString(KEY_PROXY, DEFAULT_PROXY)?.trim()
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_PROXY
    }

    /** Enabled row names (default: all rows). */
    fun rows(): Set<String> {
        val c = ctx() ?: return ALL_ROWS.toSet()
        val saved = prefs(c).getString(KEY_ROWS, null)?.split(',')
            .orEmpty().filter { it.isNotBlank() }.toSet()
        return saved.ifEmpty { ALL_ROWS.toSet() }
    }

    fun isRowEnabled(key: String): Boolean = key in rows()

    // ------------------------------------------------------- dialog

    fun showSettings(context: Context) {
        val prefs = prefs(context)
        val currentRows = rows()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }

        fun label(text: String) = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(context, 16), 0, dp(context, 4))
        }

        root.addView(label("Proxy URL (blank = direct)"))
        val proxyInput = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(KEY_PROXY, DEFAULT_PROXY) ?: DEFAULT_PROXY)
        }
        root.addView(proxyInput)

        root.addView(label("Home rows"))
        val rowBoxes = ALL_ROWS.map { name ->
            CheckBox(context).apply { text = name; isChecked = name in currentRows } to name
        }
        rowBoxes.forEach { (box, _) -> root.addView(box) }

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("XHamsterLive Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(KEY_PROXY, proxyInput.text.toString().trim())
                    .putString(
                        KEY_ROWS,
                        rowBoxes.filter { (box, _) -> box.isChecked }.joinToString(",") { (_, name) -> name }
                    )
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
