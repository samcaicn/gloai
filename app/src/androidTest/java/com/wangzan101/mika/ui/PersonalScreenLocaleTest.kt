package com.wangzan101.mika.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import com.wangzan101.mika.memory.MemoryRuntime
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import com.wangzan101.mika.personal.ui.PersonalProfileDialog
import com.wangzan101.mika.personal.ui.PersonalProfileTab
import com.wangzan101.mika.personal.ui.PersonalProfileTabs
import com.wangzan101.mika.personal.ui.personalDayFormat

@RunWith(AndroidJUnit4::class)
class PersonalScreenLocaleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun profileTabsShowFullLabelsAndRemainSelectableAtLargeFontScale() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = mutableStateOf(Locale("pt"))
        val selected = mutableStateOf(PersonalProfileTab.Tasks)
        compose.setContent {
            val configuration = Configuration(base.resources.configuration).apply { setLocale(locale.value) }
            val localized = base.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MikaTheme {
                    androidx.compose.foundation.layout.Box(Modifier.width(240.dp)) {
                        PersonalProfileTabs(selected.value, showIcons = false) { selected.value = it }
                    }
                }
            }
        }
        for ((language, labels) in listOf(
            Locale("pt") to listOf("Tarefas", "Memória", "Habilidades"),
            Locale("es") to listOf("Tareas", "Memoria", "Habilidades"),
        )) {
            compose.runOnIdle { locale.value = language }
            labels.forEachIndexed { index, label ->
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertTrue("Missing layout for $language: $label", results.isNotEmpty())
                val layout = results.single()
                // The semantics layout reports overflow against the constraint width, so compare the full text width to the drawn width instead.
                val needed = kotlin.math.ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
                assertTrue("Clipped label for $language: $label needs $needed px, has ${layout.size.width} px",
                    needed <= layout.size.width)
                compose.onNodeWithText(label).performClick()
                compose.runOnIdle { assertEquals(PersonalProfileTab.entries[index], selected.value) }
            }
        }
    }

    @Test fun fullProfileDialogCanScrollToContentAtLargeFontScale() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = MemoryRuntime(base)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MikaTheme {
                    PersonalProfileDialog(emptyList(), memory, onDismiss = {}, onTask = {})
                }
            }
        }
        compose.onNodeWithText(base.getString(com.wangzan101.mika.R.string.personal_tab_skills))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(base.getString(com.wangzan101.mika.R.string.personal_no_tasks))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun profileTabsStayOnOneRowWhenTheyFit() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                MikaTheme {
                    androidx.compose.foundation.layout.Box(Modifier.width(360.dp)) {
                        PersonalProfileTabs(PersonalProfileTab.Tasks, showIcons = true) {}
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tops = listOf(com.wangzan101.mika.R.string.personal_tab_tasks, com.wangzan101.mika.R.string.personal_tab_memory,
            com.wangzan101.mika.R.string.personal_tab_skills).map {
            compose.onNodeWithText(context.getString(it)).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(1, tops.distinct().size)
    }

    @Test fun dateFollowsConfigurationWhenLanguageChanges() {
        val date = LocalDate.of(2026, 11, 12)
        val english = Locale.US
        val chinese = Locale.SIMPLIFIED_CHINESE
        fun configuration(locale: Locale) = Configuration().apply { setLocale(locale) }
        fun expected(locale: Locale) = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale).format(date)
        val current = mutableStateOf(configuration(english))
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides current.value) {
                Text(personalDayFormat().format(date))
            }
        }
        compose.onNodeWithText(expected(english)).assertExists()
        compose.runOnIdle { current.value = configuration(chinese) }
        compose.onNodeWithText(expected(chinese)).assertExists()
        compose.onNodeWithText(expected(english)).assertDoesNotExist()
    }
}
