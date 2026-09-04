package com.privacyguard

import android.net.Uri

object UriPolicy {
    private val sensitive = setOf(
        "com.android.contacts", "contacts",
        "call_log", "com.android.calllog",
        "sms", "mms", "mms-sms", "telephony",
        "media", "com.android.providers.media.documents"
    )

    fun isSensitive(uri: Uri?): Boolean =
        uri?.authority?.lowercase() in sensitive
}
