package com.mtrinh.fobalarm.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mtrinh.fobalarm.core.Role
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * What each role actually SEES, asserted against the rendered tree.
 *
 * Every defect this app has shipped in a year of role work was a two-way branch over three roles,
 * and not one was reachable by the 139 tests in `:core` and `:data`. Reading the code is the
 * assurance that failed, four times, each time found by the owner holding the phone.
 *
 * Rule for every test here: an absence assertion is paired with a positive anchor unique to the
 * screen it claims to be looking at. Otherwise a blank tree satisfies it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoleScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun exists(text: String, substring: Boolean = true) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /**
     * Proves we are on the Status TAB -- not an empty tree, and not the connect screen.
     *
     * "Power" alone was not enough: the connect screen renders the whole of `StatusBlock` under
     * "What it last said", so a phone wrongly held on the connect screen satisfied it, and the
     * authority, ring-takeover and battery-label tests would all have stayed green.
     */
    private fun assertOnStatusTab() {
        compose.onNodeWithText("Power", substring = true).assertIsDisplayed()
        assertTrue(!exists(M_TRYING), "this is the connect screen, not the Status tab")
    }

    // --- defect 4: a sentinel's own battery was labelled "Controller" -----------

    @Test fun `a sentinel's power rows name this phone, never the controller`() {
        // Shipped defect: `val controller = if (iAmAlarm) s.peer else s.self` resolved to the
        // sentinel ITSELF, so its own battery appeared under the label "Controller". The real
        // controller is not in a sentinel's snapshot at all -- the client overwrites `peer` with
        // whatever phone it polled, which for a sentinel is the alarm phone.
        compose.renderRoot(Role.SENTINEL)
        assertOnStatusTab()
        // `$OWN_BATTERY%`, not the words "This phone": that label also appears on the Permissions
        // row and as a Setup heading, so asserting it passed whether or not the power row
        // rendered -- which the `sentinel-own-power-row-dropped` mutant proved by surviving.
        assertTrue(exists("$OWN_BATTERY%"), "a sentinel must account for its own power: the gate " +
                "vetoes its block below 20% unplugged, so the owner has to see it")
        assertTrue(!exists("Controller"), "a sentinel has no controller row to show")
    }

    @Test fun `a controller's power rows do name the controller`() {
        // The other side of the same branch, so the test above cannot pass by the label simply
        // never being rendered for anyone.
        compose.renderRoot(Role.CONTROLLER)
        assertOnStatusTab()
        assertTrue(exists("Controller"), "the controller IS the phone that stops it")
        // And the row carries THIS phone's battery, not the alarm phone's. Asserting the label
        // alone let `Role.CONTROLLER -> s.peer` survive: the row would still be there, showing
        // the wrong phone.
        assertTrue(exists("$OWN_BATTERY%"), "the controller's row must be the controller's")
    }

    // --- the sentinel has no authority over the alarm --------------------------

    @Test fun `a sentinel is offered no action that changes the alarm`() {
        compose.renderRoot(Role.SENTINEL)
        assertOnStatusTab()
        listOf("Skip it", "Move it", "Nap for", "Nap until", "Revert").forEach {
            assertTrue(!exists(it), "a sentinel must not be able to $it: it is the pocket phone " +
                    "the design distrusts, and the mutating routes carry no role")
        }
    }

    @Test fun `a controller is offered those actions`() {
        // Again the opposite arm: without this, deleting the action rows entirely would leave the
        // sentinel test green and the product broken.
        compose.renderRoot(Role.CONTROLLER)
        assertOnStatusTab()
        listOf("Skip it", "Move it", "Nap for").forEach {
            assertTrue(exists(it), "the controller is the phone that acts, and lost: $it")
        }
    }

    // --- tabs ------------------------------------------------------------------

    @Test fun `a sentinel has exactly Status and Setup`() {
        // Hand-written, in owner language, not read from any table in the production code.
        compose.renderRoot(Role.SENTINEL)
        assertTrue(exists("Status", substring = false))
        assertTrue(exists("Setup", substring = false))
        assertTrue(!exists("Settings", substring = false),
            "the settings editor writes through to the alarm phone; a sentinel has no say")
        assertTrue(!exists("History", substring = false))
    }

    @Test fun `the controller has all four tabs`() {
        compose.renderRoot(Role.CONTROLLER)
        listOf("Status", "Setup", "Settings", "History").forEach {
            assertTrue(exists(it, substring = false), "the controller lost the $it tab")
        }
    }

    // --- a ring must not take over a sentinel's screen -------------------------

    @Test fun `a ringing alarm takes over the controller's screen`() {
        // The positive arm. Without it, a change that stopped the ring screen appearing for
        // ANYONE would leave the sentinel test below green and the product broken.
        compose.renderRoot(Role.CONTROLLER, snap = snapshot(Role.CONTROLLER, ring = ringing()))
        assertTrue(exists("ALARM PHONE IS RINGING"),
            "the controller is the phone that stops the alarm; the ring must seize its screen")
    }

    @Test fun `a ringing alarm leaves a sentinel on its ordinary screen`() {
        compose.renderRoot(Role.SENTINEL, snap = snapshot(Role.SENTINEL, ring = ringing()))
        assertOnStatusTab()
        assertTrue(!exists("ALARM PHONE IS RINGING"),
            "a sentinel cannot dismiss, so a ring must not seize its screen and offer it a " +
                "button that belongs to a controller")
    }

    // --- a joiner that has never heard from the alarm phone --------------------

    // --- which group am I paired with --------------------------------------------

    // --- defect 3, now provable: who is shown the HOST's editor -----------------

    @Test fun `a sentinel is shown the joiner summary and never the host's editor`() {
        // Defect 3 as shipped: a sentinel fell through a two-way branch to the host's credential
        // editor -- offered the group's own name and passphrase, with editing gated behind the
        // ALARM phone's password. It was unprovable while the choice lived in `:app`; the slots
        // are split now, so `:ui` makes it and this test can see it.
        compose.renderRoot(Role.SENTINEL)
        compose.onNodeWithText("Setup", substring = false).performClick()
        assertTrue(exists(M_JOINER_SUMMARY), "a sentinel must be told which group it joined")
        assertTrue(!exists(M_HOST_PAIRING), "a sentinel joins a group; it does not host one")
    }

    @Test fun `the controller is shown the joiner summary too`() {
        compose.renderRoot(Role.CONTROLLER)
        compose.onNodeWithText("Setup", substring = false).performClick()
        assertTrue(exists(M_JOINER_SUMMARY))
        assertTrue(!exists(M_HOST_PAIRING), "only the phone that hosts the group edits it")
    }

    @Test fun `the alarm phone is shown the host's editor`() {
        // The opposite arm, so the two tests above cannot pass by the editor never rendering.
        compose.renderRoot(Role.ALARM)
        compose.onNodeWithText("Setup", substring = false).performClick()
        assertTrue(exists(M_HOST_PAIRING), "the alarm phone hosts the group and edits it")
        assertTrue(!exists(M_JOINER_SUMMARY))
    }

    @Test fun `the alarm phone is offered the device editors`() {
        // The positive arm FIRST. Without it the controller test below passes if the Settings
        // click did nothing, or if the editors were deleted for everyone.
        compose.renderRoot(Role.ALARM)
        compose.onNodeWithText("Settings", substring = false).performClick()
        assertTrue(exists(M_DEVICE_SETTINGS),
            "the ringtone and the settings password belong to the phone that rings")
    }

    @Test fun `a controller is not offered the device editors`() {
        // `deviceSettings` was nullable, so whether `:app` passed it WAS the decision. Now it is
        // always passed and `:ui` decides, which is why this can be asserted at all.
        compose.renderRoot(Role.CONTROLLER)
        compose.onNodeWithText("Settings", substring = false).performClick()
        // Anchored on something the Settings tab always has for every role, so "absent" cannot
        // mean "never navigated". NOT "Away" -- that section is alarm-only, which this test
        // discovered by going red.
        assertTrue(exists(M_ROLE_SWITCHER),
            "this must be the Settings tab for the absence below to mean anything")
        assertTrue(!exists(M_DEVICE_SETTINGS), "a controller editing the ringtone would be " +
                "editing hardware it does not own")
    }

    @Test fun `a sentinel can still change its role`() {
        // Its ONLY way out: the sentinel has no Settings tab, so the role switcher is appended to
        // Setup. Nothing asserted this, so deleting it survived -- and a sentinel with no way to
        // change role can only be fixed by clearing app data.
        compose.renderRoot(Role.SENTINEL)
        compose.onNodeWithText("Setup", substring = false).performClick()
        assertTrue(exists(M_ROLE_SWITCHER), "a phone set to the wrong role must not be a dead end")
    }

    @Test fun `a joiner that has never connected can still change its role`() {
        // The other dead end: before any snapshot exists, the connect screen is all there is.
        compose.renderRoot(Role.SENTINEL, snap = null)
        assertTrue(exists(M_TRYING))
        assertTrue(exists(M_ROLE_SWITCHER), "a wrong role chosen at setup must be escapable")
    }

    @Test fun `a joiner with no snapshot is shown the connect screen, not a dead spinner`() {
        // Defects 1 and 2 collapse into this one observable. The sentinel used to reach `s!!` and
        // crash; then it reached a spinner with no way to enter the group credentials.
        compose.renderRoot(Role.SENTINEL, snap = null)
        compose.onNodeWithText(M_TRYING).assertIsDisplayed()
    }

    @Test fun `a joiner holding a stale snapshot still shows the connect screen`() {
        // The clock seam exists for this: the snapshot arrived, then a minute passed with no reply.
        compose.renderRoot(Role.CONTROLLER, staleFor = 60_000)
        compose.onNodeWithText(M_TRYING).assertIsDisplayed()
    }
}
