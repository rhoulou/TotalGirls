package com.example.streamate

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
 * Per-provider in-app settings for Streamate: proxy URL, enabled genders
 * (applied to search + keyword rows) and enabled home rows. Persisted in
 * SharedPreferences; the dialog opens from the gear button CloudStream shows.
 *
 * The default proxy is blank (the /v4 guest gateway + manifest server scrape
 * fine directly). When set, GET requests are routed through it.
 */
object Settings {
    private const val PREFS = "TotalGirls_settings"

    private const val KEY_PROXY = "streamate_proxy"
    private const val KEY_GENDERS = "streamate_genders"
    private const val KEY_ROWS = "streamate_rows"

    const val DEFAULT_PROXY = ""
    private val DEFAULT_GENDERS = setOf("f", "m", "tm2f", "mf", "ff")

    /** Gender options shown in the dialog (autocomplete gender code -> label). */
    val GENDERS = listOf(
        "f" to "Female",
        "m" to "Guys (Male)",
        "tm2f" to "Trans",
        "mf" to "Couples (M/F)",
        "ff" to "Couples (F/F)"
    )

    /** Keyword home rows: keyword -> label (queried via the /v4/search gateway). */
    val ALL_ROWS = listOf(
        "milf" to "MILF",
        "bbw" to "BBW",
        "latina" to "Latina",
        "ebony" to "Ebony",
        "blonde" to "Blonde",
        "mature" to "Mature",
        "feet" to "Feet",
        "asian" to "Asian"
    )

    private fun ctx(): Context? = StreamateProviderPlugin.appContext

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Proxy prefix (already includes ?url=). Blank means direct (no proxy). */
    fun proxy(): String {
        val c = ctx() ?: return DEFAULT_PROXY
        return prefs(c).getString(KEY_PROXY, DEFAULT_PROXY)?.trim()
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_PROXY
    }

    /** Currently enabled gender codes (default: all). */
    fun genders(): Set<String> {
        val c = ctx() ?: return DEFAULT_GENDERS
        val saved = prefs(c).getString(KEY_GENDERS, null)?.split(',')
            .orEmpty().filter { it.isNotBlank() }.toSet()
        return saved.ifEmpty { DEFAULT_GENDERS }
    }

    /** Keys of the enabled keyword rows (default: all rows). */
    fun rows(): Set<String> {
        val c = ctx() ?: return ALL_ROWS.map { it.first }.toSet()
        val saved = prefs(c).getString(KEY_ROWS, null)?.split(',')
            .orEmpty().filter { it.isNotBlank() }.toSet()
        return saved.ifEmpty { ALL_ROWS.map { it.first }.toSet() }
    }

    fun isRowEnabled(key: String): Boolean = key in rows()

    // ------------------------------------------------------- dialog

    fun showSettings(context: Context) {
        val prefs = prefs(context)
        val currentGenders = genders()
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

        root.addView(label("Enabled genders (search + keyword rows)"))
        val genderBoxes = GENDERS.map { (code, name) ->
            CheckBox(context).apply { text = name; isChecked = code in currentGenders } to code
        }
        genderBoxes.forEach { (box, _) -> root.addView(box) }

        root.addView(label("Home rows"))
        val rowBoxes = ALL_ROWS.map { (key, name) ->
            CheckBox(context).apply { text = name; isChecked = key in currentRows } to key
        }
        rowBoxes.forEach { (box, _) -> root.addView(box) }

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("Streamate Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(KEY_PROXY, proxyInput.text.toString().trim())
                    .putString(
                        KEY_GENDERS,
                        genderBoxes.filter { (box, _) -> box.isChecked }.joinToString(",") { (_, code) -> code }
                    )
                    .putString(
                        KEY_ROWS,
                        rowBoxes.filter { (box, _) -> box.isChecked }.joinToString(",") { (_, key) -> key }
                    )
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
