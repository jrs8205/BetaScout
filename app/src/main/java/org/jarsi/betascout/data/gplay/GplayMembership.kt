package org.jarsi.betascout.data.gplay

import android.content.Context
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import java.util.Locale
import java.util.Properties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jarsi.betascout.domain.MembershipSource

/**
 * Reads authoritative beta membership from Google Play with the user's own
 * account (via gplayapi). This is the only reliable way to know which betas the
 * user is actually enrolled in; it uses an unofficial Play API, so the account
 * credential is the user's own AAS token, kept on device.
 */
class GplayMembership(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MembershipSource {

    /** Returns the subset of [packages] the account is subscribed to the beta of. */
    override suspend fun subscribedPackages(
        email: String,
        aasToken: String,
        packages: List<String>,
    ): Result<Set<String>> = withContext(io) {
        try {
            val deviceProperties = Properties().apply {
                context.assets.open("device.properties").use { load(it) }
            }
            val authData = AuthHelper.build(email, aasToken, AuthHelper.Token.AAS, false, deviceProperties, Locale.US)
            val helper = AppDetailsHelper(authData)
            val subscribed = packages.filter { packageName ->
                runCatching {
                    helper.getAppByPackageName(packageName)?.testingProgram?.isSubscribed == true
                }.getOrDefault(false)
            }.toSet()
            Result.success(subscribed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
