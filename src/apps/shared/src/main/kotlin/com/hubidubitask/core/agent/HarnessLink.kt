package com.hubidubitask.core.agent

import com.hubidubitask.core.EntityRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Harness is Claude Code on a computer in the home network, served by
 * src/harness and reached at the IP and port the user types. Neither app runs
 * it. When a question may use tools, the app sends its items along; the
 * changes the Harness's item tools make come back as [HarnessAction]s for the
 * app to apply to its own data.
 */
object HarnessLink {
    const val DEFAULT_PORT = "8765"

    /** "https://ip:port" from what was typed; blank without an IP. "ip:port" in the IP box works too. */
    fun endpoint(host: String, port: String): String {
        var h = host.trim().substringAfter("://").substringBefore('/').trim()
        var p = port.trim()
        Regex("^([^:\\[\\]]+):(\\d+)$").matchEntire(h)?.let { h = it.groupValues[1]; p = it.groupValues[2] }
        if (h.isBlank()) return ""
        if (h.count { it == ':' } > 1 && !h.startsWith("[")) h = "[$h]"   // IPv6
        return "https://$h:${p.ifBlank { DEFAULT_PORT }}"
    }

    /** The IP and port of a saved endpoint, for the settings form. */
    fun split(endpoint: String?): Pair<String, String> {
        val hp = endpoint.orEmpty().trim().substringAfter("://").substringBefore('/')
        if (hp.isBlank()) return "" to DEFAULT_PORT
        if (hp.startsWith("[")) return hp.substringAfter('[').substringBefore(']') to
            hp.substringAfter("]:", DEFAULT_PORT)
        return if (':' in hp) hp.substringBeforeLast(':') to hp.substringAfterLast(':')
               else hp to DEFAULT_PORT
    }

    fun itemsJson(records: List<EntityRecord>): JSONArray = JSONArray().apply {
        records.filter { !it.deleted && it.type != "feed" }.forEach { r ->
            put(JSONObject().put("id", r.id).put("type", r.type).put("fields", JSONObject().apply {
                r.fields.forEach { (k, v) -> if (v != null) put(k, v) }
            }))
        }
    }

    fun actionsOf(answer: JSONObject): List<HarnessAction> {
        val arr = answer.optJSONArray("hubidubitask_actions") ?: return emptyList()
        fun JSONObject.s(k: String) = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { a ->
            HarnessAction(a.s("op") ?: return@mapNotNull null, text = a.s("text"),
                project = a.s("project"), due = a.s("due"), id = a.s("id"))
        }
    }
}

/**
 * One change the Harness made with its item tools: "add_task" (text, project
 * id or none, due), "capture" (text) or "mark_done" (id).
 */
data class HarnessAction(val op: String, val text: String? = null, val project: String? = null,
                         val due: String? = null, val id: String? = null)

/** An app's items, as a Harness question carries them and its changes land. */
interface HarnessItems {
    fun records(): List<EntityRecord>
    fun apply(actions: List<HarnessAction>)
}
