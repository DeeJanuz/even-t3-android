package dev.evenbridge.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Generic native notification only. Even owns display wake and its native overlay gestures. */
class WakeNotification(private val context: Context) {
    companion object {
        const val CHANNEL = "even_display_wake_v1"
        private const val NOTIFICATION_ID = 4701
    }
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var lastPosted = Long.MIN_VALUE
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Glasses display alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Experimental alerts forwarded through Even Auto Display. No message contents."
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    fun allowed(): Boolean = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun cancel() { manager.cancel(NOTIFICATION_ID) }
    fun publish(events: List<WakeEvent>) {
        if (events.isEmpty()) return
        post(if (events.any { it.kind == "t3" }) "T3 reply ready" else "Tracked phone notification",
            "Open T3 Code Assistant on your glasses.")
    }
    fun publishTest(): Boolean = post("Display wake test", "Testing Even Auto Display.")
    private fun post(title: String, text: String): Boolean {
        if (!allowed()) return false
        val now = SystemClock.elapsedRealtime()
        // Consume bursts, with one replaceable notification and no automatic retry.
        if (lastPosted != Long.MIN_VALUE && now - lastPosted < 3000) return false
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_companion).setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent).setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setTimeoutAfter(5000)
            .build()
        return try { manager.notify(NOTIFICATION_ID, notification); lastPosted = now; true }
        catch (_: SecurityException) { false } // Permission may change between check and post.
    }
}

data class WakeEvent(val id: String, val kind: String, val issuedAt: Long, val expiresAt: Long) {
    fun timely(now: Long) = issuedAt <= now + 5000 && expiresAt > now
    companion object {
        fun parse(json: JSONObject): WakeEvent {
            val id = json.getString("id")
            val kind = json.getString("kind")
            require(id.matches(Regex("[a-f0-9]{64}")) && kind in setOf("t3", "phone"))
            for (field in listOf("issuedAt", "expiresAt")) {
                val value = json.get(field)
                require(value is Number && value.toDouble() == value.toLong().toDouble())
            }
            val issuedAt = json.getLong("issuedAt")
            val expiresAt = json.getLong("expiresAt")
            require(issuedAt in 1_000_000_000_000..9_000_000_000_000 && expiresAt == issuedAt + 30_000)
            return WakeEvent(id, kind, issuedAt, expiresAt)
        }
        fun batch(array: JSONArray): List<WakeEvent> {
            require(array.length() <= 8)
            val events = (0 until array.length()).map { parse(array.getJSONObject(it)) }
            require(events.map { it.id }.toSet().size == events.size)
            return events
        }
    }
}

/** Persist only receipt IDs and expiry in the existing encrypted state before native posting. */
class WakeJournal(val data: JSONObject) {
    fun consume(events: List<WakeEvent>, now: Long): List<WakeEvent> {
        data.keys().asSequence().toList().forEach { id -> if (data.optLong(id) < now - 5000) data.remove(id) }
        return events.filter { event ->
            if (!event.timely(now) || data.has(event.id) || data.length() >= 256) false
            else { data.put(event.id, event.expiresAt); true }
        }
    }
}
