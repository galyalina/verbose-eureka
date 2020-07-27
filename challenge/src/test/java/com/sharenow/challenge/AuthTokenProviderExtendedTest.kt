package com.sharenow.challenge

import com.google.common.truth.Truth
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Single
import io.reactivex.schedulers.TestScheduler
import org.junit.Before
import org.junit.Test
import org.threeten.bp.ZonedDateTime
import java.util.*

/**
 * Additional test for AuthTokenProvider
 */
class AuthTokenProviderExtendedTest {

    private lateinit var testStartTime: ZonedDateTime

    val currentTimeFunction = CurrentTimeFunction()

    private val isLoggedInObservable = BehaviorRelay.createDefault(true)

    inner class CurrentTimeFunction : () -> ZonedDateTime {

        lateinit var time: ZonedDateTime

        override fun invoke(): ZonedDateTime {
            return time
        }
    }

    private val testScheduler = TestScheduler()

    @Before
    fun setUp() {
        testStartTime = ZonedDateTime.now()
        currentTimeFunction.time = testStartTime
    }

    /**
     * Refresh should be called twice when fails and error should be propagated to first subscriber
     * Second subscriber will get correct response after one retry
     */
    @Test
    fun `Refresh error propagated properly`() {
        // Given
        val error = IllegalStateException("Should never be called")
        var count = 0
        val token = createAuthToken()

        // When
        val provider = AuthTokenProvider(
            computationScheduler = testScheduler,
            refreshAuthToken = Single.fromCallable {
                if (count < 3) {
                    count++
                    throw error
                } else {
                    token
                }
            },
            isLoggedInObservable = isLoggedInObservable,
            currentTime = currentTimeFunction
        )
        val testObserver = provider.observeToken().test()
        val testObserver2 = provider.observeToken().test()

        // Then
        testObserver.assertError(error)
        testObserver2.assertValue(token.token)
        testObserver2.assertNoErrors()
        testObserver2.assertNotComplete()
    }


    /**
     * Check exact number of retries on refreshAuthToken
     */
    @Test
    fun `Count number of retries in case that refresh token fails`() {
        // Given
        val error = IllegalStateException("Should never be called")
        var count = 0

        // When
        val provider = AuthTokenProvider(
            computationScheduler = testScheduler,
            refreshAuthToken = Single.fromCallable<AuthToken> { throw error }
                .doOnSubscribe { count++ },
            isLoggedInObservable = isLoggedInObservable,
            currentTime = currentTimeFunction
        )
        val testObserver = provider.observeToken().test()

        // Then
        testObserver.assertError(error)
        Truth.assertThat(count).isEqualTo(2)
    }


    private fun createAuthToken(): AuthToken {
        return AuthToken(
            token = UUID.randomUUID().toString(),
            validUntil = currentTimeFunction().plusMinutes(TOKEN_VALID_MINUTES)
        )
    }

    companion object {
        private const val TOKEN_VALID_MINUTES = 5L
    }
}

