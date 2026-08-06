package com.example.stripchat

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
 * Per-provider in-app settings for Stripchat: proxy URL, enabled genders and
 * enabled home rows. Persisted in SharedPreferences so they survive restarts;
 * the dialog is opened from the gear button CloudStream shows for this plugin.
 *
 * Gender changes apply after the plugin is reloaded (CloudStream registers one
 * provider entry per enabled gender). Rows and proxy are read live.
 */
object Settings {
    private const val PREFS = "TotalGirls_settings"

    private const val KEY_PROXY = "stripchat_proxy"
    private const val KEY_GENDERS = "stripchat_genders"
    private const val KEY_ROWS = "stripchat_rows"

    const val DEFAULT_PROXY = ""
    private val DEFAULT_GENDERS = setOf("f")

    /** Gender options shown in the dialog (code -> label). Couples stays a home row. */
    val GENDERS = listOf(
        "f" to "Female",
        "m" to "Guys (Male)",
        "t" to "Trans"
    )

    /** Full row set: flag genres + age genres (union across genders). */
    val ALL_ROWS = listOf(
        "HD", "New", "VR", "Mobile", "Lovense", "Kiiroo",
        "Teen", "Young", "MILF", "Mature"
    )

    private fun ctx(): Context? = StripchatProviderPlugin.appContext

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Proxy prefix (already includes ?url=). Blank means direct (no proxy). */
    fun proxy(): String {
        val c = ctx() ?: return DEFAULT_PROXY
        return prefs(c).getString(KEY_PROXY, DEFAULT_PROXY)?.trim()
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_PROXY
    }

    /** Currently enabled gender codes (default: female only). */
    fun genders(): Set<String> {
        val c = ctx() ?: return DEFAULT_GENDERS
        val saved = prefs(c).getString(KEY_GENDERS, null)?.split(',')
            .orEmpty().filter { it.isNotBlank() }.toSet()
        return saved.ifEmpty { DEFAULT_GENDERS }
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

        root.addView(label("Enabled genders (apply after plugin reload)"))
        val genderBoxes = GENDERS.map { (code, name) ->
            CheckBox(context).apply { text = name; isChecked = code in currentGenders } to code
        }
        genderBoxes.forEach { (box, _) -> root.addView(box) }

        root.addView(label("Home rows"))
        val rowBoxes = ALL_ROWS.map { name ->
            CheckBox(context).apply { text = name; isChecked = name in currentRows } to name
        }
        rowBoxes.forEach { (box, _) -> root.addView(box) }

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("Stripchat Settings")
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
