package com.pcl.app

/** Only an EXACT subscription ID to phone-account match permits directed SIM dialing. */
internal object SimDialAccountPolicy {
    data class Candidate(val subscriptionId: Int, val slot: Int, val accountId: String)

    fun choose(slot: Int, subscriptions: List<Candidate>): String? {
        if (slot !in 0..3) return null
        val eligible = subscriptions.filter {
            it.slot == slot && it.subscriptionId >= 0 &&
                it.accountId == it.subscriptionId.toString()
        }
        return eligible.singleOrNull()?.accountId
    }
}
