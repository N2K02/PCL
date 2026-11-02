package com.pcl.app

/** Device-independent verification before selecting any Telecom SIM account. */
internal object SimDialAccountPolicy {
    data class Subscription(val id: Int, val slot: Int)
    data class Account(val mappedSubscriptionId: Int?, val simCapable: Boolean,
                       val supportsTel: Boolean)

    /** Returns the index of the sole verified PhoneAccount, or null (unsupported). */
    fun choose(slot: Int, subs: List<Subscription>, accounts: List<Account>): Int? {
        if (slot !in 0..1) return null
        val matches = subs.filter { it.slot == slot && it.id >= 0 }
        if (matches.size != 1) return null
        val id = matches.single().id
        return accounts.indices.filter { index ->
            val a = accounts[index]
            a.mappedSubscriptionId == id && a.simCapable && a.supportsTel
        }.singleOrNull()
    }
}
