package com.sharenow.challenge

import io.reactivex.*
import io.reactivex.disposables.Disposables
import io.reactivex.subjects.BehaviorSubject
import org.threeten.bp.Duration
import org.threeten.bp.ZonedDateTime
import java.util.concurrent.TimeUnit


class AuthTokenProvider(

    /**
     * Scheduler used for background operations. Execute time relevant operations on this one,
     * so we can use [TestScheduler] within unit tests.
     */
    private val computationScheduler: Scheduler,

    /**
     * Single to be observed in order to get a new token.
     */
    private val refreshAuthToken: Single<AuthToken>,

    /**
     * Observable for the login state of the user. Will emit true, if he is logged in.
     */
    private val isLoggedInObservable: Observable<Boolean>,

    /**
     * Function that returns you the current time, whenever you need it. Please use this whenever you check the
     * current time, so we can manipulate time in unit tests.
     */
    private val currentTime: () -> ZonedDateTime
) {

    private var refreshCache: BehaviorSubject<Boolean> = BehaviorSubject.createDefault(false)
    private var refreshCacheDisposable = Disposables.disposed()
    private var cachedToken: AuthToken? = null
    private val tokenObservable: Observable<AuthToken> =
        fetchToken().share()

    /**
     * @return the observable auth token as a string
     */
    fun observeToken(): Observable<String> {
        return tokenObservable
            .map {
                it.token
            }
    }

    private fun fetchToken(): Observable<AuthToken> {
        val requestTokenForLoggedUser: Maybe<AuthToken> =
            refreshAuthToken
                .retry(2)
                .filter {
                    it.isValid(currentTime)
                }
                .switchIfEmpty(Single.defer {
                    refreshAuthToken.retry(2)
                })
                .filter {
                    it.isValid(currentTime)
                }
                .doOnSuccess {
                    setUpCache(it)
                    cachedToken = it
                }

        return refreshCache
            .hide()
            .flatMap {
                if (cachedToken != null && cachedToken?.isValid(currentTime) == true) {
                    Observable.just(cachedToken)
                } else {
                    isLoggedInObservable
                        .filter { it }
                        .switchIfEmpty(Observable.defer {
                            cachedToken = null
                            Observable.empty<Boolean>()
                        })
                        .flatMapMaybe { requestTokenForLoggedUser }
                }
            }
    }

    private fun setUpCache(token: AuthToken) {
        if (refreshCacheDisposable.isDisposed) {
            refreshCacheDisposable =
                Completable.timer(
                    Duration.between(currentTime(), token.validUntil).toMillis(),
                    TimeUnit.MILLISECONDS,
                    computationScheduler
                ).subscribe {
                    cachedToken = null
                    refreshCache.onNext(true)
                }
        }
    }
}
