package com.katanaclaude.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** Small helpers shared by the two programmatic screens. */
object Ui {
    const val BG = 0xFF0D1117.toInt()
    const val BAR = 0xFF161B22.toInt()
    const val FG = 0xFFE6EDF3.toInt()
    const val DIM = 0xFF8B949E.toInt()
    const val ACCENT = 0xFFE8875B.toInt()
    const val OK = 0xFF3FB950.toInt()
    const val WARN = 0xFFD29922.toInt()
    const val ERR = 0xFFFF7B72.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun button(ctx: Context, text: String, onClick: () -> Unit): Button =
        Button(ctx, null, android.R.attr.buttonStyleSmall).apply {
            this.text = text
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            textSize = 14f
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
            setOnClickListener { onClick() }
        }

    fun label(ctx: Context, text: String, sizeSp: Float = 14f, color: Int = FG, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    fun mono(ctx: Context, text: String = ""): TextView = TextView(ctx).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(FG)
        setTextIsSelectable(true)
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun copy(ctx: Context, what: String, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(what, text))
        Toast.makeText(ctx, "Copied $what", Toast.LENGTH_SHORT).show()
    }

    fun info(ctx: Context, title: String, msg: String) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }
}
