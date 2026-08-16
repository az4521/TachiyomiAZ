package eu.kanade.tachiyomi.ui.base.presenter

import android.os.Bundle
import eu.kanade.tachiyomi.util.lang.awaitSingle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import nucleus.presenter.RxPresenter

/**
 * Nucleus stays as a boundary library, the same way ReactiveNetwork and the extension `Source`
 * API do: it provides presenter retention, bundle save/restore and view attach/detach, and its
 * RxJava surface is confined to [awaitAttachedView] below. Replacing it would be a lifecycle
 * refactor across every controller, not an RxJava one.
 */
open class BasePresenter<V> : RxPresenter<V>() {
    /**
     * Scope for coroutines started by this presenter. Cancelled in [onDestroy], so work started
     * here does not outlive the presenter the way the process-wide [launchIO]/[launchUI] helpers
     * do. Initialized eagerly rather than in [onCreate] because [super.onCreate] can throw, and a
     * `lateinit` assigned after that call would be left uninitialized when it does.
     */
    val presenterScope: CoroutineScope = MainScope()

    override fun onCreate(savedState: Bundle?) {
        try {
            super.onCreate(savedState)
        } catch (e: NullPointerException) {
            // Swallow this error. This should be fixed in the library but since it's not critical
            // (only used by restartables) it should be enough. It saves me a fork.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        presenterScope.cancel()
    }

    /**
     * Suspends until a view is attached, then returns it.
     *
     * Nucleus exposes the view as a [rx.subjects.BehaviorSubject] that emits null while detached,
     * which is what the `deliver*` transformers key off. This is the coroutine equivalent of
     * waiting for that emission.
     */
    private suspend fun awaitAttachedView(): V = view ?: view().filter { it != null }.take(1).awaitSingle()

    /**
     * Coroutine equivalent of [subscribeLatestCache]: collects this flow and hands each value to
     * the attached view, holding the newest value while no view is attached and dropping any it
     * supersedes. Cancelled with [presenterScope].
     */
    fun <T> Flow<T>.collectLatestCache(
        onNext: (V, T) -> Unit,
        onError: ((V, Throwable) -> Unit)? = null
    ): Job =
        presenterScope.launch {
            catch { e -> onError?.invoke(awaitAttachedView(), e) }
                .collectLatest { value -> onNext(awaitAttachedView(), value) }
        }

    /**
     * Coroutine equivalent of [subscribeReplay]: delivers every value in order, waiting for a view
     * rather than dropping superseded values the way [collectLatestCache] does. Pair it with a
     * replaying source (e.g. a `MutableSharedFlow(replay = Int.MAX_VALUE)`) when the view needs
     * the whole sequence back after a detach, such as an incrementally paged list.
     */
    fun <T> Flow<T>.collectReplay(
        onNext: (V, T) -> Unit,
        onError: ((V, Throwable) -> Unit)? = null
    ): Job =
        presenterScope.launch {
            catch { e -> onError?.invoke(awaitAttachedView(), e) }
                .collect { value -> onNext(awaitAttachedView(), value) }
        }

    /**
     * Coroutine equivalent of a one-shot [subscribeFirst]: runs [block] against the view once one
     * is attached.
     */
    fun deliverToView(block: (V) -> Unit): Job =
        presenterScope.launch {
            block(awaitAttachedView())
        }

}
