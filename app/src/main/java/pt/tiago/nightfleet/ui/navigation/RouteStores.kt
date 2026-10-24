package pt.tiago.nightfleet.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * One [ViewModelStore] per entry of the back stack. A screen's view models live as long as its entry: they are
 * cleared when the entry leaves the stack, so a new screen never receives the view model of an old one that used
 * the same key (a finished battle of another mission or difficulty, for example).
 */
class RouteStores {
    private val stores = LinkedHashMap<String, ViewModelStore>()

    /** The store of the entry [key], created on first use. */
    fun storeFor(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    /** Clears and forgets the stores of the entries that are no longer on the stack. */
    fun retainOnly(keys: Set<String>) {
        val gone = stores.keys.filter { it !in keys }
        gone.forEach { stores.remove(it)?.clear() }
    }

    fun clearAll() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }

    /** Keys of the entries that hold a store; for tests. */
    val keys: Set<String> get() = stores.keys.toSet()
}

/** Keeps the [RouteStores] across rotation and clears every store when the activity really finishes. */
class RouteStoresViewModel : ViewModel() {
    val stores = RouteStores()

    override fun onCleared() = stores.clearAll()
}

/** Gives [content] the view model store of the current entry of [stack]. */
@Composable
fun ProvideRouteViewModels(stack: NavStack, content: @Composable () -> Unit) {
    val holder: RouteStoresViewModel = viewModel()
    val keys = stack.entryKeys()
    val currentKey = keys.last()
    val owner = remember(currentKey) {
        val store = holder.stores.storeFor(currentKey)
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }
    // After the frame: the screen that left the stack is no longer composed when its view models are cleared.
    SideEffect { holder.stores.retainOnly(keys.toSet()) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}
