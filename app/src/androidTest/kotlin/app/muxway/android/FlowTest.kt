package app.muxway.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxway.android.store.Hub
import app.muxway.android.store.Settings
import app.muxway.android.store.SettingsStore
import app.muxway.android.store.Vault
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Connect, list, open, type, back, settings: one pass through the app
 * against the test hub that scripts/ui-tests.sh starts and reaches through
 * adb reverse. Takes a screenshot at every step into the app's files/screens.
 */
@RunWith(AndroidJUnit4::class)
class FlowTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val hub = args.getString("hub") ?: ""
    private val token = args.getString("token") ?: ""
    private val adminToken = args.getString("adminToken") ?: ""
    private val hub2 = args.getString("hub2") ?: ""
    private val token2 = args.getString("token2") ?: ""
    private var scenario: ActivityScenario<MainActivity>? = null
    private var step = 0

    @Before
    fun seed() {
        assumeTrue("hub and token come from scripts/ui-tests.sh", hub.isNotEmpty() && token.isNotEmpty())
        SettingsStore(context).save(Settings(hubs = listOf(Hub("test", "test", hub)), current = "test", refreshSeconds = 1))
        Vault(context).store("test", token)
        File(context.getExternalFilesDir(null), "screens").deleteRecursively()
    }

    @After
    fun close() {
        scenario?.close()
        Vault(context).clear("test")
        Vault(context).clear("two")
    }

    /** The second hub joins the list; its host is bob-server. */
    private fun seedSecondHub() {
        assumeTrue("hub2 and token2 come from scripts/ui-tests.sh", hub2.isNotEmpty() && token2.isNotEmpty())
        SettingsStore(context).save(Settings(hubs = listOf(Hub("test", "test", hub), Hub("two", "two", hub2)), current = "test", refreshSeconds = 1))
        Vault(context).store("two", token2)
    }

    private fun shot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "screens").also { it.mkdirs() }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "%02d-%s.png".format(++step, name)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun await(text: String, seconds: Long = 15): SemanticsNodeInteraction {
        rule.waitUntil(seconds * 1000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        return rule.onAllNodes(hasText(text, substring = true)).onFirst()
    }

    @Test
    fun listOpenTypeAndNeverShowTheToken() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0")
        shot("panes")
        rule.onAllNodes(hasText(token.substring(4, 20), substring = true)).assertCountEquals(0)

        await("work/0.0").performClick()
        await("⏎")
        await("$ ")
        shot("pane")

        rule.onNode(hasSetTextAction()).performTextInput("echo from android")
        rule.onNodeWithText("⏎").performClick()
        await("from android")
        shot("typed")

        // Two lines go as one paste, then Enter: bash runs both.
        rule.onNode(hasSetTextAction()).performTextInput("echo first-\$((1+1))\necho second-\$((2+2))")
        shot("two-lines")
        rule.onNodeWithText("⏎").performClick()
        await("first-2")
        await("second-4")

        rule.onNodeWithText("^C").performClick()
        await("┤ work/0.0 ├")
        shot("tabs")

        rule.onNodeWithText("scroll").performClick()
        await(" scroll ")
        shot("scroll-toggle")
        rule.onNodeWithText("scroll").performClick()

        rule.onNodeWithText("panes").performClick()
        await("work/0.0")
        shot("back")

        rule.onNodeWithText("settings").performClick()
        await("save and connect")
        shot("connect")
        rule.onAllNodes(hasText(token.substring(4, 20), substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("stored", substring = true)).onFirst()
        // The phone's token is no admin: Settings has no admin section.
        rule.onAllNodes(hasText("refresh")).assertCountEquals(0)
    }

    @Test
    fun theKeyboardTypesAndSendsChords() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        rule.onNodeWithTag("keyboard-open").performClick()
        rule.onNodeWithTag("keyboard").assertExists()
        rule.onNodeWithTag("keyboard-target").assert(hasText("work/0.0", substring = true))
        shot("keyboard")
        for (c in listOf("E", "C", "H", "O", "Space", "K", "B", "D", "Enter ⏎")) rule.onNodeWithTag("cap:$c").performClick().also { rule.waitForIdle() }
        await("kbd")
        // A latched Ctrl and L: the chord clears the screen.
        rule.onNodeWithTag("cap:Ctrl").performClick()
        rule.onNodeWithTag("cap:L").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("kbd", substring = true)).fetchSemanticsNodes().isEmpty() }
        shot("keyboard-chord")
        rule.onNodeWithTag("keyboard-close").performClick()
        rule.onAllNodesWithTag("keyboard").assertCountEquals(0)
    }

    @Test
    fun adminSectionOnlyForAnAdminToken() {
        assumeTrue("adminToken comes from scripts/ui-tests.sh", adminToken.isNotEmpty())
        Vault(context).store("test", adminToken)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0")
        rule.onNodeWithText("settings").performClick()
        await("save settings")
        await("agent connected")
        await("clients")
        shot("admin")
        rule.onAllNodes(hasText(adminToken.substring(4, 20), substring = true)).assertCountEquals(0)
        rule.onNodeWithText("refresh").performClick()
        await("agent connected")
    }

    @Test
    fun newPaneAndNewSessionFromTheList() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0")
        await("+ pane").performClick()
        await("⏎")
        await("$ ")
        shot("new-pane")
        rule.onNodeWithText("panes").performClick()
        await("work/0.1")

        await("+ session").performClick()
        await("cancel")
        rule.onNode(hasSetTextAction()).performTextInput("Scratch!")
        rule.onNodeWithText("start").performClick()
        await("! a-z")
        shot("new-session-dialog")
        rule.onNodeWithText("cancel").performClick()
        // One creation per two seconds per token.
        Thread.sleep(2100)
        await("+ session").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("scratch")
        rule.onNodeWithText("start").performClick()
        await("scratch/0.0")
        await("$ ")
        shot("new-session")
    }

    @Test
    fun theAddMenuAddsATileAndTheHeaderMinimisesIt() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        rule.onNodeWithTag("add").performClick()
        rule.onNodeWithTag("add-menu").assertExists()
        await("+ session")
        shot("add-menu")
        await("+ pane").performClick()
        // Two tiles: two headers with the mode, two `_`.
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 2 }
        shot("added")
        // Every pane of the window is open: the window fits as one, and no tile is held.
        await("write")
        Thread.sleep(2_000)
        rule.onAllNodes(hasText("held", substring = true)).assertCountEquals(0)
        shot("one-window")
        // ⊡ shows the focused pane alone, and once more brings both tiles back.
        rule.onNodeWithTag("zoom").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 1 }
        shot("zoomed")
        rule.onNodeWithTag("zoom").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithTag("minimise")[1].performClick()
        rule.onNodeWithTag("minimised").assertExists()
        rule.onAllNodesWithTag("minimise").assertCountEquals(1)
        shot("minimised")
        // The menu lists it as minimised; a tap on the row brings it back.
        rule.onNodeWithTag("add").performClick()
        await("minimised")
        Espresso.pressBack()
        rule.onNodeWithTag("minimised").onChildren().onLast().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithTag("minimised").assertCountEquals(0)
        shot("restored")
    }

    @Test
    fun theChangesSheetListsTheRepositoryAndOpensADiff() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        // The pane's shell is in a repository under the host's root: the header counts its changes.
        await("⎇ +1 ~1")
        shot("changes-count")
        rule.onNodeWithTag("changes-count").performClick()
        rule.onNodeWithTag("changes").assertExists()
        await("repo · main")
        await("notes.txt")
        shot("changes")
        rule.onNodeWithTag("change:src/a.go").performClick()
        await("+func A() {}")
        shot("diff")
        rule.onNodeWithTag("side:file").performClick()
        await("package a")
        Espresso.pressBack()
        Espresso.pressBack()
        rule.onAllNodesWithTag("changes").assertCountEquals(0)
    }

    @Test
    fun aCrossClosesTheTileAndATabHeldOpensItsPaneBeside() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        rule.onNodeWithTag("add").performClick()
        await("+ pane").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 2 }
        // The agent does not close panes here, so × closes the tile alone; the pane stays on the host.
        rule.onAllNodesWithTag("close")[1].performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag("tab:alice-laptop/work/0.1").assertExists()
        shot("tile-closed")
        // The second pane's tab, held, opens it beside the first.
        rule.onNodeWithTag("tab:alice-laptop/work/0.1").performTouchInput { longClick() }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 2 }
        shot("beside")
    }

    @Test
    fun everyHubIsListedAndOpensSideBySide() {
        seedSecondHub()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0")
        await("ops/0.0")
        rule.onNodeWithTag("hub:test").assertExists()
        rule.onNodeWithTag("hub:two").assertExists()
        shot("two-hubs")
        await("work/0.0").performTouchInput { longClick() }
        await("ops/0.0").performClick()
        await("open 2 side by side").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 2 }
        rule.onNode(hasSetTextAction()).performTextInput("echo from-two-hubs")
        rule.onNodeWithText("⏎").performClick()
        await("from-two-hubs")
        shot("two-hubs-side-by-side")
        rule.onNodeWithText("‹").performClick()
        await("ops/0.0")
        rule.onNodeWithText("settings").performClick()
        await("save and connect")
        rule.onAllNodes(hasText(token2.substring(4, 20), substring = true)).assertCountEquals(0)
    }
}
