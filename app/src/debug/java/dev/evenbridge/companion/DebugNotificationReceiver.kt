package dev.evenbridge.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Manifest restricts entry to the shell/system DUMP permission. Absent entirely from release APK. */
class DebugNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operation = intent.getStringExtra("operation") ?: "post"
        val id = intent.getIntExtra("id", 42).coerceIn(1, 999)
        val manager = context.getSystemService(NotificationManager::class.java)
        when (operation) {
            "pair" -> {
                val pending = goAsync()
                CompanionRuntime.get(context).pair(intent.getStringExtra("origin").orEmpty(), intent.getStringExtra("code").orEmpty()) { error ->
                    pending.resultCode = if (error == null) 0 else 1
                    pending.resultData = error ?: "paired"
                    pending.finish()
                }
            }
            "disconnect" -> CompanionRuntime.get(context).disconnect()
            "remove" -> manager.cancel(id)
            "clear" -> manager.cancelAll()
            "post", "update", "readonly", "immutable", "failure", "newer-on-reply" -> {
                manager.createNotificationChannel(NotificationChannel("simulation", "Local simulation", NotificationManager.IMPORTANCE_DEFAULT))
                val builder = Notification.Builder(context, "simulation").setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(intent.getStringExtra("title") ?: "Simulated conversation")
                    .setContentText(intent.getStringExtra("text") ?: "A local test message")
                    .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_MESSAGE)
                if (operation != "readonly") {
                    val replyIntent = Intent(context, DebugReplyReceiver::class.java).setAction("dev.evenbridge.companion.SIMULATED_REPLY")
                        .putExtra("id", id).putExtra("newer", operation == "newer-on-reply")
                    val mutability = if (operation == "immutable") PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_MUTABLE
                    val pending = PendingIntent.getBroadcast(context, id, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
                    val input = RemoteInput.Builder("reply").setLabel("Reply").setAllowFreeFormInput(true).build()
                    builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pending).addRemoteInput(input)
                        .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build())
                    if (operation == "failure") pending.cancel()
                }
                try { manager.notify(id, builder.build()) }
                catch (error: IllegalArgumentException) {
                    if (operation != "immutable") throw error
                    // Current Android rejects malformed immutable replies at posting time. This expected
                    // simulation result must not kill the listener process and invalidate other targets.
                    context.getSharedPreferences("simulation", Context.MODE_PRIVATE).edit().putBoolean("immutableRejected", true).apply()
                }
            }
        }
    }
}

class DebugReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString()
        if (reply.isNullOrBlank()) return
        val preferences = context.getSharedPreferences("simulation", Context.MODE_PRIVATE)
        // Persist only test counters, never reply contents.
        preferences.edit().putInt("replyCount", preferences.getInt("replyCount", 0) + 1).putInt("lastReplyId", intent.getIntExtra("id", 42))
            .putString("lastReplyHash", Protocol.hash(reply)).apply()
        if (intent.getBooleanExtra("newer", false)) {
            DebugNotificationReceiver().onReceive(context, Intent().putExtra("operation", "post").putExtra("id", intent.getIntExtra("id", 42)).putExtra("text", "New message after reply"))
        }
    }
}
