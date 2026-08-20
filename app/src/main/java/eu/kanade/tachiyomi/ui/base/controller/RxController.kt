package eu.kanade.tachiyomi.ui.base.controller

import android.os.Bundle
import android.view.View
import androidx.annotation.CallSuper
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import rx.Observable
import rx.Subscription
import rx.subscriptions.CompositeSubscription

abstract class RxController<VB : ViewBinding>(bundle: Bundle? = null) : BaseController<VB>(bundle) {
    private var untilDestroySubscriptions = CompositeSubscription()
    private var untilDetachSubscriptions = CompositeSubscription()

    private var untilDestroyScope: CoroutineScope = MainScope()
    private var untilDetachScope: CoroutineScope = MainScope()

    @CallSuper
    override fun onViewCreated(view: View) {
        if (untilDestroySubscriptions.isUnsubscribed) {
            untilDestroySubscriptions = CompositeSubscription()
        }
        if (!untilDestroyScope.isActive) {
            untilDestroyScope = MainScope()
        }
    }

    @CallSuper
    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        untilDestroySubscriptions.unsubscribe()
        untilDestroyScope.cancel()
    }

    @CallSuper
    override fun onAttach(view: View) {
        super.onAttach(view)
        if (untilDetachSubscriptions.isUnsubscribed) {
            untilDetachSubscriptions = CompositeSubscription()
        }
        if (!untilDetachScope.isActive) {
            untilDetachScope = MainScope()
        }
    }

    @CallSuper
    override fun onDetach(view: View) {
        super.onDetach(view)
        untilDetachSubscriptions.unsubscribe()
        untilDetachScope.cancel()
    }

    /** Coroutine counterpart of [subscribeUntilDestroy]. */
    fun <T> Flow<T>.collectUntilDestroy(onNext: (T) -> Unit): Job =
        onEach(onNext).launchIn(untilDestroyScope)

    /** Coroutine counterpart of [subscribeUntilDetach]. */
    fun <T> Flow<T>.collectUntilDetach(onNext: (T) -> Unit): Job =
        onEach(onNext).launchIn(untilDetachScope)

    fun <T> Observable<T>.subscribeUntilDestroy(onNext: (T) -> Unit): Subscription {
        return subscribe(onNext).also { untilDestroySubscriptions.add(it) }
    }

    fun <T> Observable<T>.subscribeUntilDetach(onNext: (T) -> Unit): Subscription {
        return subscribe(onNext).also { untilDetachSubscriptions.add(it) }
    }
}
