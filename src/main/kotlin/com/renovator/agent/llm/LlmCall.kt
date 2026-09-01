package com.renovator.agent.llm

// LEARN[017] LLM retry taxonomy: which HTTP errors are retryable and why
// Why this way: an LLM call whose error cannot succeed on retry must fail FAST —
//   the Phase-0 smoke watched a quota-exhausted 429 burn ~10 attempts over ~24 s
//   and then fail (KL-12, from the reviewer). The rule that makes this safe is a
//   classification, not a blanket "retry on error": AUTH/QUOTA errors are the
//   environment or the budget — retrying is expensive noise and can run the meter
//   dry (429-quota is the meter saying NO; 401 means the key is wrong; 400 means
//   the request/prompt is malformed). THE ONLY retryable classes for an OpenAI-
//   compatible API are: 429-rate ("slow down") and 5xx/transport (the server is
//   temporarily unable), which are exactly what bounded exponential backoff is FOR.
// Good sides: fail-fast is deterministic (the planner replans instead of waiting);
//   the classification is a pure function (testable per class); "unknown error =>
//   fatal" default keeps the allow-list honest — you must name a retryable class.
// Drawbacks: classification by message text is a heuristic (providers vary); the
//   framework's own retrier is outside our control — so we pin the framework's
//   data-binding max-attempts to 1 and let OUR loop own bounded retry (documented
//   in application.yml); a genuinely transient 5xx storm still costs the bounded
//   budget (that is what "bounded" means).
// Concept: think circuit-breaker-at-call-site: costs are binary (fail fast vs
//   retry-bounded) and the taxonomy is a table, not a gradient. OpenAI's own
//   guidance is the source of truth: 429/5xx retryable; 4xx (except 429) are
//   permanent and should not be retried without changing the request.
// See also: KNOWN_LIMITATIONS.md KL-12, PLAN §2 C-8, LLM .st prompts
class NonRetryableLlmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

enum class RetryClass {
    FAIL_FAST,
    RETRY_BOUNDED,
}

/**
 * Pure classification: which throwables from an OpenAI-compatible client deserve
 * a bounded retry (LEARN[017]). Default refuse-to-retry unless the class is in
 * the known-transient set.
 */
object LlmRetryClassifier {
    private val QUOTA_MARKERS = listOf("no credits", "quota", "billing", "insufficient", "payment required", "credit")
    private val RATE_MARKERS = listOf("rate limit", "too many requests", "slow down", "try again")

    fun classify(error: Throwable): RetryClass {
        val message = (error.message ?: "").lowercase()
        val type = error.javaClass.simpleName.lowercase()

        // Auth / permission / malformed request / not found: permanent.
        if (type.contains("unauthorized") || type.contains("auth") || message.contains("401") || message.contains("403")) {
            return RetryClass.FAIL_FAST
        }
        if (message.contains("400") || type.contains("badrequest") || message.contains("invalid request") ||
            message.contains("model not found") ||
            message.contains("404")
        ) {
            return RetryClass.FAIL_FAST
        }
        // 429: quota vs rate — this is exactly the KL-12 distinction.
        if (type.contains("ratelimit") || type.contains("rate_limit") || message.contains("429")) {
            return if (QUOTA_MARKERS.any { it in message }) {
                RetryClass.FAIL_FAST
            } else {
                RetryClass.RETRY_BOUNDED
            }
        }
        // Transport / server: transient.
        if (message.contains("timeout") || message.contains("connect") || message.contains("temporarily") ||
            message.contains("502") || message.contains("503") || message.contains("500") ||
            type.contains("server") || type.contains("connection") || type.contains("timeout")
        ) {
            return RetryClass.RETRY_BOUNDED
        }
        return RetryClass.FAIL_FAST
    }
}

data class LlmAttempt(
    val index: Int,
    val failed: Boolean,
    val error: String?,
    val durationMs: Long,
)

/**
 * The single LLM-call path for the agent palette (KL-12 implementation, Phase 3):
 *  - the FRAMEWORK is pinned to one binding attempt (application.yml:
 *    llm-operations.data-binding.max-attempts=1) so the retry budget is OURS;
 *  - [LlmRetryClassifier] decides fail-fast vs bounded backoff;
 *  - every call records typed [LlmAttempt]s the caller can surface in run output
 *    (token/cost accounting per call is the Phase-4 demo material — the wrapper
 *    is where invocation metadata is captured, see [LlmCall.record]).
 */
class LlmCall(
    private val maxTransientAttempts: Int = 2,
    private val backoffMs: Long = 500L,
    private val clock: () -> Long = { System.nanoTime() },
) {
    val attempts = mutableListOf<LlmAttempt>()

    fun <T : Any> invoke(
        call: (attempt: Int) -> T,
        classify: (Throwable) -> RetryClass = LlmRetryClassifier::classify,
    ): T {
        var lastError: Throwable? = null
        for (attempt in 1..(maxTransientAttempts + 1)) {
            val started = clock()
            try {
                val result = call(attempt)
                attempts += LlmAttempt(attempt, failed = false, error = null, durationMs = (clock() - started) / 1_000_000)
                return result
            } catch (e: Throwable) {
                lastError = e
                attempts += LlmAttempt(attempt, failed = true, error = e.message, durationMs = (clock() - started) / 1_000_000)
                val cls = classify(e)
                if (cls == RetryClass.FAIL_FAST || attempt > maxTransientAttempts) {
                    val wrapped =
                        NonRetryableLlmException(
                            "LLM call failed fast after $attempt attempt(s): ${e.message ?: e.javaClass.simpleName}",
                            e,
                        )
                    throw wrapped
                }
                Thread.sleep(backoffMs * (1L shl (attempt - 1)))
            }
        }
        error("unreachable")
    }

    /** Cost/token accounting hook: the wrapper records what the caller supplies per
     *  successful call (Phase-4 demo reads `attempts`; live token counts wire here). */
    fun record(
        tokensIn: Long = 0,
        tokensOut: Long = 0,
    ) {
        tokenCounters = tokenCounters.first + tokensIn to tokenCounters.second + tokensOut
    }

    private var tokenCounters = 0L to 0L

    fun tokenStats(): Pair<Long, Long> = tokenCounters
}
