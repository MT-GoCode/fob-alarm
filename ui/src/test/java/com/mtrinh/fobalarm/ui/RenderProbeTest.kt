package com.mtrinh.fobalarm.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PROBE: does a Compose tree render and become assertable on the JVM in this project at all? */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderProbeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a composable renders and its text is findable`() {
        compose.setContent { Text("sentinel is away") }
        compose.onNodeWithText("sentinel is away").assertIsDisplayed()
    }
}
