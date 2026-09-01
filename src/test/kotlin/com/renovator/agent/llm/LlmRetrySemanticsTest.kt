package com.renovator.agent.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * KL-12 red/green proof (reviewer mandate): non-retryable provider errors fail
 * fast with exactly one attempt and a typed, named error; transient errors retry
 * with bounded backoff.
 */
class LlmRetrySemanticsTest {
    @Test
    fun `quota error fails fast with exactly one attempt`() {
        val call = LlmCall(maxTransientAttempts = 2, backoffMs = 1)
        val started = System.currentTimeMillis()
        val thrown =
            assertThrows(NonRetryableLlmException::class.java) {
                call.invoke(call = {
                    throw RuntimeException("429: no credits remaining. Please check your billing.")
                })
            }
        assertEquals(1, call.attempts.size, "a quota error must not be retried")
        assertTrue(call.attempts.single().failed)
        assertTrue(thrown.message!!.contains("failed fast"), "typed named error expected: ${thrown.message}")
        assertTrue(System.currentTimeMillis() - started < 1_000, "fail fast must be immediate")
    }

    @Test
    fun `auth error fails fast`() {
        val call = LlmCall(maxTransientAttempts = 2, backoffMs = 1)
        assertThrows(NonRetryableLlmException::class.java) {
            call.invoke(call = { throw RuntimeException("401: Incorrect API key provided") })
        }
        assertEquals(1, call.attempts.size)
    }

    @Test
    fun `rate-limit error retries with bounded backoff then succeeds`() {
        var attempts = 0
        val call = LlmCall(maxTransientAttempts = 2, backoffMs = 1)
        val result =
            call.invoke(call = {
                attempts += 1
                if (attempts == 1) {
                    throw RuntimeException("429: rate limit exceeded, retry after 1s")
                }
                "ok-$attempts"
            })
        assertEquals("ok-2", result)
        assertEquals(2, attempts, "a transient 429 must be retried")
        assertEquals(2, call.attempts.size)
        assertTrue(call.attempts.first().failed)
        assertTrue(
            call.attempts
                .last()
                .failed
                .not(),
        )
    }

    @Test
    fun `server error retries and the bound stops the storm`() {
        val call = LlmCall(maxTransientAttempts = 2, backoffMs = 1)
        assertThrows(NonRetryableLlmException::class.java) {
            call.invoke(call = { throw RuntimeException("500: Internal Server Error") })
        }
        assertEquals(3, call.attempts.size, "1 + maxTransientAttempts bounded attempts")
    }

    @Test
    fun `classifier maps statuses correctly`() {
        assertEquals(RetryClass.FAIL_FAST, LlmRetryClassifier.classify(RuntimeException("429: no credits remaining")))
        assertEquals(RetryClass.FAIL_FAST, LlmRetryClassifier.classify(RuntimeException("401: Unauthorized")))
        assertEquals(RetryClass.FAIL_FAST, LlmRetryClassifier.classify(RuntimeException("400: invalid request")))
        assertEquals(RetryClass.FAIL_FAST, LlmRetryClassifier.classify(RuntimeException("404: model not found")))
        assertEquals(RetryClass.RETRY_BOUNDED, LlmRetryClassifier.classify(RuntimeException("429: rate limit exceeded")))
        assertEquals(RetryClass.RETRY_BOUNDED, LlmRetryClassifier.classify(RuntimeException("503: Service Unavailable")))
        assertEquals(RetryClass.RETRY_BOUNDED, LlmRetryClassifier.classify(RuntimeException("connection timed out")))
    }
}
