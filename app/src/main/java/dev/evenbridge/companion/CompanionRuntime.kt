package dev.evenbridge.companion

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class CompanionApplication : Application() { override fun onCreate() { super.onCreate(); CompanionRuntime.get(this) } }

class CompanionRuntime private constructor(private val context: Application) {
    companion object {
        @Volatile private var instance: CompanionRuntime? = null
        fun get(context: Context): CompanionRuntime = instance ?: synchronized(this) {
            instance ?: CompanionRuntime(context.applicationContext as Application).also { instance = it }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).connectTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val store = SecureStore(context)
    private val wakeNotification = WakeNotification(context)
    @Volatile var wakeRelayEnabled = false; private set
    val wakePermissionGranted: Boolean get() = wakeNotification.allowed()
    private var state = JSONObject()
    @Volatile var status = "Not paired"; private set
    @Volatile var origin = ""; private set
    @Volatile var paired = false; private set
    @Volatile var listener: PhoneNotificationListener? = null; private set
    @Volatile var enabledCount = 0; private set
    private var inventory = JSONArray()
    private var inventoryTime = 0L
    private var lastSuccess = 0L
    private var lastAttemptAt = 0L
    private val wakePending = AtomicBoolean(false)

    init {
        worker.execute {
            try { state = store.read(); origin = state.optString("origin"); paired = state.optString("token").isNotEmpty(); enabledCount = enabled().size; wakeRelayEnabled = state.optBoolean("wakeRelayEnabled", false) }
            catch (_: Exception) { status = "Secure storage unavailable. Re-pair to reset." }
        }
        worker.scheduleWithFixedDelay({ sync() }, 0, 1, TimeUnit.SECONDS)
    }
    fun attach(service: PhoneNotificationListener) { listener = service; wake() }
    fun detach(service: PhoneNotificationListener) { if (listener === service) listener = null; wake() }
    fun wake() { if (wakePending.compareAndSet(false, true)) worker.schedule({ try { sync() } finally { wakePending.set(false) } }, 150, TimeUnit.MILLISECONDS) }
    fun pair(rawOrigin: String, code: String, done: (String?) -> Unit) {
        worker.execute {
            try {
                val endpoint = Protocol.endpoint(rawOrigin, BuildConfig.DEBUG)
                require(code.trim().length in 4..128) { "Enter the current pairing code from Even settings." }
                val response = post(endpoint, "/v1/phone/pair", JSONObject().put("code", code.trim()), null)
                val token = response.getString("token")
                require(token.length in 16..4096 && !token.any { it.isISOControl() })
                // Keep the journal when re-pairing to the same bridge; a crash must not permit replays.
                val previous = if (state.optString("origin") == endpoint) state else JSONObject()
                state = previous.put("origin", endpoint).put("token", token)
                store.write(state)
                origin = endpoint; paired = true; wakeRelayEnabled = state.optBoolean("wakeRelayEnabled", false); status = "Paired. Connecting…"
                main.post { done(null) }; sync()
            } catch (error: IllegalArgumentException) { main.post { done(error.message ?: "Check the bridge address and pairing code.") } }
            catch (_: Exception) { main.post { done("Pairing failed. Check HTTPS connectivity and generate a fresh code in Even settings.") } }
        }
    }
    fun testDisplayAlert(): Boolean = wakeRelayEnabled && wakeNotification.publishTest()
    fun setWakeRelayEnabled(enabled: Boolean) { worker.execute {
        state.put("wakeRelayEnabled", enabled); store.write(state); wakeRelayEnabled = enabled
        if (!enabled) onMain { wakeNotification.cancel() }
        sync()
    } }
    fun disconnect() { worker.execute { state.remove("token"); store.write(state); paired = false; status = "Disconnected"; onMain { wakeNotification.cancel() } } }
    private fun enabled(): Set<String> = arrayStrings(state.optJSONArray("enabledPackages"))
    private fun commandNow(): Long = maxOf(System.currentTimeMillis(), state.optLong("clockWatermark", 0))
    private fun arrayStrings(array: JSONArray?): Set<String> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf { value -> value.isNotBlank() } }.toSet()
    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block); main.post(task); return task.get(3, TimeUnit.SECONDS)
    }
    private fun post(endpoint: String, path: String, payload: JSONObject, token: String?): JSONObject {
        val body = payload.toString()
        require(body.toByteArray().size <= 262144)
        val request = Request.Builder().url(endpoint + path).post(body.toRequestBody("application/json".toMediaType()))
        if (token != null) request.header("Authorization", "Bearer $token")
        client.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            val source = checkNotNull(response.body).source()
            source.request(262145)
            check(source.buffer.size <= 262144)
            return JSONObject(source.readUtf8())
        }
    }
    @Suppress("DEPRECATION")
    private fun apps(observed: Set<String>): JSONArray {
        if (System.currentTimeMillis() - inventoryTime > 30_000) {
            inventory = JSONArray()
            context.packageManager.getInstalledApplications(0).filter { BuildConfig.DEBUG || it.packageName != context.packageName }.map { app ->
                val label = runCatching { context.packageManager.getApplicationLabel(app) }.getOrNull()
                JSONObject().put("packageName", app.packageName).put("name", Protocol.appLabel(app.packageName, label)).put("replyObserved", false)
            }.sortedBy { it.getString("name").lowercase() }.take(2048).forEach { inventory.put(it) }
            inventoryTime = System.currentTimeMillis()
        }
        val copy = JSONArray(inventory.toString())
        for (index in 0 until copy.length()) copy.getJSONObject(index).put("replyObserved", copy.getJSONObject(index).getString("packageName") in observed)
        return copy
    }
    private fun sync() {
        if (!paired) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAttemptAt < 500) return
        lastAttemptAt = now
        try {
            val allowed = enabled()
            val service = listener
            val targets = if (service != null) onMain { service.targets(allowed) } else emptyList()
            val observed = arrayStrings(state.optJSONArray("observed")) + targets.map { it.sbn.packageName }
            if (observed != arrayStrings(state.optJSONArray("observed"))) { state.put("observed", JSONArray(observed.toList())); store.write(state) }
            val appList = apps(observed)
            val notifications = JSONArray()
            val journal = CommandJournal(state.optJSONObject("journal") ?: JSONObject())
            val beforePrune = journal.data.toString()
            journal.prune(commandNow())
            if (journal.data.toString() != beforePrune) { state.put("clockWatermark", commandNow()).put("journal", journal.data); store.write(state) }
            val pending = state.optJSONArray("pending") ?: JSONArray()
            val payload = JSONObject().put("v", 1).put("apps", appList).put("notifications", notifications)
                .put("enabledPackages", JSONArray(allowed.toList())).put("permissionGranted", service != null).put("results", pending)
                .put("wakeRelayEnabled", wakeRelayEnabled && wakeNotification.allowed())
            // Fit the bounded protocol. Prefer newest notifications; never partially serialize a notification.
            for (target in targets.sortedByDescending { it.wire.getLong("arrivedAt") }.take(100)) {
                notifications.put(target.wire)
                if (payload.toString().toByteArray().size > 250000) { notifications.remove(notifications.length() - 1); break }
            }
            while (payload.toString().toByteArray().size > 250000 && appList.length() > 0) appList.remove(appList.length() - 1)
            val visiblePackages = (0 until appList.length()).map { appList.getJSONObject(it).getString("packageName") }.toSet()
            for (index in notifications.length() - 1 downTo 0) if (notifications.getJSONObject(index).getString("packageName") !in visiblePackages) notifications.remove(index)
            val response = post(origin, "/v1/phone/sync", payload, state.getString("token"))
            check(response.getInt("v") == 1)
            val receivedSettings = response.getJSONArray("enabledPackages")
            require(receivedSettings.length() <= 2048)
            val nextEnabled = arrayStrings(receivedSettings)
            require(nextEnabled.all { it.length <= 255 && it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")) })
            val commands = response.getJSONArray("commands")
            require(commands.length() <= 32)
            val validatedCommands = (0 until commands.length()).map { Protocol.command(commands.getJSONObject(it)) }
            require(validatedCommands.map { it.id }.toSet().size == validatedCommands.size)
            val wakeEvents = WakeEvent.batch(if (response.has("wakeEvents")) response.getJSONArray("wakeEvents") else JSONArray())
            val previousState = state.toString()
            val wakeJournal = WakeJournal(state.optJSONObject("wakeJournal") ?: JSONObject())
            val freshWakeEvents = wakeJournal.consume(wakeEvents, commandNow())
            val newPending = JSONArray()
            state.put("enabledPackages", JSONArray(nextEnabled.toList())).put("pending", newPending).put("journal", journal.data)
                .put("wakeJournal", wakeJournal.data)
            if (freshWakeEvents.isNotEmpty()) state.put("clockWatermark", commandNow())
            if (state.toString() != previousState) store.write(state)
            // Receipt commit precedes posting, so process death cannot replay a native wake.
            if (wakeRelayEnabled && freshWakeEvents.isNotEmpty()) onMain { wakeNotification.publish(freshWakeEvents.filter { it.timely(commandNow()) }) }
            enabledCount = nextEnabled.size
            lastSuccess = System.currentTimeMillis()
            status = if (listener != null) "Connected · ${targets.size} replyable notifications" else "Connected · notification access needed"
            for (command in validatedCommands) {
                var result = journal.status(command.id)
                if (!command.timely(commandNow()) || (result.isEmpty() && journal.data.length() >= 4096)) {
                    newPending.put(JSONObject().put("id", command.id).put("status", if (!command.timely(commandNow())) "unavailable" else "failed"))
                    store.write(state)
                    continue
                }
                if (result.isEmpty()) {
                    // Commit intent BEFORE invoking PendingIntent. Process death yields 'unknown', never a replay.
                    journal.record(command, "unknown")
                    state.put("clockWatermark", commandNow())
                    store.write(state)
                    result = onMain { if (command.timely(commandNow())) listener?.execute(command, nextEnabled) ?: "unavailable" else "unavailable" }
                    journal.record(command, result)
                }
                newPending.put(JSONObject().put("id", command.id).put("status", result))
                store.write(state)
            }
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("EvenCompanion", "Sync failed: ${error.javaClass.simpleName}${if (error is HttpFailure) " HTTP ${error.code}" else ""} at ${error.stackTrace.firstOrNull()?.methodName}")
            if (System.currentTimeMillis() - lastSuccess >= 6_000) status = "Bridge disconnected · retrying"
        }
    }
}
private class HttpFailure(val code: Int) : Exception()
