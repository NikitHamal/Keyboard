package com.nikit.nepalikeyboard.ime

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandleSupport
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * =============================================================================
 * WHY THIS FILE EXISTS
 * =============================================================================
 *
 * Compose and the AndroidX ViewModel stack were designed for `ComponentActivity`,
 * which conveniently implements `LifecycleOwner`, `ViewModelStoreOwner` and
 * `SavedStateRegistryOwner` on itself and grafts them onto its window's view
 * tree via `setViewTreeXxxOwner`.
 *
 * An `InputMethodService` inherits from `Service`, not `Activity`. It owns a
 * normal `Window` whose decor view is inserted into the system's input-method
 * container, but it implements none of those three interfaces. If you naively
 * call `setContent { }` inside an IME you get one of:
 *
 *   * `IllegalStateException: Composed into a view that does not have a
 *     ViewTreeLifecycleOwner` — thrown by `AbstractComposeView` during the very
 *     first composition.
 *   * A silent leak: `rememberSaveable` never restores, `ViewModel`s are
 *     recreated on every layout pass, `LaunchedEffect` never cancels.
 *   * `BaseInputConnection` / recomposer crashes when the service is destroyed
 *     while a composition is still active.
 *
 * This class is the fix: a small, hand-rolled triple-owner that we install on
 * the keyboard's Compose host. It is deliberately modelled on what
 * `ComponentActivity` does internally so that the behaviour users and
 * developers expect from `Lifecycle`, `SavedStateHandle` and `viewModel()`
 * inside a Composable holds identically here.
 *
 * =============================================================================
 * LIFECYCLE MAPPING
 * =============================================================================
 *
 * The IME host is shown and hidden far more often than an activity is resumed
 * and paused — every single focus change between two text fields. Mapping the
 * keyboard's visibility 1:1 onto `RESUMED`/`CREATED` would cancel and recreate
 * every `LaunchedEffect` on each focus change, which for us means repeatedly
 * rebuilding the suggestion pipeline and restarting the lexicon query. So the
 * mapping is:
 *
 *   service created            -> ON_CREATE   -> CREATED
 *   keyboard becomes visible   -> ON_START + ON_RESUME -> RESUMED
 *   keyboard hidden            -> ON_PAUSE  -> STARTED
 *   keyboard destroyed         -> ON_STOP + ON_DESTROY -> DESTROYED
 *
 * Crucially, hiding the keyboard does *not* destroy the owner. The ViewModel
 * store survives, which is what lets the lexicon index, the clipboard history
 * and the learned-word cache stay resident across focus changes. Only when the
 * system actually tears the IME down (`onDestroy`) do we destroy the registry,
 * and at that point we also clear the `ViewModelStore`, because holding a
 * `ViewModelStore` past `onDestroy` is precisely the leak that
 * `ViewModelStoreOwner` exists to prevent.
 *
 * =============================================================================
 */
class KeyboardLifecycleOwner(
    val application: Application
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    /**
     * The single source of truth for lifecycle state. `LifecycleRegistry` is
     * not thread-safe; every method here must be called on the main thread,
     * which is guaranteed because the IME service drives them from its own
     * main-thread callbacks.
     */
    private val lifecycleRegistry = LifecycleRegistry(this)

    /**
     * Controller that lets us drive the saved-state registry manually. In a
     * `ComponentActivity` this is invoked by `onSaveInstanceState`; for us
     * there is no instance-state bundle callback on `InputMethodService`, so
     * the service explicitly forwards whatever it has. The registry is still
     * worth having: `rememberSaveable` inside the keyboard (emoji tab
     * selection, clipboard tab scroll position, one-handed side) works as long
     * as a registry is present, and it survives configuration changes that the
     * service does receive.
     */
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    /**
     * Storage for `viewModel()` callers inside the keyboard composition.
     *
     * `clear()` is called exactly once, from `destroy()`. Everything else is
     * handled by the owner itself.
     */
    override val viewModelStore: ViewModelStore = ViewModelStore()

    /**
     * Default creation extras.
     *
     * `APPLICATION_KEY` is what lets `AndroidViewModel` subclasses resolve
     * their `Application` constructor argument. The
     * `SavedStateHandle`-related keys are installed later, lazily, by
     * [KeyboardLifecycleOwner.factory] via `SavedStateHandleSupport`, exactly as
     * `ComponentActivity` does — `SavedStateHandleSupport` needs the registry
     * to have already been restored, so it cannot be wired at construction
     * time.
     */
    private val defaultCreationExtras: CreationExtras = run {
        val extras = MutableCreationExtras()
        extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = application
        extras
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    /**
     * Extras exposed to `ViewModelProvider`. `ComponentActivity` overrides
     * `getDefaultViewModelCreationExtras()`; we expose the same bag under the
     * same name so that code written against an activity ports over unchanged.
     */
    val defaultViewModelCreationExtras: CreationExtras get() = defaultCreationExtras

    /** True once [destroy] has run; guards against double-destroy. */
    private var destroyed = false

    /** True while the keyboard window is actually on screen. */
    var isKeyboardVisible: Boolean = false
        private set

    // =========================================================================
    // Lifecycle driving
    // =========================================================================

    /**
     * Called from `NepaliImeService.onCreate()`, before the input view is ever
     * inflated. Registers the saved-state registry so that `rememberSaveable`
     * has somewhere to write the first time it composes.
     */
    fun create() {
        if (destroyed) return
        savedStateRegistryController.performRestore(null)
        moveTo(Lifecycle.State.CREATED)
    }

    /**
     * Called from `onStartInputView` — i.e. the keyboard is about to be drawn.
     *
     * Idempotent: focus moving from one field to another fires this repeatedly,
     * and re-entering `RESUMED` while already `RESUMED` is a no-op inside
     * `LifecycleRegistry`, so `LaunchedEffect`s are not restarted.
     */
    fun startAndResume() {
        if (destroyed) return
        isKeyboardVisible = true
        moveTo(Lifecycle.State.RESUMED)
    }

    /**
     * Called from `onFinishInputView` / `onWindowHidden`.
     *
     * Drops to `STARTED`, not `CREATED`. Effects that must stop when the
     * keyboard is off screen — an active backspace-repeat loop, a blinking
     * caret, an in-flight animation — observe `Lifecycle.State.STARTED` as
     * their floor via `repeatOnLifecycle`. State that should persist across
     * focus changes (ViewModels, the ViewModelStore) is untouched.
     */
    fun pause() {
        if (destroyed) return
        isKeyboardVisible = false
        moveTo(Lifecycle.State.STARTED)
    }

    /**
     * Called from `NepaliImeService.onDestroy()`.
     *
     * Order matters and is the same order `ComponentActivity` uses:
     *   1. Emit `ON_STOP` so anything holding a resumed resource releases it.
     *   2. Emit `ON_DESTROY`, which cancels `DisposableEffect`s and
     *      `LaunchedEffect`s still in flight.
     *   3. Clear the ViewModelStore, invoking `ViewModel.onCleared()`. Skipping
     *      this step is the classic IME Compose leak: the store retains a
     *      reference to the destroyed service through captured lambdas.
     *
     * After this the owner is inert; every public method becomes a no-op so a
     * late callback from the framework cannot resurrect a destroyed registry
     * (`LifecycleRegistry` throws if you try to move a destroyed lifecycle).
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        isKeyboardVisible = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    /**
     * Moves the registry to [target], always stepping through every
     * intermediate state.
     *
     * `LifecycleRegistry.currentState = x` already walks the path, but going
     * through `handleLifecycleEvent` with an explicit event keeps the emitted
     * event sequence identical to what an Activity produces, which matters for
     * libraries that key off specific events (e.g. `flowWithLifecycle`).
     */
    private fun moveTo(target: Lifecycle.State) {
        val current = lifecycleRegistry.currentState
        if (current == target) return
        if (!current.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        when {
            target == Lifecycle.State.RESUMED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            target == Lifecycle.State.STARTED -> {
                if (current.isAtLeast(Lifecycle.State.RESUMED)) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                }
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
            target == Lifecycle.State.CREATED -> {
                if (current.isAtLeast(Lifecycle.State.RESUMED)) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                }
                if (current.isAtLeast(Lifecycle.State.STARTED)) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                }
            }
        }
    }

    companion object {
        /**
         * Builds the `ViewModelProvider.Factory` for this owner.
         *
         * `SavedStateHandleSupport.enableSavedStateHandles` must be called
         * before any ViewModel that injects a `SavedStateHandle` is created: it
         * installs the `SavedStateProvider` that snapshots the handle when the
         * registry saves. `ComponentActivity` does this in
         * `onCreate` via `initializeViewTreeOwners`; we do it lazily on first
         * factory access, which is equivalent because our registry has already
         * been restored by then.
         *
         * The returned factory is the standard Android one, so ViewModels with
         * plain `Application` constructors and ViewModels with
         * `(Application, SavedStateHandle)` constructors both resolve.
         */
        fun factory(owner: KeyboardLifecycleOwner): ViewModelProvider.Factory {
            SavedStateHandleSupport.enableSavedStateHandles(owner, owner.defaultViewModelCreationExtras)
            return ViewModelProvider.AndroidViewModelFactory.getInstance(owner.application)
        }
    }
}

/**
 * Installs [owner] as the `ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner`
 * and `ViewTreeSavedStateRegistryOwner` for the compose host it is called from,
 * and removes all three when the calling composable leaves the composition.
 *
 * Call this as the **outermost** thing inside the keyboard's `setContent` block.
 * Concretely:
 *
 * ```
 * setContent {
 *     InstallKeyboardViewTreeOwners(owner) {
 *         NepaliKeyboardTheme { KeyboardRoot(...) }
 *     }
 * }
 * ```
 *
 * Doing it inside a `DisposableEffect` (rather than in the service's
 * `onCreateInputView`) means the tags are written on the view the compose
 * runtime actually attached to — `LocalView.current` — so we can never
 * accidentally tag the wrong decor. It also guarantees teardown, so a service
 * that is destroyed and recreated does not leave stale owner tags on a
 * recycled view.
 *
 * The effect key is the owner itself, so swapping owners mid-flight (which
 * happens if the service is torn down and rebuilt without the process dying)
 * re-installs the tags rather than silently keeping the old ones.
 */
@Composable
fun InstallKeyboardViewTreeOwners(
    owner: KeyboardLifecycleOwner,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    remember(owner, view) {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        owner
    }
    DisposableEffect(owner, view) {
        onDispose {
            // Clearing on dispose is what prevents the stale-owner bug. A view
            // whose tags outlive their owner causes a hard crash the next time
            // anything resolves `ViewTreeLifecycleOwner.get()` against it,
            // because the retrieved registry is already DESTROYED.
            view.setViewTreeLifecycleOwner(null)
            view.setViewTreeViewModelStoreOwner(null)
            view.setViewTreeSavedStateRegistryOwner(null)
        }
    }
    content()
}

/**
 * Creates (and remembers) a [KeyboardLifecycleOwner] bound to the application,
 * already driven to `CREATED`.
 *
 * This is the convenience path for callers that do not need to control the
 * owner's lifecycle from outside the composition — in practice, the sandbox
 * activity and any preview. The IME service deliberately does *not* use this:
 * it constructs the owner itself in `onCreate` so that the owner outlives any
 * individual composition, which is what keeps the lexicon ViewModel resident
 * across the keyboard being hidden and shown again.
 */
@Composable
fun rememberKeyboardLifecycleOwner(application: Application): KeyboardLifecycleOwner =
    remember(application) {
        KeyboardLifecycleOwner(application).apply { create() }
    }

/**
 * `true` when a lifecycle owner has already been installed on this view tree.
 * Used by the sandbox activity to avoid double-installing owners when it is
 * already running inside a real activity that provides them.
 */
@Composable
fun hasKeyboardViewTreeOwners(): Boolean {
    val view = LocalView.current
    return remember(view) {
        view.findViewTreeLifecycleOwner() != null
    }
}

/**
 * Convenience factory for ViewModels that need nothing but an `Application`.
 *
 * The keyboard's ViewModels are all `AndroidViewModel` subclasses with an
 * `Application` parameter, so this covers every real call site. Keeping it here
 * rather than reaching for `AndroidViewModelFactory.getInstance(app)` means the
 * creation extras come from the [KeyboardLifecycleOwner] — which is what makes
 * `SavedStateHandle` work if a future ViewModel asks for one.
 */
fun keyboardViewModelFactory(owner: KeyboardLifecycleOwner): ViewModelProvider.Factory =
    ViewModelProvider.AndroidViewModelFactory.getInstance(owner.application)
