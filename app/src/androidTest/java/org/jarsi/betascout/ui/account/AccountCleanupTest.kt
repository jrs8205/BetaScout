package org.jarsi.betascout.ui.account

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jarsi.betascout.MainActivity
import org.jarsi.betascout.R
import org.jarsi.betascout.assumeDisposableInstall
import org.jarsi.betascout.data.settings.SessionCookieCipher
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.SignOutUseCase
import org.jarsi.betascout.windowNodes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountCleanupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun findButton(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
            return generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findButton(child, text)?.let { return it }
        }
        return null
    }

    private suspend fun click(label: Int) {
        val text = context.getString(label)
        val clicked = withTimeoutOrNull(10_000) {
            while (true) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root?.packageName == context.packageName &&
                    findButton(root, text)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                ) break
                delay(100)
            }
            true
        }
        assertTrue("Expected a clickable '$text' on the account screen", clicked == true)
    }

    @Test
    fun cleanupCanBeRetriedFromSettingsAfterExpiryAndReopening() = runBlocking {
        assumeDisposableInstall()
        val settings = SettingsRepository(context, SessionCookieCipher())
        settings.setScanBlockedUntil(System.currentTimeMillis() + 3_600_000)
        settings.savePlaySession("fixture@example.test", "SID=fixture")
        val signedIn = checkNotNull(settings.playSession.first())
        var failDeletion = true
        var observationsRemain = true
        var cookiesRemain = true
        val action = SignOutUseCase(
            cancelScanWork = {},
            withScanLock = { it() },
            clearObservations = {
                if (failDeletion) Result.failure(IOException("disk")) else {
                    observationsRemain = false
                    Result.success(Unit)
                }
            },
            clearSession = settings::clearPlaySession,
            clearWebViewCookies = { cookiesRemain = false },
            rescheduleBackgroundScans = {},
        )
        fun viewModel() = AccountViewModel(settings, WorkManager.getInstance(context), action)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        var vm = viewModel()
        try {
            withContext(Dispatchers.Main) { activity.setContent { AccountScreen(onBack = null, viewModel = vm) } }
            click(R.string.account_signout)
            withTimeout(5_000) { vm.state.first { it.signOutFailed && !it.busy } }
            assertTrue(observationsRemain && cookiesRemain)
            assertTrue(settings.expirePlaySession(signedIn))
            withTimeout(5_000) { vm.state.first { !it.signedIn } }

            // Reopen with an account view model that never saw the transient failure.
            vm.viewModelScope.cancel()
            vm = viewModel()
            withContext(Dispatchers.Main) { activity.setContent { AccountScreen(onBack = null, viewModel = vm) } }
            failDeletion = false
            click(R.string.account_clear_local_data)
            withTimeout(5_000) { settings.sessionExpired.first { !it } }
            assertFalse(observationsRemain)
            assertFalse(cookiesRemain)
            val signIn = context.getString(R.string.account_signin)
            val clear = context.getString(R.string.account_clear_local_data)
            withTimeout(5_000) {
                while (true) {
                    val labels = instrumentation.windowNodes(context.packageName).map { it.label }
                    if (signIn in labels && clear !in labels) break
                    delay(100)
                }
            }
        } finally {
            vm.viewModelScope.cancel()
            withContext(Dispatchers.Main) { activity.finish() }
            settings.clearPlaySession()
            settings.setScanBlockedUntil(0L)
        }
    }
}
