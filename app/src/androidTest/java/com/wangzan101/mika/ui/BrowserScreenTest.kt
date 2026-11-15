package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.browser.AddressInput
import com.wangzan101.mika.browser.BrowserPage
import com.wangzan101.mika.browser.BrowserTab
import com.wangzan101.mika.browser.FavoriteSite
import com.wangzan101.mika.browser.PageBookmark
import com.wangzan101.mika.browser.FindState
import com.wangzan101.mika.browser.RecentItem
import com.wangzan101.mika.browser.RecentPage
import com.wangzan101.mika.browser.SearchEngine
import com.wangzan101.mika.browser.SiteLook
import com.wangzan101.mika.browser.SiteLooks
import com.wangzan101.mika.browser.StartPageModel
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 侧栏的「浏览器」入口;起始页的几段(搜索、常用网站、整理、最近打开),网页的顶栏和工具栏、「更多」面板、「给 Mika 登录」横幅。 */
@RunWith(AndroidJUnit4::class)
class BrowserScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val looks = mapOf("shop.example.com" to SiteLook("购物", 3, null), "example.org" to SiteLook("新闻", 3, null))
    private val face = { host: String -> SiteLooks.faceOf(host, looks) { null } }
    private val sites = listOf(FavoriteSite("shop.example.com", true), FavoriteSite("news.example.org", false))
    private val recent = StartPageModel.recentRows(
        listOf(
            RecentPage(AddressInput.search("周末去哪玩"), "", 3_000),
            RecentPage("https://docs.example.net/guide", "使用指南", 2_000),
        ),
        nameOf = { face(it).name },
    )

    private fun start() {
        compose.setContent {
            MikaTheme {
                BrowserStart(
                    sites, recent, face,
                    onBack = { calls += "back" },
                    onSubmit = { calls += "submit $it" },
                    onOpenUrl = { calls += "open $it" },
                    onOpenSite = { calls += "site ${it.host}" },
                    onRemoveSite = { calls += "remove ${it.host}" },
                    onAddHint = { calls += "add" },
                    tabsCount = 2,
                    onMenu = { calls += "menu" },
                    onHistory = { calls += "history" },
                    now = 3_000,
                )
            }
        }
    }

    @Test
    fun startPageShowsSearchSitesAndRecent() {
        start()
        compose.onNodeWithText(BROWSER_SEARCH_HINT).assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.browser_engine_note, SearchEngine.Bing.displayName)).assertIsDisplayed()
        compose.onNodeWithText("常用网站").assertIsDisplayed()
        compose.onNodeWithText(BROWSER_LOGIN_LEGEND).assertIsDisplayed()
        compose.onAllNodesWithTag("browser-site-logged-in", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText("最近打开").assertIsDisplayed()
        compose.onNodeWithText("搜索：周末去哪玩").assertIsDisplayed()
        compose.onNodeWithText("使用指南").performClick()
        compose.onNodeWithText("全部").performClick()
        compose.onNodeWithText("添加").performClick()
        assertEquals(listOf("open https://docs.example.net/guide", "history", "add"), calls)
    }

    @Test
    fun engineNoteOpensTheEnginePicker() {
        start()
        compose.onNodeWithTag("browser-engine").performClick()
        compose.onNodeWithTag("browser-engine-picker").assertIsDisplayed()
        compose.onNodeWithText("谷歌").assertIsDisplayed()
    }

    @Test
    fun typingShowsSuggestions() {
        start()
        compose.onNodeWithTag("browser-search").performTextInput("周末")
        compose.onNodeWithTag("browser-suggestions").assertIsDisplayed()
        compose.onNodeWithText("搜索「周末」").assertIsDisplayed()
        compose.onNodeWithText("搜索：周末去哪玩").performClick()
        assertEquals(listOf("open ${AddressInput.search("周末去哪玩")}"), calls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keyboardGoAndEnterSubmitTheSearch() {
        start()
        compose.onNodeWithTag("browser-search").performTextInput("weather")
        compose.onNodeWithTag("browser-search").performImeAction()
        compose.onNodeWithTag("browser-search").performTextInput("天气")
        compose.onNodeWithTag("browser-search").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("submit weather", "submit 天气"), calls)
    }

    @Test
    fun editModeRemovesAndFinishes() {
        start()
        compose.onNodeWithText("整理").performClick()
        compose.onNodeWithText("整理常用网站").assertIsDisplayed()
        compose.onNodeWithContentDescription("移除购物", useUnmergedTree = true).performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("浏览器").assertIsDisplayed()
        assertEquals("remove shop.example.com", calls.first())
    }

    @Test
    fun longPressEntersEditMode() {
        start()
        compose.onAllNodesWithTag("browser-sites")[0].performTouchInput { longClick(center.copy(x = width / 8f, y = 40f)) }
        compose.onNodeWithText("整理常用网站").assertIsDisplayed()
    }

    @Test
    fun pageBarAndToolbar() {
        val page = BrowserPage("https://shop.example.com/", "购物", 40, loading = true, canBack = false, canForward = false, desktop = true)
        compose.setContent {
            MikaTheme {
                Column {
                    BrowserPageBar(page, face("shop.example.com"), onClose = { calls += "close" }, onSubmit = {}, onReload = {}, onStop = { calls += "stop" })
                    BrowserToolbar(page, onBackPage = {}, onForward = {}, onHome = { calls += "home" }, onShare = { calls += "share" }, onMore = { calls += "more" })
                }
            }
        }
        compose.onNodeWithText(BROWSER_DESKTOP_TAG).assertIsDisplayed()
        compose.onNodeWithTag("browser-progress").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsNotEnabled()
        compose.onNodeWithContentDescription("停止").performClick()
        compose.onNodeWithContentDescription("起始页").performClick()
        compose.onNodeWithContentDescription("分享").performClick()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithTag("browser-address-pill").performClick()
        compose.onNodeWithText("取消").assertIsDisplayed()
        assertEquals(listOf("stop", "home", "share", "more"), calls)
    }

    @Test
    fun moreSheetOffersSiteActions() {
        compose.setContent {
            MikaTheme {
                BrowserMoreSheet(
                    face("shop.example.com"), "shop.example.com", favorite = false, desktop = false,
                    onDismiss = { calls += "dismiss" }, onReload = {}, onCopy = { calls += "copy" }, onOpenOutside = {},
                    onToggleFavorite = { calls += "fav" }, onToggleDesktop = { calls += "desktop" },
                )
            }
        }
        compose.onNodeWithText("复制链接").assertIsDisplayed()
        compose.onNodeWithText("在其他浏览器里打开").assertIsDisplayed()
        compose.onNodeWithText("加入常用网站").assertIsDisplayed()
        compose.onNodeWithText("这个网站下次也用电脑版").assertIsDisplayed()
        compose.onNodeWithText("换成电脑版").performClick()
        assertEquals(listOf("dismiss", "desktop"), calls)
    }

    @Test
    fun bookmarksSheetOpensInEditModeAndRemoves() {
        val items = listOf(PageBookmark("https://shop.example.com/item/1", "购物页", 1_000))
        compose.setContent {
            MikaTheme {
                BookmarksSheet(
                    items, face,
                    onDismiss = {}, onOpen = { calls += "open ${it.url}" },
                    onRemove = { calls += "remove ${it.url}" }, onRename = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("购物页").assertIsDisplayed()
        compose.onNodeWithText("整理").performClick()
        compose.onNodeWithContentDescription("移除购物页", useUnmergedTree = true).performClick()
        assertEquals(listOf("remove https://shop.example.com/item/1"), calls)
    }

    @Test
    fun tabsSheetSelectsClosesAndOpensNew() {
        compose.setContent {
            MikaTheme {
                TabsSheet(
                    listOf(BrowserTab("t1", "A", "https://a.example.com/", "a.example.com", active = true)),
                    onDismiss = {}, onSelect = { calls += "select $it" }, onClose = { calls += "close $it" }, onNew = { calls += "new" },
                )
            }
        }
        compose.onNodeWithText("A").performClick()
        compose.onNodeWithContentDescription("关闭标签页", useUnmergedTree = true).performClick()
        compose.onNodeWithText("新建标签页").performClick()
        assertEquals(listOf("select t1", "close t1", "new"), calls)
    }

    @Test
    fun historySheetRemovesInEditMode() {
        val pages = listOf(RecentItem("https://a.example.com/", "A", "a.example.com", null, false, 1_000))
        compose.setContent {
            MikaTheme {
                HistorySheet(
                    pages, now = 2_000, onDismiss = {}, onOpen = { calls += "open ${it.url}" },
                    onRemove = { calls += "remove ${it.url}" }, onClear = { calls += "clear" },
                )
            }
        }
        compose.onNodeWithText("整理").performClick()
        compose.onNodeWithContentDescription("移除A", useUnmergedTree = true).performClick()
        assertEquals(listOf("remove https://a.example.com/"), calls)
    }

    @Test
    fun clearDataSheetClearsTheSelectedKinds() {
        compose.setContent { MikaTheme { ClearDataSheet({}) { h, s, c -> calls += "clear $h $s $c" } } }
        compose.onNodeWithText("清除").performClick()
        assertEquals(listOf("clear true true true"), calls)
    }

    @Test
    fun findBarShowsTheCurrentMatch() {
        compose.setContent { MikaTheme { BrowserFindBar(FindState(active = true, query = "a", matches = 3, index = 2), {}, {}, {}, {}) } }
        compose.onNodeWithText("2/3").assertIsDisplayed()
    }

    @Test
    fun loginBannerConfirms() {
        compose.setContent { MikaTheme { LoginBanner(desktop = true, onDone = { calls += "done" }) } }
        compose.onNodeWithTag("browser-login-banner").assertIsDisplayed()
        compose.onNodeWithText(BROWSER_LOGIN_DONE).performClick()
        assertEquals(listOf("done"), calls)
    }

    @Test
    fun siteTileFallsBackToInitial() {
        compose.setContent { MikaTheme { SiteTile(face("news.example.org"), 54.dp, RoundedCornerShape(16.dp), MikaType.Title) } }
        compose.onNodeWithText("新").assertIsDisplayed()
    }
}
