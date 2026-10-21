package com.mtrinh.fobalarm.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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

    /** The anchor that proves we are looking at a rendered Status tab and not an empty tree. */
    private fun assertOnStatusTab() {
        compose.onNodeWithText("Power", substring = true).assertIsDisplayed()
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
