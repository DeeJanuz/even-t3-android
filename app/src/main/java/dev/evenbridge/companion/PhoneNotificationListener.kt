package dev.evenbridge.companion

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

data class ReplyTarget(val sbn: StatusBarNotification, val action: Notification.Action, val input: RemoteInput, val wire: JSONObject)

class PhoneNotificationListener : NotificationListenerService() {
    private val arrivals = mutableMapOf<String, Pair<String, Long>>()
    override fun onListenerConnected() { CompanionRuntime.get(this).attach(this) }
    override fun onListenerDisconnected() { CompanionRuntime.get(this).detach(this); requestRebind(android.content.ComponentName(this, javaClass)) }
    override fun onDestroy() { CompanionRuntime.get(this).detach(this); super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { CompanionRuntime.get(this).wake() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { sbn?.let { arrivals.remove(it.key) }; CompanionRuntime.get(this).wake() }

    fun targets(enabled: Set<String>): List<ReplyTarget> = (activeNotifications ?: emptyArray()).mapNotNull { sbn ->
        try {
        // The phone is the only authority for active actions. Never deserialize an action from the bridge.
        if (!Protocol.captureAllowed(sbn.packageName, packageName, enabled, BuildConfig.DEBUG, sbn.notification.channelId) || sbn.user != Process.myUserHandle() || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@mapNotNull null
        val candidates = sbn.notification.actions.orEmpty().filter { action ->
            action.actionIntent?.creatorPackage == sbn.packageName && action.remoteInputs.orEmpty().count { it.allowFreeFormInput } == 1 &&
                !(Build.VERSION.SDK_INT >= 31 && action.actionIntent.isImmutable) &&
                !(Build.VERSION.SDK_INT >= 31 && action.isAuthenticationRequired && getSystemService(KeyguardManager::class.java).isDeviceLocked)
        }
        val replyCandidates = candidates.filter { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
        val action = (if (replyCandidates.isNotEmpty()) replyCandidates else candidates).singleOrNull() ?: return@mapNotNull null
        val input = action.remoteInputs.first { it.allowFreeFormInput }
        val extras = sbn.notification.extras
        val title = Protocol.clean(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE), 200)
        val messages = if (Build.VERSION.SDK_INT >= 30) Notification.MessagingStyle.Message.getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES)) else emptyList()
        val rawText = if (messages.isNotEmpty()) messages.joinToString("\n") { message ->
            val sender = message.senderPerson?.name?.toString().orEmpty()
            (if (sender.isNotBlank()) "$sender: " else "") + message.text.toString()
        } else extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        val text = Protocol.clean(rawText?.toString()?.takeLast(4000), 4000)
        if (text.isBlank()) return@mapNotNull null
        val sender = Protocol.clean(messages.lastOrNull()?.senderPerson?.name, 200)
        val content = "$title\u0000$text\u0000$sender"
        val contentHash = Protocol.hash(content)
        val previous = arrivals[sbn.key]
        val arrived = if (previous?.first == contentHash) previous.second else if (previous == null) sbn.postTime else System.currentTimeMillis()
        arrivals[sbn.key] = contentHash to arrived
        val revision = Protocol.hash("${sbn.postTime}\u0000$content\u0000${input.resultKey}\u0000${action.actionIntent.hashCode()}")
        val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val wire = JSONObject().put("key", Protocol.hash(sbn.key)).put("revision", revision).put("packageName", sbn.packageName)
            .put("appName", Protocol.appLabel(sbn.packageName, appName)).put("title", title).put("text", text).put("arrivedAt", arrived).put("replyable", true)
        if (sender.isNotEmpty()) wire.put("sender", sender)
        if (extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE)) wire.put("thread", title)
        ReplyTarget(sbn, action, input, wire)
        } catch (_: RuntimeException) { null } // One malformed app notification must not block the entire inbox.
    }

    fun execute(command: Command, enabled: Set<String>): String {
        if (!command.timely(System.currentTimeMillis())) { if (BuildConfig.DEBUG) android.util.Log.d("EvenCompanion", "Reply unavailable: expired"); return "unavailable" }
        val active = targets(enabled)
        val target = active.firstOrNull { it.wire.getString("key") == command.key && it.wire.getString("revision") == command.revision }
        if (target == null) {
            if (BuildConfig.DEBUG) android.util.Log.d("EvenCompanion", "Reply unavailable: target mismatch key=${command.key} expected=${command.revision} actual=${active.firstOrNull { it.wire.getString("key") == command.key }?.wire?.getString("revision")}")
            return "unavailable"
        }
        if (command.operation == "dismiss") { cancelNotification(target.sbn.key); return "dismissed" }
        return try {
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(target.input.resultKey, command.text) }
            RemoteInput.addResultsToIntent(target.action.remoteInputs, intent, results)
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            target.action.actionIntent.send(this, 0, intent)
            // Android offers no atomic compare-and-cancel. Re-read immediately, preserving any observed new revision.
            val latest = targets(enabled).firstOrNull { it.wire.getString("key") == command.key }
            if (latest?.wire?.getString("revision") == command.revision) cancelNotification(target.sbn.key)
            "handed_off"
        } catch (_: PendingIntent.CanceledException) { if (BuildConfig.DEBUG) android.util.Log.d("EvenCompanion", "Reply unavailable: cancelled intent"); "unavailable" }
        catch (_: SecurityException) { "failed" }
        catch (_: RuntimeException) { "unknown" }
    }
}
