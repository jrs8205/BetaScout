package org.jarsi.betascout

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import java.util.Locale
import java.util.Properties
import org.junit.Test
import org.junit.runner.RunWith

// One-off probe: confirms this Google account works with the Aurora/gplayapi
// mechanism and reads the authoritative testingProgramAvailable flag per package.
// Credentials come in as instrumentation args so nothing is compiled in.
//
//   adb shell am instrument -w \
//     -e class org.jarsi.betascout.GplayProbe \
//     -e email <email> -e aasToken <aas_et/...> \
//     org.jarsi.betascout.test/androidx.test.runner.AndroidJUnitRunner
//
// Results are logged under the GPLAYPROBE tag (read with logcat).
@RunWith(AndroidJUnit4::class)
class GplayProbe {

    private val tag = "GPLAYPROBE"

    private val packages = listOf(
        "com.whatsapp",
        "com.spotify.music",
        "com.instagram.android",
        "com.duolingo",
        "org.telegram.messenger",
    )

    @Test
    fun queryTestingPrograms() {
        val args = InstrumentationRegistry.getArguments()
        val email = requireNotNull(args.getString("email")) { "pass -e email" }
        val aasToken = requireNotNull(args.getString("aasToken")) { "pass -e aasToken" }

        // gplayapi loads its device profile from res/raw (needs a Context), so we
        // pass an explicit device profile bundled as a test asset instead. It is a
        // spoofed fingerprint for the API call, unrelated to the phone running this.
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val deviceProperties = Properties().apply {
            testContext.assets.open("device.properties").use { load(it) }
        }
        val authData = AuthHelper.build(
            email,
            aasToken,
            AuthHelper.Token.AAS,
            /* isAnonymous = */ false,
            deviceProperties,
            Locale.US,
        )
        Log.i(tag, "AUTH OK: authenticated as $email")

        val helper = AppDetailsHelper(authData)
        for (pkg in packages) {
            try {
                val app = helper.getAppByPackageName(pkg)
                val program = app?.testingProgram
                Log.i(
                    tag,
                    "$pkg available=${program?.isAvailable} subscribed=${program?.isSubscribed} " +
                        "versionCode=${app?.versionCode} name=${app?.displayName}",
                )
            } catch (e: Exception) {
                Log.i(tag, "$pkg ERROR ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
