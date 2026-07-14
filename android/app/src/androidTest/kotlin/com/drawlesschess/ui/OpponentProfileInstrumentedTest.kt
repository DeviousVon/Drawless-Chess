package com.drawlesschess.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.drawlesschess.core.engine.BotDifficultyCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OpponentProfileInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyDifficultyHasOneDistinctCharacterAndRenderablePortrait() {
        assertEquals(
            BotDifficultyCatalog.namedLevels.map { it.id },
            OpponentProfiles.all.map { it.level.id },
        )
        assertEquals(OpponentProfiles.all.size, OpponentProfiles.all.map { it.name }.toSet().size)
        assertEquals(OpponentProfiles.all.size, OpponentProfiles.all.map { it.portraitRes }.toSet().size)

        compose.setContent {
            DrawlessTheme {
                Row {
                    OpponentProfiles.all.forEach { profile ->
                        OpponentPortrait(
                            profile = profile,
                            size = 40.dp,
                            modifier = Modifier.testTag("portrait_fixture_${profile.level.id}"),
                        )
                    }
                }
            }
        }

        OpponentProfiles.all.forEach { profile ->
            compose.onNodeWithTag("portrait_fixture_${profile.level.id}").fetchSemanticsNode()
        }
    }
}
