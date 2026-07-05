package org.jarsi.betascout.data.gplay

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exchanges the short-lived `oauth_token` captured from Google's embedded sign-in
 * for a long-lived AAS token that gplayapi uses. Done in-app so the user just
 * signs in normally; they never see a token.
 */
object GoogleAuth {

    private const val AUTH_URL = "https://android.clients.google.com/auth"

    suspend fun exchangeOAuthToken(
        email: String,
        oauthToken: String,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Result<String> = withContext(io) {
        try {
            val params = linkedMapOf(
                "accountType" to "HOSTED_OR_GOOGLE",
                "Email" to email,
                "has_permission" to "1",
                "add_account" to "1",
                "ACCESS_TOKEN" to "1",
                "Token" to oauthToken,
                "service" to "ac2dm",
                "source" to "android",
                "androidId" to randomHex16(),
                "device_country" to "us",
                "operatorCountry" to "us",
                "lang" to "en",
                "sdk_version" to "17",
                "google_play_services_version" to "240913000",
                "client_sig" to "38918a453d07199354f8b19af05ec6562ced5788",
                "callerSig" to "38918a453d07199354f8b19af05ec6562ced5788",
                "droidguard_results" to "dummy123",
            )
            val body = params.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
            val connection = (URL(AUTH_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "GoogleAuth/1.4 (generic_x86 KOT49H); gzip")
                setRequestProperty("app", "com.google.android.gms")
            }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            val token = text.lineSequence()
                .firstOrNull { it.startsWith("Token=") }
                ?.removePrefix("Token=")
            if (token != null && token.startsWith("aas_et/")) {
                Result.success(token)
            } else {
                Result.failure(IllegalStateException(text.take(200).ifBlank { "empty response" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun randomHex16(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
