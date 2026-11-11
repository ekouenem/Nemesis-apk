package com.nemesis.apk

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        val density = resources.displayMetrics.density
        fun px(n: Int) = (n * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(12))
            setBackgroundColor(Color.rgb(17, 20, 31))
        }
        val title = TextView(this).apply { text = "NEMESIS  /  prototype"; textSize = 22f; setTextColor(Color.WHITE) }
        val subtitle = TextView(this).apply {
            text = "Moteur Python · NemApi séparé : 127.0.0.1:8090\nOutil disponible : echo uniquement"
            textSize = 12f; setTextColor(Color.rgb(169, 184, 211)); setPadding(0, px(4), 0, px(10))
        }
        transcript = TextView(this).apply {
            text = "Démarre NemApi, ouvre DeepSeek, puis écris un message.\n\n"
            textSize = 16f; setTextColor(Color.rgb(233, 236, 245)); setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(transcript); isFillViewport = true }
        input = EditText(this).apply {
            hint = "Ton message…"; setSingleLine(false); setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY); imeOptions = EditorInfo.IME_ACTION_SEND
            setBackgroundColor(Color.rgb(38, 43, 59)); setPadding(px(12), px(8), px(12), px(8))
            minLines = 2; maxLines = 5
        }
        sendButton = Button(this).apply { text = "Envoyer"; setOnClickListener { send() } }
        layout.addView(title); layout.addView(subtitle)
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(input, LinearLayout.LayoutParams(-1, -2))
        layout.addView(sendButton, LinearLayout.LayoutParams(-1, -2).apply { gravity = Gravity.END })
        setContentView(layout)
    }
    private fun append(line: String) { transcript.append(line + "\n\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    private fun send() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        input.setText(""); sendButton.isEnabled = false
        append("Vous : $message")
        worker.execute {
            val answer = try {
                val raw = Python.getInstance().getModule("prototype_agent").callAttr("send", message).toString()
                val json = JSONObject(raw)
                val trace = json.optJSONArray("steps")?.let { a -> (0 until a.length()).joinToString(" · ") { a.optString(it) } } ?: ""
                val body = if (json.optBoolean("ok")) json.optString("text") else "Erreur : " + json.optString("error")
                (if (trace.isNotBlank()) "[$trace]\n" else "") + body
            } catch (e: Exception) { "Erreur Python : ${e.message}" }
            runOnUiThread { append("NEMESIS : $answer"); sendButton.isEnabled = true }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
