package org.jarsi.betascout

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * Skips the calling test unless the person who started this run declared the
 * installed app disposable.
 *
 * Call it before anything that changes the app under test in a way the test
 * cannot undo — granting a runtime permission (revoking it again would kill the
 * very process the test runs in), finishing onboarding, posting or cancelling a
 * notification the real app also uses. There is no default: the argument has to
 * be given for the run, for an emulator chosen for the purpose,
 *
 *     -Pandroid.testInstrumentationRunnerArguments.disposableInstall=true
 *
 * (or `-e disposableInstall true` with `am instrument`). Without it such tests
 * are skipped, whoever runs them and however.
 */
fun assumeDisposableInstall() {
    assumeTrue(
        "this test changes the installed app; pass the disposableInstall=true runner " +
            "argument to run it on an install that may be changed",
        InstrumentationRegistry.getArguments().getString("disposableInstall") == "true",
    )
}

/** One node of a window: its text (or content description) and widget class. */
data class WindowNode(val label: String, val className: String)

/**
 * Every node of the window in front, if it is [packageName]'s; an empty list
 * while no window of that package can be read (between two screens, or with
 * another app in front). This is what the user sees, read the way an
 * accessibility service would read it.
 */
fun Instrumentation.windowNodes(packageName: String): List<WindowNode> {
    val root = uiAutomation.rootInActiveWindow ?: return emptyList()
    if (root.packageName?.toString() != packageName) return emptyList()
    val nodes = mutableListOf<WindowNode>()
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        nodes += WindowNode(
            label = (node.text ?: node.contentDescription ?: "").toString(),
            className = node.className?.toString().orEmpty(),
        )
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let(pending::add)
        }
    }
    return nodes
}

/** The sign-in page is a WebView; nothing else in the app is. */
fun List<WindowNode>.showSignInPage(): Boolean = any { it.className == "android.webkit.WebView" }
