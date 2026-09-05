package dev.evenbridge.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runtime: CompanionRuntime
    private lateinit var status: TextView
    private val refresh = object : Runnable { override fun run() { status.text = "${runtime.status}\n${runtime.enabledCount} apps enabled in Even settings\nNotification access: ${if (runtime.listener != null) "connected" else "not connected"}"; handler.postDelayed(this, 1000) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(0xfff4f6f2.toInt())
        runtime = CompanionRuntime.get(this)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setPadding(0, 10, 0, 16); layout.addView(this) }
        text("Even Phone Companion", 27f)
        text("Reply to your phone notifications from your glasses.")
        status = text(runtime.status)
        text("1. Allow notification access", 20f)
        text("Android grants access to notifications. Only apps you enable in Even settings forward readable notifications with a text reply. Replies are sent only when you confirm Send on the glasses.")
        layout.addView(Button(this).apply { text = "Open notification access"; setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).putExtra(":settings:fragment_args_key", ComponentName(this@MainActivity, PhoneNotificationListener::class.java).flattenToString()))
        } })
        text("2. Pair with your T3 Code Bridge", 20f)
        text("In the T3 Code Bridge app in Even, open Android notifications to generate a pairing code. Enter your private HTTPS T3 Code Bridge address below.")
        val origin = EditText(this).apply { hint = "https://your-bridge.your-tailnet.ts.net"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setSingleLine(); setText(runtime.origin); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO; layout.addView(this) }
        val code = EditText(this).apply { hint = "Pairing code"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO; layout.addView(this) }
        val feedback = text("")
        layout.addView(Button(this).apply { text = "Pair with T3 Code Bridge"; setOnClickListener {
            isEnabled = false; feedback.text = "Pairing…"
            runtime.pair(origin.text.toString(), code.text.toString()) { error ->
                isEnabled = true; feedback.text = error ?: "Paired. Enable your messaging apps in Even settings."; if (error == null) code.text.clear()
            }
        } })
        layout.addView(Button(this).apply { text = "Disconnect from T3 Code Bridge"; setOnClickListener { runtime.disconnect() } })
        text("3. Choose apps in Even settings", 20f)
        text("Apps start disabled. Signal, Google Messages, WhatsApp, Gmail, and other apps work when their individual notifications offer a compatible text reply. Keep your T3 Code Bridge reachable. Connection loss cancels unfinished replies on the glasses.")
        layout.addView(Button(this).apply { text = "Open-source licenses"; setOnClickListener {
            val notices = TextView(this@MainActivity).apply {
                text = listOf("LICENSE.txt", "THIRD_PARTY_NOTICES.txt").joinToString("\n\n") { asset ->
                    assets.open(asset).bufferedReader().use { it.readText() }
                }
                textSize = 14f
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            AlertDialog.Builder(this@MainActivity).setTitle("Open-source licenses")
                .setView(ScrollView(this@MainActivity).apply { addView(notices) })
                .setPositiveButton("Close", null).show()
        } })
        if (BuildConfig.DEBUG) text("Debug build: ADB simulation hooks available. HTTP is allowed only for localhost and the emulator host (10.0.2.2).", 13f)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(layout) })
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
