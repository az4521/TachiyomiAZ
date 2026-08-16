package eu.kanade.tachiyomi.ui.base.presenter

import android.os.Bundle
import eu.kanade.tachiyomi.util.lang.awaitSingle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
     * Nucleus exposes the view as a BehaviorSubject that emits the view on attach and null on
     * detach. Exposed as a Flow so delivery can react to re-attachment.
     */
    private fun viewFlow(): Flow<V?> =
        callbackFlow {
            val subscription = view().subscribe { trySend(it) }
            awaitClose { subscription.unsubscribe() }
        }

    /**
     * Coroutine equivalent of [subscribeLatestCache].
     *
     * Combining with [viewFlow] is what makes this match `deliverLatestCache`: a re-attaching
     * view re-emits, so the newest value is delivered again to the new view. Collecting the
     * source alone would only deliver on new emissions, leaving a screen blank when it is
     * returned to and nothing has changed since.
     *
     * The upstream is deliberately not restarted on re-attach -- some sources do real work
     * (GlobalSearchPresenter searches every source), and restarting would repeat it.
     */
    fun <T> Flow<T>.collectLatestCache(
        onNext: (V, T) -> Unit,
        onError: ((V, Throwable) -> Unit)? = null
    ): Job =
        presenterScope.launch {
            val values = catch { e -> onError?.invoke(awaitAttachedView(), e) }
            combine(viewFlow(), values) { view, value -> view to value }
                .collectLatest { (view, value) -> if (view != null) onNext(view, value) }
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
            // Restarted per attached view, so a replaying source (Pager keeps every page in a
            // replay buffer) hands the whole sequence to a view that was re-created, which is
            // what deliverReplay did. Safe here precisely because the source is a buffer
            // rather than work.
            viewFlow().collectLatest { view ->
                if (view == null) return@collectLatest
                this@collectReplay
                    .catch { e -> onError?.invoke(view, e) }
                    .collect { value -> onNext(view, value) }
            }
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
