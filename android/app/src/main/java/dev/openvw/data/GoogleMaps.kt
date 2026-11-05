package dev.openvw.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** Hand-offs to Google Maps (app if installed, browser otherwise). */
object GoogleMaps {
  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  /** Drop a pin on a coordinate (e.g. where the car is parked). */
  fun pin(ctx: Context, lat: Double, lon: Double) =
    open(ctx, "https://www.google.com/maps/search/?api=1&query=$lat,$lon")

  /** Show a place by name/address. */
  fun place(ctx: Context, query: String) =
    open(ctx, "https://www.google.com/maps/search/?api=1&query=${enc(query)}")

  /** Route between two coordinates (your trip's start → end). */
  fun route(ctx: Context, oLat: Double, oLon: Double, dLat: Double, dLon: Double) =
    open(ctx, "https://www.google.com/maps/dir/?api=1&origin=$oLat,$oLon&destination=$dLat,$dLon&travelmode=driving")

  /** Turn-by-turn driving directions to a place. */
  fun directions(ctx: Context, destination: String) =
    open(ctx, "https://www.google.com/maps/dir/?api=1&destination=${enc(destination)}&travelmode=driving")

  /**
   * Search Google Maps for [query] around a point (the parked car); with no point, around wherever you are.
   * The Maps app gets a geo: search centred on the point; a browser gets "query near lat,lon".
   */
  fun searchNear(ctx: Context, query: String, lat: Double?, lon: Double?) {
    val at = if (lat != null && lon != null) "$lat,$lon" else "0,0"
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$at?q=${enc(query)}"))
      .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(app) } catch (_: ActivityNotFoundException) {
      val q = if (lat != null && lon != null) "$query near $lat,$lon" else query
      runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${enc(q)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
  }

  /** Open the dialer with [phone] filled in (nothing is called without the user pressing call). */
  fun dial(ctx: Context, phone: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  }

  private fun open(ctx: Context, url: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      ctx.startActivity(Intent(i).setPackage("com.google.android.apps.maps"))
    } catch (_: ActivityNotFoundException) {
      runCatching { ctx.startActivity(i) }
    }
  }
}
