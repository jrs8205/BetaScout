import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import java.io.File

// Queries Google Play (via gplayapi) for the authoritative testing-program flag
// per package. Reads credentials from a .gplay.local file (EMAIL, AAS_TOKEN).
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

    val authData = AuthHelper.build(email, aasToken)
    val helper = AppDetailsHelper(authData)

    for (pkg in args.drop(1)) {
        try {
            val app = helper.getAppByPackageName(pkg)
            if (app == null) {
                println("$pkg\tNOT_FOUND")
            } else {
                println(
                    "$pkg\ttestingProgramAvailable=${app.testingProgramAvailable}" +
                        "\tversionCode=${app.versionCode}\tname=${app.displayName}",
                )
            }
        } catch (e: Exception) {
            println("$pkg\tERROR ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
