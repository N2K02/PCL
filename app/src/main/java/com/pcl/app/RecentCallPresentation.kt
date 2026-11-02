package com.pcl.app

/** UI-only labels: metadata never changes because a badge was rendered. */
internal object RecentCallPresentation {
    fun source(
        app: String?, whatsapp: Boolean, sim: SimCallIdentity?,
    ): String = when {
        !app.isNullOrBlank() -> CallerId.clean(app)
        whatsapp -> "WhatsApp"
        else -> sim?.badge() ?: "SIM"
    }

    fun outcome(type: CallHistory.Type): String = when (type) {
        CallHistory.Type.INCOMING -> "Incoming"
        CallHistory.Type.OUTGOING -> "Outgoing"
        CallHistory.Type.MISSED -> "Missed"
        CallHistory.Type.DECLINED -> "Declined"
    }

    fun isVoip(app: String?, whatsapp: Boolean) =
        !app.isNullOrBlank() || whatsapp
}
