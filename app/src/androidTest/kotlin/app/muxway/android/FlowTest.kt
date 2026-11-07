package app.muxway.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxway.android.store.Hub
import app.muxway.android.store.Settings
import app.muxway.android.store.SettingsStore
import app.muxway.android.store.Vault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // A test that ended in a view would start the next on the desk: every test starts on the list.
        SettingsStore(context).saveLastPlace("list")
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

    /** A node's bounds on the screen, for a touch the system sees. */
    private fun onScreen(tag: String): Rect {
        val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val xy = IntArray(2)
        scenario!!.onActivity { it.window.decorView.getLocationOnScreen(xy) }
        return b.translate(xy[0].toFloat(), xy[1].toFloat())
    }

    /**
     * A drag as the window manager sees it, so a drop lands: down, a long press, moves, up,
     * injected through UiAutomation, since the rule's touches never leave the view and the
     * drag's window is the system's.
     */
    private fun dragOnScreen(from: Offset, to: Offset) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, at: Offset) {
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, at.x, at.y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(e, true)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 400L)
        for (i in 1..12) {
            send(MotionEvent.ACTION_MOVE, from + (to - from) * (i / 12f))
            SystemClock.sleep(40)
        }
        send(MotionEvent.ACTION_UP, to)
        rule.waitForIdle()
    }

    private fun tiles(): Int = rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size

    /** Where a tile's top is in the window, or nothing while it is not there. */
    private fun topOf(tag: String): Float? = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.top

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
        // The second pane's tab, held and let go in place, opens its menu; *open beside* opens it beside the first.
        rule.onNodeWithTag("tab:alice-laptop/work/0.1").performTouchInput { longClick() }
        rule.onNodeWithTag("pane-menu").assertExists()
        shot("pane-menu")
        rule.onNodeWithTag("menu-beside").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 2 }
        shot("beside")
    }

    /** The left edge of a tab in the strip, or null while it is not on screen. */
    private fun leftOf(tag: String): Float? = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.left

    @Test
    fun theHeaderHeldParksThePaneForLaterAndItsTabTappedWakesIt() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        val work = "tab:alice-laptop/work/0.0"
        val ask = "tab:alice-laptop/ask/0.0"
        rule.waitUntil(15_000) { val w = leftOf(work); val a = leftOf(ask); w != null && a != null && w < a }
        // The header held and let go in place opens the pane menu; *later* parks the pane on the host.
        rule.onNodeWithTag("header").performTouchInput { longClick() }
        rule.onNodeWithTag("menu-later").assertExists()
        rule.onNodeWithTag("menu-later").performClick()
        await("later")
        // The tab goes after every other with the moon; the header says later and nothing pulses.
        rule.waitUntil(15_000) { val w = leftOf(work); val a = leftOf(ask); w != null && a != null && w > a }
        rule.onNodeWithTag(work).assert(hasAnyDescendant(hasText("☾")))
        shot("later-tab")
        // Hold again: the menu now offers wake. Back closes it.
        rule.onNodeWithTag("header").performTouchInput { longClick() }
        await("☾ wake")
        Espresso.pressBack()
        // The parked tab, tapped, wakes the pane: the word goes and the tab takes its place back.
        rule.onNodeWithTag(work).performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("later", substring = true)).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(15_000) { val w = leftOf(work); val a = leftOf(ask); w != null && a != null && w < a }
        rule.onAllNodesWithTag("name-sheet").assertCountEquals(0)
        shot("woken")
    }

    /** A node whose test tag starts with [prefix]. */
    private fun tagged(prefix: String) = SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    @Test
    fun theEnvelopeOpensTheMailSheetAndMailBackAnswersTheSender() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("ask/0.0")
        // The seeded mail from the first pane counts on the asking pane's row; the envelope alone opens the sheet.
        await("✉ 1")
        rule.onNode(hasClickAction() and hasParent(hasText("✉ 1", substring = true)), useUnmergedTree = true).performClick()
        rule.onNodeWithTag("mail-sheet").assertExists()
        await("read the branch and say what fails")
        rule.onNodeWithTag("mail-unread").assertExists()
        shot("mail-sheet")
        // mail back aims the line at the sender; the sent mail appears as → at the bottom, nothing typed.
        rule.onNodeWithTag("mail-back").performClick()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("mail-sheet"))).performTextInput("tests green")
        rule.onNodeWithTag("mail-send").performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(tagged("mail-row:out:")).fetchSemanticsNodes().isNotEmpty() }
        await("tests green")
        shot("mail-back")
        Espresso.pressBack()
        rule.onAllNodesWithTag("mail-sheet").assertCountEquals(0)
        // The header's envelope opens the messages sheet; a mail row there opens the sheet on that mail.
        rule.onNodeWithTag("messages-count").performClick()
        rule.onNodeWithTag("messages").assertExists()
        await("read the branch")
        shot("messages")
        rule.onAllNodes(tagged("talk-row:")).onFirst().performClick()
        rule.onNodeWithTag("mail-sheet").assertExists()
        await("read the branch and say what fails")
    }

    @Test
    fun theShareSheetMakesALinkOnceCountsItOnTheHeaderAndRevokesIt() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        // The header held opens the pane menu; share opens the sheet, not shared yet.
        rule.onNodeWithTag("header").performTouchInput { longClick() }
        rule.onNodeWithTag("menu-share").performClick()
        rule.onNodeWithTag("share-sheet").assertExists()
        await("not shared")
        shot("share-sheet")
        // create: the link, the password once, the code.
        rule.onNodeWithTag("share-create").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("share-url").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("share-url").assert(hasText("/s/", substring = true))
        val password = rule.onNodeWithTag("share-password").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(password.length >= 8)
        await("until")
        shot("share-made")
        // done: the password is nowhere; the header counts the share; revoke ends it.
        rule.onNodeWithTag("share-done").performClick()
        rule.onAllNodesWithTag("share-password").assertCountEquals(0)
        rule.onAllNodes(hasText(password, substring = true)).assertCountEquals(0)
        Espresso.pressBack()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("share-count").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("share-count").assert(hasText("⇗ 1", substring = true))
        rule.onNodeWithTag("share-count").performClick()
        rule.onNodeWithTag("share-revoke").performClick()
        await("not shared")
        Espresso.pressBack()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("share-count").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aTabDroppedOnATileLaysItsPaneOut() {
        seedSecondHub()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        val tab = "tab:bob-server/ops/0.0"
        val work = "tile:alice-laptop/work/0.0"
        val ops = "tile:bob-server/ops/0.0"
        rule.onNodeWithTag(tab).assertExists()
        // The other hub's tab dropped on the right part of the one tile: two tiles side by side,
        // the dropped pane on the right.
        var t = onScreen(work)
        dragOnScreen(onScreen(tab).center, Offset(t.left + t.width * 0.9f, t.center.y))
        rule.waitUntil(15_000) { tiles() == 2 }
        assertTrue(onScreen(ops).left > onScreen(work).left)
        assertEquals(onScreen(work).top, onScreen(ops).top, 1f)
        shot("tab-right")
        // The second tile closed; the tab dropped on the lower part: stacked, the dropped pane below.
        rule.onAllNodesWithTag("close")[1].performClick()
        rule.waitUntil(5_000) { tiles() == 1 }
        t = onScreen(work)
        dragOnScreen(onScreen(tab).center, Offset(t.center.x, t.top + t.height * 0.9f))
        rule.waitUntil(15_000) { tiles() == 2 }
        assertTrue(onScreen(ops).top > onScreen(work).top)
        shot("tab-below")
        // The lower tile's tab dropped on the upper tile's middle: the two swap.
        dragOnScreen(onScreen(tab).center, onScreen(work).center)
        rule.waitUntil(5_000) { val o = topOf(ops); val w = topOf(work); o != null && w != null && o < w }
        assertEquals(2, tiles())
        shot("tab-swapped")
    }

    @Test
    fun theFocusedTabTappedNamesThePaneAndTheHeaderShowsTheFolder() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        // The pane's shell runs in the fixture's repository under a preview root: the header shows the folder.
        await("· repo")
        // The focused tab, tapped once more, opens the name sheet with the whole path.
        rule.onNodeWithTag("tab:alice-laptop/work/0.0").performClick()
        rule.onNodeWithTag("name-sheet").assertExists()
        await("/work/repo")
        shot("name-sheet")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("name-sheet"))).performTextInput("Fix Tests")
        await("alice-laptop/@fix-tests")
        rule.onNodeWithTag("name-save").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("name-sheet").fetchSemanticsNodes().isEmpty() }
        await("fix-tests")
        rule.onNodeWithTag("tab:alice-laptop/work/0.0").assert(hasAnyDescendant(hasText("fix-tests", substring = true)))
        shot("named")
        // The sheet again, from the header's name this time; clear takes the name away.
        rule.onNodeWithTag("name").performClick()
        rule.onNodeWithTag("name-clear").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("name-sheet").fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("fix-tests", substring = true)).fetchSemanticsNodes().isEmpty() }
        shot("name-cleared")
    }

    @Test
    fun aQuestionsChoicesAreAnsweredFromTheTileAndTheInboxListsWhatWaits() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0")
        // The shell dressed as Codex is named, with its mark, and the asking pane counts in the header.
        await("Codex")
        shot("codex-mark")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("inbox-count").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("inbox-count").performClick()
        rule.onNodeWithTag("inbox").assertExists()
        await("deploy to staging?")
        rule.onNodeWithTag("choice:yes").assertExists()
        shot("inbox")
        rule.onNodeWithTag("inbox-row:alice-laptop/ask/0.0").performClick()
        await("⏎")
        await("question")
        rule.onNodeWithTag("choice:yes").assertExists()
        shot("choices")
        // The answer goes to the asking process: the attention leaves, nothing is typed.
        rule.onNodeWithTag("choice:yes").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("choice:yes").fetchSemanticsNodes().isEmpty() }
        shot("answered")
        rule.onAllNodes(hasText("yes", substring = false)).assertCountEquals(0)
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

    /** A tap, or with [hold] a long press, on the [col]th cell of the last row on the screen that holds [name]. */
    private fun tapCell(name: String, col: Int, hold: Boolean = false) {
        val node = rule.onAllNodes(hasText(name, substring = true)).onLast()
        val text = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: name
        node.performTouchInput {
            val at = Offset(width * (col + 0.5f) / text.length.coerceAtLeast(1), height / 2f)
            if (hold) longClick(at) else click(at)
        }
    }

    @Test
    fun aNameOfTheDirectoryIsALinkThatEntersItOrOpensTheFile() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        rule.onNode(hasSetTextAction()).performTextInput("ls -1")
        rule.onNodeWithText("⏎").performClick()
        await("notes.txt")
        // The listing arrives within the tile's two seconds; then the names are links.
        SystemClock.sleep(3_000)
        shot("names")
        // Held, the quoted name lands on the line with a space after it, nothing is sent.
        tapCell("notes.txt", 1, hold = true)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("'notes.txt' ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("names-held")
        rule.onNode(hasSetTextAction()).performTextClearance()
        tapCell("src", 1)
        rule.onNode(hasSetTextAction()).performTextInput("pwd")
        rule.onNodeWithText("⏎").performClick()
        await("/repo/src")
        shot("names-cd")
        // A file opens in the sheet by default: nothing is typed.
        rule.onNode(hasSetTextAction()).performTextInput("ls -1")
        rule.onNodeWithText("⏎").performClick()
        await("a.go")
        SystemClock.sleep(3_000)
        tapCell("a.go", 1)
        await("func A()")
        shot("names-file")
        Espresso.pressBack()
        rule.onAllNodes(hasText("cat ", substring = true)).assertCountEquals(0)
    }

    @Test
    fun aPrintedPathOnlyAWorktreeHoldsOpensAndTheSheetSaysWhereItWasFound() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        // From the repository's top, whatever an earlier step left the shell in.
        rule.onNode(hasSetTextAction()).performTextInput("cd \"$(git rev-parse --show-toplevel)\" && clear")
        rule.onNodeWithText("⏎").performClick()
        await("$ ")
        rule.onNode(hasSetTextAction()).performTextInput("echo docs/side.md")
        rule.onNodeWithText("⏎").performClick()
        await("docs/side.md")
        // The file lives only in the worktree beside the checkout: the host finds it there and the sheet says so.
        tapCell("docs/side.md", 1)
        await("plan from the side")
        rule.onNodeWithTag("found").assert(hasText("/side/docs/side.md", substring = true))
        shot("file-found")
        Espresso.pressBack()
        // The pane's own file opens as before, with no such row.
        rule.onNode(hasSetTextAction()).performTextInput("echo src/a.go")
        rule.onNodeWithText("⏎").performClick()
        await("src/a.go")
        tapCell("src/a.go", 1)
        await("func A()")
        rule.onAllNodesWithTag("found").assertCountEquals(0)
        Espresso.pressBack()
    }

    /** A pixel of a tab's ground: just left of its content, inside its padding. */
    private fun groundOf(tag: String): Int {
        val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return rule.onRoot().captureToImage().asAndroidBitmap().getPixel((b.left - 4).toInt(), b.center.y.toInt())
    }

    private fun apart(a: Int, b: Int): Int = maxOf(
        Math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
        Math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)),
        Math.abs((a and 0xff) - (b and 0xff)),
    )

    @Test
    fun aWorkingPaneIsPlainWithItsRingAndAWaitingOnePainted() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // The fixture keeps the Codex pane working; its row says so, unpainted, in the list.
        await("working")
        await("Codex").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("tab:alice-laptop/codex/0.0").fetchSemanticsNodes().isNotEmpty() }
        // The header: the ring is drawn, the word is text.
        await("working")
        rule.onNodeWithTag("tab:alice-laptop/ask/0.0").assertExists()
        shot("working-plain")
        // The working tab has the ground of a quiet one; the waiting tab is the one painted.
        val quiet = groundOf("tab:alice-laptop/work/0.0")
        assertTrue("a working tab is not painted", apart(groundOf("tab:alice-laptop/codex/0.0"), quiet) < 16)
        assertTrue("a waiting tab is painted", apart(groundOf("tab:alice-laptop/ask/0.0"), quiet) > 64)
    }

    @Test
    fun theGhostWordGoesOnFromALineSentBeforeAndATapTakesIt() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        rule.onNode(hasSetTextAction()).performTextInput("echo one two")
        rule.onNodeWithText("⏎").performClick()
        await("one two")
        // The rest of the line sent before is drawn after the text; nothing is sent.
        rule.onNode(hasSetTextAction()).performTextInput("echo o")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("ghost").fetchSemanticsNodes().isNotEmpty() }
        shot("ghost")
        rule.onNodeWithTag("ghost").performClick()
        rule.onNode(hasSetTextAction()).assert(hasText("echo one two"))
        rule.onAllNodesWithTag("ghost").assertCountEquals(0)
        rule.onNodeWithText("⏎").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("one two", substring = true)).fetchSemanticsNodes().size >= 2 }
        shot("ghost-taken")
        // Forget empties the pool: the command row's recent line is gone, and so is the ghost.
        rule.onNodeWithText("‹").performClick()
        await("work/0.0")
        rule.onNodeWithText("settings").performClick()
        await("save settings")
        rule.onNodeWithTag("forget").performClick()
        shot("forget")
        rule.onNodeWithText("panes").performClick()
        await("work/0.0").performClick()
        await("$ ")
        rule.onAllNodes(hasText("echo one two", substring = false)).assertCountEquals(0)
        rule.onNode(hasSetTextAction()).performTextInput("echo o")
        SystemClock.sleep(1_500)
        rule.onAllNodesWithTag("ghost").assertCountEquals(0)
    }

    @Test
    fun thePartnerIsLaidBesideWithTheChainFitIsPerTileAndHelpListsTheGestures() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // The seeded mail made the first pane and the asking one a group.
        await("work/0.0").performClick()
        await("$ ")
        rule.onNodeWithTag("header").performTouchInput { longClick() }
        rule.onNodeWithTag("pane-menu").assertExists()
        shot("pane-menu-partner")
        rule.onNodeWithTag("menu-partner:alice-laptop/ask/0.0").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("minimise").fetchSemanticsNodes().size == 2 }
        // Two grouped tiles side by side: the chain sits on the divider between them.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("divider-chain").fetchSemanticsNodes().isNotEmpty() }
        shot("beside-chain")
        // Fit per tile: ⤢ held turns the focused tile's fit off and its size leaves the header; the other keeps its size.
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("size").fetchSemanticsNodes().size == 2 }
        rule.onNodeWithText("⤢").performTouchInput { longClick() }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("size").fetchSemanticsNodes().size == 1 }
        shot("fit-per-tile")
        rule.onNodeWithText("⤢").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("size").fetchSemanticsNodes().size == 2 }
        // Across the width from the header's menu: the focused tile takes the whole width.
        val before = rule.onAllNodesWithTag("header").fetchSemanticsNodes()[0].boundsInWindow.width
        rule.onAllNodesWithTag("header")[0].performTouchInput { longClick() }
        rule.onNodeWithTag("menu-across-width").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("header").fetchSemanticsNodes()[0].boundsInWindow.width > before * 1.5f }
        shot("across")
        // help from Settings lists the gestures.
        rule.onNodeWithText("‹").performClick()
        await("work/0.0")
        rule.onNodeWithText("settings").performClick()
        await("save settings")
        rule.onNodeWithTag("help").performClick()
        rule.onNodeWithTag("help-sheet").assertExists()
        await("lay the pane a tile talks with beside it")
        shot("help-sheet")
    }

    @Test
    fun aTileFollowsItsPaneWhenTheHostRenumbersAndClosesWithAWordWhenItIsGone() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0")
        // A session of this test's own, so the shared panes are left as they are.
        await("+ session").performClick()
        await("cancel")
        rule.onNode(hasSetTextAction()).performTextInput("own")
        rule.onNodeWithText("start").performClick()
        // The tile warms up until its first frame is in, then the frame comes up and the warm-up goes.
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("warmup").fetchSemanticsNodes().isNotEmpty() }
        shot("own-pane-warmup")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("warmup").fetchSemanticsNodes().isEmpty() }
        await("$ ")
        rule.onNodeWithTag("add").performClick()
        await("+ pane").performClick()
        rule.waitUntil(15_000) { tiles() == 2 }
        rule.onNodeWithTag("tile:alice-laptop/own/0.1").assertExists()
        shot("own-pane-split")
        // The second tile is focused once it is up: a line typed into it marks its screen.
        rule.onNode(hasSetTextAction()).performTextInput("echo second-pane")
        rule.onNodeWithText("⏎").performClick()
        await("second-pane")
        // The first pane exits: the host renumbers the second to its address. The first's tile
        // goes with the word, the second's follows its pane, its screen its own.
        rule.onNodeWithTag("tile:alice-laptop/own/0.0").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("exit")
        rule.onNodeWithText("⏎").performClick()
        rule.waitUntil(15_000) { tiles() == 1 }
        await("is gone")
        rule.onNodeWithTag("tile:alice-laptop/own/0.0").assertExists()
        rule.onAllNodesWithTag("tile:alice-laptop/own/0.1").assertCountEquals(0)
        await("second-pane")
        shot("own-pane-moved")
        // The last pane exits too: the view ends on the list, with the word.
        rule.onNode(hasSetTextAction()).performTextInput("exit")
        rule.onNodeWithText("⏎").performClick()
        await("tap to open")
        await("is gone")
        shot("own-pane-gone")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun thePaneChordsOfAHardwareKeyboardMoveBetweenTheTilesAndNeverReachThePane() {
        // A hardware keyboard assumed: the keys go to the pane while the line is not focused.
        SettingsStore(context).save(SettingsStore(context).load().copy(hardwareKeyboard = true))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        rule.onNodeWithTag("add").performClick()
        await("+ pane").performClick()
        rule.waitUntil(15_000) { tiles() == 2 }
        // The new tile is focused; the line's hint names the focused pane.
        await("direct")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("work/0.1").fetchSemanticsNodes().isNotEmpty() }
        // Ctrl+] comes round to the first tile, Ctrl+] again to the second, Ctrl+[ back to the first.
        rule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.RightBracket) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("work/0.0").fetchSemanticsNodes().isNotEmpty() }
        shot("chord-next")
        rule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.RightBracket) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("work/0.1").fetchSemanticsNodes().isNotEmpty() }
        rule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.LeftBracket) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("work/0.0").fetchSemanticsNodes().isNotEmpty() }
        // Direct typing stayed on: a word typed lands in the focused pane, and no chord did.
        rule.onRoot().performKeyInput { pressKey(Key.E); pressKey(Key.C); pressKey(Key.H); pressKey(Key.O); pressKey(Key.Spacebar); pressKey(Key.C); pressKey(Key.H); pressKey(Key.O); pressKey(Key.R); pressKey(Key.D); pressKey(Key.Enter) }
        await("chord")
        rule.onNode(hasTestTag("tile:alice-laptop/work/0.0") and hasAnyDescendant(hasText("chord", substring = true))).assertExists()
        rule.onAllNodes(hasText("^]", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("^[", substring = true)).assertCountEquals(0)
        shot("chord-typed")
        // From the line the chord moves the pane too, and the line keeps the focus.
        rule.onNode(hasSetTextAction()).performClick()
        rule.onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.RightBracket) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("work/0.1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).assertIsFocused()
        shot("chord-from-line")
    }

    /** A desk written through the hub by the test, as another client would; the status. */
    private fun putDesk(body: String): Int {
        val c = java.net.URL("$hub/v1/desk").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.responseCode
    }

    @Test
    fun theDeskIsWrittenOpenedFromItsRowAndFollowed() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // The row is there, whatever an earlier test left on the desk.
        await("▤ ")
        rule.onNodeWithTag("desk-row").assertExists()
        shot("desk-row-at-start")
        await("work/0.0").performClick()
        await("$ ")
        rule.onNodeWithTag("add").performClick()
        await("+ pane").performClick()
        rule.waitUntil(15_000) { tiles() == 2 }
        // Half a second after the change the view is the desk: the row names it.
        Thread.sleep(1_500)
        rule.onNodeWithText("panes").performClick()
        await("desk · 2 panes · column")
        shot("desk-row")
        rule.onNodeWithTag("desk-row").performClick()
        rule.waitUntil(15_000) { tiles() == 2 }
        shot("desk-opened")
        // Another client leaves one pane on the desk: the view follows within the second, and the
        // view does not write it back (it shows what the hub has).
        assertEquals(200, putDesk("""{"view":"panes","panes":["alice-laptop/work/0.0"],"focused":"alice-laptop/work/0.0"}"""))
        rule.waitUntil(15_000) { tiles() == 1 }
        rule.onNodeWithTag("tile:alice-laptop/work/0.0").assertExists()
        shot("desk-followed")
        // Started again on the list after a view: the desk opens by itself.
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { tiles() == 1 }
        rule.onNodeWithTag("tile:alice-laptop/work/0.0").assertExists()
        shot("desk-at-start")
        rule.onNodeWithText("panes").performClick()
        await("desk · 1 pane")
    }

    @Test
    fun theGhostWordIsOffBySetting() {
        SettingsStore(context).save(SettingsStore(context).load().copy(suggest = false))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("work/0.0").performClick()
        await("$ ")
        rule.onNode(hasSetTextAction()).performTextInput("git st")
        SystemClock.sleep(1_500)
        rule.onAllNodesWithTag("ghost").assertCountEquals(0)
        shot("ghost-off")
    }
}
