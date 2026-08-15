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
import nucleus.presenter.delivery.Delivery
import rx.Observable

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

    /**
     * Subscribes an observable with [deliverFirst] and adds it to the presenter's lifecycle
     * subscription list.
     *
     * @param onNext function to execute when the observable emits an item.
     * @param onError function to execute when the observable throws an error.
     */
    fun <T> Observable<T>.subscribeFirst(
        onNext: (V, T) -> Unit,
        onError: ((V, Throwable) -> Unit)? = null
    ) = compose(deliverFirst<T>()).subscribe(split(onNext, onError)).apply { add(this) }

    /**
     * Subscribes an observable with [deliverLatestCache] and adds it to the presenter's lifecycle
     * subscription list.
     *
     * @param onNext function to execute when the observable emits an item.
     * @param onError function to execute when the observable throws an error.
     */
    fun <T> Observable<T>.subscribeLatestCache(
        onNext: (V, T) -> Unit,
        onError: ((V, Throwable) -> Unit)? = null
    ) = compose(deliverLatestCache<T>()).subscribe(split(onNext, onError)).apply { add(this) }

    /**
     * Subscribes an observable with [deliverReplay] and adds it to the presenter's lifecycle
     * subscription list.
     *
     * @param onNext function to execute when the observable emits an item.
     * @param onError function to execute when the observable throws an error.
     */
    fun <T> Observable<T>.subscribeReplay(
        onNext: (V, T) -> Unit,
        onError: ((V, Throwable) -> Unit)? = null
    ) = compose(deliverReplay<T>()).subscribe(split(onNext, onError)).apply { add(this) }

    /**
     * Subscribes an observable with [DeliverWithView] and adds it to the presenter's lifecycle
     * subscription list.
     *
     * @param onNext function to execute when the observable emits an item.
     * @param onError function to execute when the observable throws an error.
     */
    fun <T> Observable<T>.subscribeWithView(
        onNext: (V, T) -> Unit,
        onError: ((V, Throwable) -> Unit)? = null
    ) = compose(DeliverWithView<V, T>(view())).subscribe(split(onNext, onError)).apply { add(this) }

    /**
     * A deliverable that only emits to the view if attached, otherwise the event is ignored.
     */
    class DeliverWithView<View, T>(private val view: Observable<View>) : Observable.Transformer<T, Delivery<View, T>> {
        override fun call(observable: Observable<T>): Observable<Delivery<View, T>> {
            return observable
                .materialize()
                .filter { notification -> !notification.isOnCompleted }
                .flatMap { notification ->
                    view.take(1).filter { it != null }.map { Delivery(it, notification) }
                }
        }
    }
}
