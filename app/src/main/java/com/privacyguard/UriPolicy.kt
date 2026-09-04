package com.privacyguard

import android.net.Uri

object UriPolicy {
    private val contactsAuthorities = setOf("com.android.contacts", "contacts")
    private val callLogAuthorities = setOf("call_log", "com.android.calllog")
    private val smsAuthorities = setOf("sms")
    private val mmsAuthorities = setOf("mms", "mms-sms")
    private val mediaAuthorities = setOf("media", "com.android.providers.media.documents", "com.google.android.apps.photos.contentprovider", "com.google.android.apps.photos.content", "com.google.android.apps.photos.api", "com.miui.gallery.provider", "com.sec.android.gallery3d.provider", "com.oneplus.gallery.provider", "com.coloros.gallery3d", "com.huawei.photos")

    fun ruleFor(uri: Uri?): PrivacyRule? {
        val authority = uri?.authority?.lowercase() ?: return null
        return when {
            authority in contactsAuthorities -> PrivacyRule.CONTACTS
            authority in callLogAuthorities -> PrivacyRule.CALL_LOGS
            authority in smsAuthorities -> PrivacyRule.SMS
            authority in mmsAuthorities -> PrivacyRule.MMS
            authority in mediaAuthorities -> PrivacyRule.MEDIA
            else -> null
        }
    }

    fun isSensitive(uri: Uri?): Boolean = ruleFor(uri) != null
}
