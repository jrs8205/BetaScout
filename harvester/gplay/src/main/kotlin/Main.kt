import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import java.io.File
import java.util.Locale
import java.util.Properties

// Server-side gplayapi query (plain JVM, no Android). Reads credentials from a
// .gplay.local file (EMAIL, AAS_TOKEN) and the spoofed device profile from a
// bundled resource, then prints the authoritative testingProgram flag and the
// production versionCode per package.
//
//   gradlew -p harvester/gplay run --args="<path-to-.gplay.local> <pkg> [pkg...]"

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: run --args=\"<path-to-.gplay.local> <pkg> [pkg...]\"")
        return
    }

    val creds = File(args[0]).readLines()
        .filter { it.contains('=') }
        .associate { line ->
            val eq = line.indexOf('=')
            line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }
    val email = requireNotNull(creds["EMAIL"]) { "EMAIL missing from credentials file" }
    val aasToken = requireNotNull(creds["AAS_TOKEN"]) { "AAS_TOKEN missing from credentials file" }

    val deviceProperties = Properties().apply {
        val stream = checkNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream("device.properties"),
        ) { "device.properties resource missing" }
        stream.use { load(it) }
    }

    val authData = AuthHelper.build(email, aasToken, AuthHelper.Token.AAS, false, deviceProperties, Locale.US)
    System.err.println("AUTH OK: authenticated as $email")

    val helper = AppDetailsHelper(authData)
    for (pkg in args.drop(1)) {
        try {
            val app = helper.getAppByPackageName(pkg)
            val program = app?.testingProgram
            println(
                "$pkg\tavailable=${program?.isAvailable}\tsubscribed=${program?.isSubscribed}" +
                    "\tversionCode=${app?.versionCode}\tname=${app?.displayName}",
            )
        } catch (e: Exception) {
            println("$pkg\tERROR ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
