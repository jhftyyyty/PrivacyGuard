package com.privacyguard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** Read-only bridge used by scoped target processes to read their own policy. */
class PrivacyPolicyProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "get_policy") return null
        val ctx = context ?: return null
        val callingPackage = callingPackage ?: return null
        val prefs = ctx.getSharedPreferences("privacy_policies", android.content.Context.MODE_PRIVATE)
        val out = Bundle()
        out.putBoolean("enabled", prefs.getBoolean("$callingPackage.enabled", false))
        PrivacyRule.values().forEach { rule ->
            out.putBoolean(rule.name, prefs.getBoolean("$callingPackage.${rule.name}", false))
        }
        out.putString("custom_path", prefs.getString("$callingPackage.custom_path", "") ?: "")
        return out
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun shutdown() = super.shutdown()
}
