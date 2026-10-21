package com.mtrinh.fobalarm.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.mtrinh.fobalarm.core.Role
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every screen every role can see, rendered to a PNG, for a human to look at.
 *
 * NOT a gate and not an assertion: no baseline images, no pixel comparison, nothing to approve
 * when a colour changes. A suite of assertions can only catch what someone already thought to
 * assert; this exists for everything else.
 *
 * One cell per row of the table below. `index.html` is the page to open.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ContactSheetTest(private val case: Case) {

    data class Case(val name: String, val role: Role, val tab: String?, val build: () -> Any?) {
        override fun toString() = name
    }

    companion object {
        private val OUT = File("build/screens")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Case> = buildList {
            for (role in listOf(Role.ALARM, Role.CONTROLLER, Role.SENTINEL)) {
                val who = role.name.lowercase()
                add(Case("$who-status", role, null) { snapshot(role) })
                add(Case("$who-setup", role, "Setup") { snapshot(role) })
                if (role != Role.SENTINEL) {
                    add(Case("$who-settings", role, "Settings") { snapshot(role) })
                    add(Case("$who-history", role, "History") { snapshot(role) })
                }
                add(Case("$who-ringing", role, null) { snapshot(role, ring = ringing()) })
                add(Case("$who-nap-armed", role, null) {
                    snapshot(role, nap = NapViewArmed) })
                add(Case("$who-tomorrow-skipped", role, null) {
                    snapshot(role, tomorrow = TomorrowSkipped) })
                add(Case("$who-setup-incomplete", role, "Setup") {
                    snapshot(role, gates = gatesAllPass().copy(foregroundService = false)) })
                if (role == Role.ALARM) {
                    add(Case("alarm-ring-blocked", role, null) {
                        snapshot(role, ringBlock = "sentinel away 1200s",
                            sentinel = device(Role.SENTINEL, lastSeenMs = NOW - 20 * 60_000)) })
                }
                if (role != Role.ALARM) {
                    add(Case("$who-never-connected", role, null) { null })
                    add(Case("$who-link-lost", role, null) { STALE })
                }
            }
        }

        /** Marker: render with a snapshot that arrived, then went a minute without a reply. */
        private val STALE = Any()
    }

    @get:Rule val compose = createComposeRule()

    @Test fun capture() {
        // The mutation harness runs the whole suite once per mutant, and this class asserts
        // nothing -- no mutant can ever be killed by it. Capturing 27 screens 65 times over was
        // pure cost, so the harness switches it off and `./gradlew test` still writes the sheet.
        assumeTrue("screens off", System.getProperty("fobalarm.screens") != "off")
        val built = case.build()
        when (built) {
            null -> compose.renderRoot(case.role, snap = null)
            STALE -> compose.renderRoot(case.role, staleFor = 60_000)
            else -> compose.renderRoot(case.role, snap = built as com.mtrinh.fobalarm.core.Snapshot)
        }
        case.tab?.let { compose.clickTab(it) }
        OUT.mkdirs()
        compose.onRoot().captureRoboImage(File(OUT, "${case.name}.png").path)
    }
}
