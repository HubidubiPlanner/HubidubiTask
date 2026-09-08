package com.hubidubios.mobile.data

import com.hubidubios.core.EntityRecord
import org.json.JSONObject

/**
 * EntityRecord <-> JSON, using Android's built-in org.json (no extra
 * dependency, no codegen). The field map holds strings and nulls; the change
 * feed's richer JSON values are flattened to their string form, which is all
 * the mirror and the plugin views need.
 */
object Json {

    fun fieldsToJson(fields: Map<String, String?>): String {
        val o = JSONObject()
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        return o.toString()
    }

    fun fieldsFromJson(s: String): Map<String, String?> {
        val o = JSONObject(s)
        val out = LinkedHashMap<String, String?>()
        for (k in o.keys()) {
            out[k] = if (o.isNull(k)) null else o.getString(k)
        }
        return out
    }

    fun recordToJson(r: EntityRecord): String = JSONObject().apply {
        put("id", r.id)
        put("type", r.type)
        put("updated", r.updated)
        put("deleted", r.deleted)
        put("fields", JSONObject(fieldsToJson(r.fields)))
    }.toString()

    fun recordFromJson(s: String): EntityRecord {
        val o = JSONObject(s)
        return EntityRecord(
            id = o.getString("id"),
            type = o.getString("type"),
            updated = o.getString("updated"),
            fields = fieldsFromJson(o.getJSONObject("fields").toString()),
            deleted = o.optBoolean("deleted", false),
        )
    }

    /** Parse one entity object from the /v1/changes feed into a record. The
     *  feed nests the entity under "data"; id/type/updated are top-level. */
    fun recordFromFeed(data: JSONObject): EntityRecord {
        val fields = LinkedHashMap<String, String?>()
        for (k in data.keys()) {
            if (k == "id" || k == "type" || k == "updated") continue
            fields[k] = if (data.isNull(k)) null else data.get(k).toString()
        }
        return EntityRecord(
            id = data.getString("id"),
            type = data.getString("type"),
            updated = data.getString("updated"),
            fields = fields,
        )
    }
}
