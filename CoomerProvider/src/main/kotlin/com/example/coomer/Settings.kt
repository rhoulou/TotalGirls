package com.example.coomer

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Per-provider in-app settings for Coomer: the archive domain.
 *
 * Defaults to the official coomer.st archive. If the domain goes down, a backup
 * preset or any pasted mirror can be used instead. The archive API must expose
 * /api/v1/{service}/user/{id}/... and serve media from an img.<host> subdomain
 * for images/thumbnails to work.
 */
object Settings {
    private const val PREFS = "TotalGirls_settings"

    private const val KEY_DOMAIN = "coomer_domain"

    const val DEFAULT_DOMAIN = "https://coomer.st"
    const val BACKUP_1 = "https://coomer1.net"
    const val BACKUP_2 = "https://official.coomer.com.co"

    private fun ctx(): Context? = CoomerProviderPlugin.appContext

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Archive base URL, normalized (no trailing slash). */
    fun base(): String {
        val c = ctx() ?: return DEFAULT_DOMAIN
        return normalize(prefs(c).getString(KEY_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN)
    }

    fun normalize(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isBlank()) return DEFAULT_DOMAIN
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
        return base
    }

    /** Bare host, e.g. "coomer.st". */
    fun host(): String = base().substringAfter("://").substringBefore('/').trimEnd('/')

    /** Image host for icons/banners/thumbnails, e.g. "https://img.coomer.st". */
    fun imgHost(): String = "https://img.${host()}"

    // ------------------------------------------------------- dialog

    fun showSettings(context: Context) {
        val prefs = prefs(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }

        val label = TextView(context).apply {
            text = "Coomer archive domain (default: coomer.st). Tap a preset below to fill the field, or paste any working mirror (needs /api/v1 endpoints)."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(context, 8))
        }
        root.addView(label)

        val domainInput = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(KEY_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN)
        }
        root.addView(domainInput)

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }
        listOf(DEFAULT_DOMAIN, BACKUP_1, BACKUP_2).forEach { preset ->
            val chip = Button(context).apply {
                text = preset.removePrefix("https://")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                isAllCaps = false
                setOnClickListener { domainInput.setText(preset) }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(0, 0, dp(context, 6), 0)
            chipRow.addView(chip, lp)
        }
        root.addView(chipRow)

        val hint = TextView(context).apply {
            text = "Note: coomer1.net and official.coomer.com.co are NOT official coomer mirrors (spam/porn-tube clones) - use them only if coomer.st is unreachable and you confirmed they work."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(context, 4))
        }
        root.addView(hint)

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("Coomer Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(KEY_DOMAIN, domainInput.text.toString().trim())
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
