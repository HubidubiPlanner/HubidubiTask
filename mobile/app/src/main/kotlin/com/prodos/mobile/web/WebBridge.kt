package com.prodos.mobile.web

import com.prodos.mobile.data.Json
import com.prodos.mobile.data.RoomLocalStore
import com.prodos.mobile.sync.Sync
import org.json.JSONArray
import org.json.JSONObject

/**
 * The native side of the view bridge, mirroring the desktop host (Phase 3):
 * every request from a plugin view is validated against that view's declared
 * permissions before any data is touched. Same permission model, same code
 * path where possible — the transport is WebMessagePort instead of the
 * desktop MessageChannel.
 *
 * A view requesting an entity type it did not declare is rejected, not
 * silently answered with empty data.
 */
class WebBridge(private val sync: Sync) {

    /** Handle one JSON request string, return one JSON response string. */
    fun handle(viewPermissions: ViewPermissions, requestJson: String): String {
        val req = JSONObject(requestJson)
        val id = req.optInt("id", 0)
        val op = req.optString("op")
        val resp = try {
            dispatch(viewPermissions, op, req)
        } catch (e: Exception) {
            JSONObject().put("error", "bad_request").put("detail", e.message)
        }
        return resp.put("id", id).toString()
    }

    private fun dispatch(perms: ViewPermissions, op: String, req: JSONObject): JSONObject {
        val store = sync.store
        return when (op) {
            "list" -> {
                val type = req.getString("type")
                if (!perms.canRead(type)) return denied("list", type)
                val arr = JSONArray()
                store.byType(type).forEach { arr.put(JSONObject(Json.recordToJson(it))) }
                JSONObject().put("entities", arr)
            }
            "get" -> {
                val rec = store.get(req.getString("id"))
                    ?: return JSONObject().put("entity", JSONObject.NULL)
                if (!perms.canRead(rec.type)) return denied("get", rec.type)
                JSONObject().put("entity", JSONObject(Json.recordToJson(rec)))
            }
            "upsert" -> {
                val type = req.getString("type")
                if (!perms.canWrite(type)) return denied("upsert", type)
                val data = req.getJSONObject("data")
                val fields = Json.fieldsFromJson(data.toString())
                val rec = com.prodos.core.EntityRecord(
                    id = data.optString("id").ifEmpty { mintId(type) },
                    type = type,
                    updated = com.prodos.core.nowUtc(),
                    fields = fields.filterKeys { it != "id" },
                )
                store.put(rec)
                sync.outbox.enqueueUpsert(rec)
                JSONObject().put("entity", JSONObject().put("id", rec.id).put("updated", rec.updated))
            }
            "delete" -> {
                val type = req.getString("type")
                if (!perms.canWrite(type)) return denied("delete", type)
                val rec = store.get(req.getString("id")) ?: return JSONObject().put("deleted", false)
                store.put(rec.tombstone())
                sync.outbox.enqueueDelete(rec)
                JSONObject().put("deleted", true)
            }
            else -> JSONObject().put("error", "unknown_op").put("op", op)
        }
    }

    private fun denied(op: String, type: String) =
        JSONObject().put("error", "permission_denied").put("op", op).put("type", type)

    private fun mintId(type: String): String {
        val prefix = when (type) {
            "task" -> "t"; "event" -> "e"; "note" -> "n"; "inbox_item" -> "i"
            else -> "x"
        }
        val a = "abcdefghijklmnopqrstuvwxyz234567"
        val r = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        return prefix + "_" + r.joinToString("") { a[it.toInt() and 31].toString() }
    }
}

data class ViewPermissions(val read: Set<String>, val write: Set<String>) {
    fun canRead(type: String) = type in read
    fun canWrite(type: String) = type in write

    companion object {
        fun fromManifestView(view: JSONObject): ViewPermissions {
            val p = view.getJSONObject("permissions")
            fun set(key: String) = p.optJSONArray(key)?.let { a ->
                (0 until a.length()).map { a.getString(it) }.toSet()
            } ?: emptySet()
            return ViewPermissions(set("read"), set("write"))
        }
    }
}
