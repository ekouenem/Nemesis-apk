package com.nemesis.apk

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nemap.android.core.DomHealth
import com.nemap.android.web.Engine
import com.nemap.android.web.WebViewHost
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproductions locales et deterministes du crash du moteur DOM.
 * Aucun appel DeepSeek, aucun compte, aucune restauration de page distante :
 * le WebView reste sur about:blank et le reseau de l'emulateur est coupe.
 */
@RunWith(AndroidJUnit4::class)
class DomTimeoutInstrumentedTest {
    private fun fixture() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val app = inst.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        inst.waitForIdleSync()
        inst.runOnMainSync { WebViewHost.load("about:blank") }
        val browser = inst.startActivitySync(
            Intent(ctx, com.nemap.android.BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        inst.runOnMainSync { WebViewHost.load("about:blank") }
        // Refermes par le test appelant via finish().
        fixtureActivities = Pair(app, browser)
    }

    private var fixtureActivities: Pair<android.app.Activity, android.app.Activity>? = null

    private fun tearDown() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync { WebViewHost.load("about:blank") }
        fixtureActivities?.let { it.second.finish(); it.first.finish() }
        fixtureActivities = null
    }

    /**
     * Regressif : quand le looper principal est sature, l'ancien timer etait
     * poste AVANT l'envoi du JS, devenait echu et declarait un faux timeout
     * avant que le callback du WebView ne soit depile.
     */
    @Test fun mainLooperStallMustNotProduceAFalseDomTimeout() = runBlocking {
        DomHealth.noteRestart()
        fixture()
        try {
            Handler(Looper.getMainLooper()).post { Thread.sleep(900L) }
            Thread.sleep(120L)
            val value = withTimeout(15_000L) { Engine.evalJs("1+1", timeoutMs = 400L) }
            assertEquals("2", value)
            assertFalse("Une reussite remet la sante a zero", DomHealth.unresponsive)
        } finally {
            tearDown()
        }
    }

    /**
     * Regressif : trois lectures avec le meme budget trop court echouaient
     * ensemble sur un renderer ralenti. Le budget doit augmenter a chaque
     * observation idempotente, sans jamais rejouer d'action.
     */
    @Test fun repeatedSlowObservationsRecoverWithAGrowingBudget() = runBlocking {
        DomHealth.noteRestart()
        fixture()
        try {
            withTimeout(10_000L) { Engine.ensureAdapter() }
            Engine.evalJs(
                "(function(){var f=window.__napDS.continueAvailable;window.__slowReads=0;" +
                    "window.__napDS.continueAvailable=function(){window.__slowReads++;" +
                    "if(window.__slowReads<=2){var e=Date.now()+900;while(Date.now()<e){}}" +
                    "return f()};return 'ok'})()"
            )
            // Ne doit pas lever : les budgets 450 -> 6450 -> 12450 ms absorbent
            // le ralentissement. Avec l'ancien budget fixe de 450 ms, ceci echouait.
            Engine.readAdapter("continueAvailable", timeoutMs = 450L)
            assertEquals("2", Engine.evalJs("window.__slowReads"))
            assertFalse(DomHealth.unresponsive)
            try {
                Engine.readAdapter("submit")
                fail("Une action ne doit jamais etre rejouee par la recuperation en lecture")
            } catch (_: IllegalArgumentException) {
            }
        } finally {
            tearDown()
        }
    }
}
