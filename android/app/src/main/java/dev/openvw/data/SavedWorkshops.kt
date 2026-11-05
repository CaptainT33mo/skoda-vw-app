package dev.openvw.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The owner's saved workshops (Locate tab hearts), kept in app-private storage on this phone. */
object SavedWorkshops {
  private fun prefs(ctx: Context) = ctx.getSharedPreferences("openvw", Context.MODE_PRIVATE)

  fun load(ctx: Context): List<Workshop> = runCatching {
    val a = JSONArray(prefs(ctx).getString("savedWorkshops", "[]"))
    (0 until a.length()).map { a.getJSONObject(it) }.map { Workshop(it.optString("id"), it.optString("name"), it.optString("address"), it.optString("phone")) }
  }.getOrDefault(emptyList())

  private fun save(ctx: Context, list: List<Workshop>) {
    val a = JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("address", it.address).put("phone", it.phone) })
    prefs(ctx).edit().putString("savedWorkshops", a.toString()).apply()
    Store.savedWorkshops = list
  }

  /** Add, or replace the one with the same id. */
  fun put(ctx: Context, w: Workshop) = save(ctx, Store.savedWorkshops.filter { it.id != w.id } + w)

  fun remove(ctx: Context, id: String) = save(ctx, Store.savedWorkshops.filter { it.id != id })
}
