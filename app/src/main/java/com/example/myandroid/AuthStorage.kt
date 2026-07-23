package com.example.myandroid

import android.content.Context
import org.json.JSONObject

class AuthStorage(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLogin(username: String, response: String) {
        preferences.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_LOGIN_RESPONSE, response)
            .apply()
    }

    fun getSavedUsername(): String? = preferences.getString(KEY_USERNAME, null)

    fun getLoginResponse(): String? = preferences.getString(KEY_LOGIN_RESPONSE, null)

    fun getSecurityToken(): String? {
        val response = getLoginResponse().orEmpty()
        if (response.isBlank()) {
            return null
        }

        return runCatching {
            val root = JSONObject(response)
            root.optJSONObject("data")?.optString("token")
                ?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
    }

    fun isLoggedIn(): Boolean = !getLoginResponse().isNullOrBlank()

    fun clear() {
        preferences.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_LOGIN_RESPONSE)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "native_auth"
        private const val KEY_USERNAME = "username"
        private const val KEY_LOGIN_RESPONSE = "login_response"
    }
}
