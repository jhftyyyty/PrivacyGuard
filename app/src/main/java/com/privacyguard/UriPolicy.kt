package com.privacyguard

import android.net.Uri

object UriPolicy {
    private val sensitiveAuthorities = setOf(
        "com.android.contacts",
        "contacts",
        "call_log",
        "com.android.calllog",
        "sms",
        "mms",
        "mms-sms",
        "telephony",
        "media",
        "com.android.providers.media.documents"
    )

    fun isSensitive(uri: Uri?): Boolean {
        val authority = uri?.authority?.lowercase() ?: return false
        return authority in sensitiveAuthorities
    }
}
