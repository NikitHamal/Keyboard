package np.com.nepalikeyboard.ime

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.activity.ViewTreeOnBackPressedDispatcherOwner

/**
 * Lifecycle plumbing that lets Jetpack Compose run inside an
 * `InputMethodService` window.
 *
 * An IME is not an `Activity`, so there is no `LifecycleOwner` in the view tree.
 * Compose's runtime, `collectAsStateWithLifecycle`, `LocalLifecycleOwner`,
 * `LocalSavedStateRegistryOwner`, `LocalViewModelStoreOwner` and any Material
 * component that reads them would throw or silently misbehave. This bridge
 * implements all four owner contracts and installs itself on the composition
 * host with the `setViewTree*` helpers, which is the supported way to host
 * Compose without an Activity.
 *
 * Lifecycle mapping
 * -----------------
 * * created + attached  -> `INITIALIZED`
 * * input view shown    -> `RESUMED` (so state collection is active)
 * * input view hidden   -> `CREATED`  (collection stops; zero wakeups while idle)
 * * service destroyed   -> `DESTROYED` (ViewModelStore cleared)
 *
 * The `SavedStateRegistry` is attached but never restored: an IME window has no
 * saved instance state of its own, and nothing in this app relies on
 * `rememberSaveable` surviving a keyboard hide.
 */
internal class ComposeOwnerBridge(
    private val hostView: View,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    /**
     * Present so that Compose's `BackHandler`, dialogs and predictive back all
     * find an owner. Nothing in the keyboard installs a back callback, so the
     * dispatcher stays empty and inert.
     */
    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /** Installs the owners on [hostView] and attaches the saved state registry. */
    fun attachToViewTree() {
        savedStateController.performAttach()
        hostView.setViewTreeLifecycleOwner(this)
        hostView.setViewTreeViewModelStoreOwner(this)
        hostView.setViewTreeSavedStateRegistryOwner(this)
        ViewTreeOnBackPressedDispatcherOwner.set(hostView, this)
    }

    /** Called from `onStartInputView`: the keyboard is on screen and interactive. */
    fun onInputViewShown() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Called from `onFinishInputView`: stop collecting while the keyboard is away. */
    fun onInputViewHidden() {
        val current = lifecycleRegistry.currentState
        if (current == Lifecycle.State.DESTROYED || current == Lifecycle.State.INITIALIZED) return
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Called from `onDestroy`. After this the bridge must not be reused. */
    fun destroy() {
        store.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
