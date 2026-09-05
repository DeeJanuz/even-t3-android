package dev.evenbridge.companion

import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

object Protocol {
    fun endpoint(raw: String, debug: Boolean): String {
        val uri = URI(raw.trim())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.host != null) { "Enter a bridge origin without credentials, path, or query." }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Enter the bridge origin only." }
        val localDebug = debug && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2", "[::1]", "::1")
        require(uri.scheme == "https" || localDebug) { "Use HTTPS. Local emulator HTTP is available in debug builds only." }
        require(uri.port == -1 || uri.port in 1..65535)
        return uri.toASCIIString().trimEnd('/')
    }
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun clean(value: CharSequence?, max: Int): String = value?.toString()?.replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), "")?.take(max) ?: ""
    fun appLabel(packageName: String, label: CharSequence?): String = clean(label, 128).ifBlank { packageName.take(128) }
    fun command(json: JSONObject): Command {
        val id = json.getString("id")
        require(id.matches(Regex("[0-9]{13}-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")))
        for (field in listOf("issuedAt", "expiresAt")) {
            val value = json.get(field)
            require(value is Number && value.toDouble() == value.toLong().toDouble())
        }
        val issuedAt = json.getLong("issuedAt")
        val expiresAt = json.getLong("expiresAt")
        require(id.substringBefore('-').toLong() == issuedAt && expiresAt == issuedAt + 30_000)
        val operation = json.getString("operation")
        require(operation == "reply" || operation == "dismiss")
        val key = json.getString("key")
        val revision = json.getString("revision")
        require(key.matches(Regex("[a-f0-9]{64}")) && revision.matches(Regex("[a-f0-9]{64}")))
        val text = if (operation == "reply") json.getString("text") else null
        require(text == null || (text.isNotBlank() && text.length <= 4000 && !text.contains('\u0000')))
        return Command(id, operation, key, revision, text, issuedAt, expiresAt)
    }
}
data class Command(val id: String, val operation: String, val key: String, val revision: String, val text: String?, val issuedAt: Long, val expiresAt: Long) {
    fun timely(now: Long) = issuedAt <= now + 5000 && expiresAt > now
}

/** Timestamp-bound IDs cannot be renewed after journal pruning. Persist this object before dispatch. */
class CommandJournal(val data: JSONObject) {
    fun prune(now: Long) {
        data.keys().asSequence().toList().forEach { id ->
            val entry = data.optJSONObject(id)
            if (entry == null || entry.optLong("expiresAt") < now - 5000) data.remove(id)
        }
    }
    fun status(id: String): String = data.optJSONObject(id)?.optString("status").orEmpty()
    fun record(command: Command, status: String) { data.put(command.id, JSONObject().put("status", status).put("expiresAt", command.expiresAt)) }
}
