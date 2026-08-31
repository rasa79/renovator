package com.renovator.execution

/**
 * A byte-budgeted view of a captured build log (PLAN §8.5): 4 KiB head + 8 KiB tail.
 * The full log always exists somewhere the judge can keep (the caller decides: the
 * trajectory store later keeps full logs; the planner only ever sees the Excerpt).
 */
data class Excerpt(
    val head: String,
    val tail: String,
    val truncatedBytes: Long,
) {
    companion object {
        const val HEAD_BUDGET = 4096
        const val TAIL_BUDGET = 8192

        fun of(
            text: String,
            headBudget: Int = HEAD_BUDGET,
            tailBudget: Int = TAIL_BUDGET,
        ): Excerpt {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val budget = headBudget + tailBudget
            if (bytes.size <= budget) {
                return Excerpt(text, "", 0L)
            }
            val head = bytes.copyOfRange(0, headBudget).toString(Charsets.UTF_8)
            val tail = bytes.copyOfRange(bytes.size - tailBudget, bytes.size).toString(Charsets.UTF_8)
            return Excerpt(head, tail, (bytes.size - budget).toLong())
        }
    }
}
