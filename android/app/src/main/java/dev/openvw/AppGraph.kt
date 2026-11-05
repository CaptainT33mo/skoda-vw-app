package dev.openvw

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.openvw.ui.AppViewModel

/**
 * One [AppViewModel] per process, shared by the phone UI and the Android Auto screen, so both see the
 * same session, the same [dev.openvw.data.Store] and a single live SignalR connection (never two).
 * Android Auto can start the process with no Activity at all, so the ViewModel can't be Activity-scoped.
 */
object AppGraph : ViewModelStoreOwner {
  override val viewModelStore = ViewModelStore()

  fun vm(app: Application): AppViewModel =
    ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
}
