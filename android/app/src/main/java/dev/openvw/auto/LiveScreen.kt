package dev.openvw.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A car screen that re-renders itself when its data changes. While the screen is visible it checks [key]
 * every [periodMs] and calls [invalidate] only if the value differs from what was last drawn, so the host
 * gets a refresh per real change (at most one per period) and nothing while values sit still.
 *
 * Polling the in-memory Store is deliberate: Compose snapshot change notifications aren't pumped when the
 * phone UI was never opened (Android Auto can start the process on its own), so observers could miss writes.
 */
internal abstract class LiveScreen(ctx: CarContext, private val periodMs: Long = 1_000L) : Screen(ctx) {
  private var drawn: Any? = null

  /** Everything the template depends on; equality decides whether to redraw. */
  protected abstract fun key(): Any?

  protected abstract fun render(): Template

  init {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
          delay(periodMs)
          if (key() != drawn) invalidate()
        }
      }
    }
  }

  final override fun onGetTemplate(): Template {
    drawn = key()
    return render()
  }
}

/** How many items the host allows right now (fewer while driving on some cars), capped at [want]. */
internal fun CarContext.limit(type: Int, want: Int): Int =
  if (carAppApiLevel >= CarAppApiLevels.LEVEL_2)
    runCatching { getCarService(ConstraintManager::class.java).getContentLimit(type) }.getOrDefault(want).coerceIn(1, want)
  else want

internal fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
