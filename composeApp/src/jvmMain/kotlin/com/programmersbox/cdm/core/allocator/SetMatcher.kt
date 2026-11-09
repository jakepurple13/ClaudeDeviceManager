package com.programmersbox.cdm.core.allocator

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.Platform

/**
 * Bipartite matching of set specs to candidate devices (Kuhn's augmenting paths).
 * A candidate with [AllocDevice.partner] stands for its whole pair: it fits a spec when either side fits,
 * and the side that fits is the returned device. The caller passes each pair once, as its primary.
 */
object SetMatcher {
    /** One device per spec, in spec order, or null when the specs cannot all be met. */
    fun assign(specs: List<Wants>, candidates: List<AllocDevice>): List<AllocDevice>? {
        val m = match(specs, candidates)
        return if (m.size == specs.size) specs.indices.map { m.getValue(it) } else null
    }

    /** Spec index to device for a maximum matching. */
    fun maxPartial(specs: List<Wants>, candidates: List<AllocDevice>): Map<Int, AllocDevice> = match(specs, candidates)

    /** `android1`, `ios1`, `android2`... in spec order. */
    fun names(specs: List<Wants>): List<String> {
        val counts = HashMap<Platform?, Int>()
        return specs.map { w ->
            val n = (counts[w.platform] ?: 0) + 1
            counts[w.platform] = n
            "${if (w.platform == Platform.IOS) "ios" else "android"}$n"
        }
    }

    private fun sides(c: AllocDevice): List<AllocDevice> {
        val partner = c.partner ?: return listOf(c)
        val other = c.copy(
            key = partner,
            name = c.name,
            category = if (c.category == Category.WATCH) Category.PHONE else Category.WATCH,
            connectedSerials = emptyList(),
            partner = null,
        )
        return listOf(c.copy(partner = null), other)
    }

    private fun match(specs: List<Wants>, candidates: List<AllocDevice>): Map<Int, AllocDevice> {
        val options = candidates.map { sides(it) }
        val owner = IntArray(candidates.size) { -1 } // candidate -> spec
        val side = arrayOfNulls<AllocDevice>(candidates.size)

        fun augment(spec: Int, seen: BooleanArray): Boolean {
            for ((ci, opts) in options.withIndex()) {
                if (seen[ci]) continue
                val fit = opts.firstOrNull { specs[spec].fits(it) } ?: continue
                seen[ci] = true
                if (owner[ci] == -1 || augment(owner[ci], seen)) {
                    owner[ci] = spec
                    side[ci] = fit
                    return true
                }
            }
            return false
        }

        for (s in specs.indices) augment(s, BooleanArray(candidates.size))
        return owner.indices.filter { owner[it] >= 0 }.associate { owner[it] to side[it]!! }
    }
}
