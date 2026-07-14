package com.drawlesschess.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drawlesschess.R
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.NamedBotLevel

internal data class OpponentProfile(
    val level: NamedBotLevel,
    val name: String,
    val epithet: String,
    val personality: String,
    val portraitRes: Int,
)

internal object OpponentProfiles {
    val all: List<OpponentProfile> = listOf(
        OpponentProfile(
            level = BotDifficultyCatalog.named("learner"),
            name = "Mira",
            epithet = "Curious newcomer",
            personality = "Bright and fearless, Mira is happy to try any idea once.",
            portraitRes = R.drawable.opponent_learner,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("casual"),
            name = "Theo",
            epithet = "Easygoing regular",
            personality = "Warm and observant, Theo enjoys a clever move and never takes a loss personally.",
            portraitRes = R.drawable.opponent_casual,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("challenger"),
            name = "Rhea",
            epithet = "Playful competitor",
            personality = "Rhea meets every position like a dare—and loves when you push back.",
            portraitRes = R.drawable.opponent_challenger,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("club"),
            name = "Mateo",
            epithet = "Club storyteller",
            personality = "Patient and good-humored, Mateo always has a story ready after the game.",
            portraitRes = R.drawable.opponent_club,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("expert"),
            name = "Yuna",
            epithet = "Quiet analyst",
            personality = "Precise and dryly funny, Yuna lets the board do most of the talking.",
            portraitRes = R.drawable.opponent_expert,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("master"),
            name = "Amara",
            epithet = "Unshakable strategist",
            personality = "Disciplined, gracious, and completely at home under pressure.",
            portraitRes = R.drawable.opponent_master,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("grandmaster"),
            name = "Lucian",
            epithet = "Courteous grandmaster",
            personality = "Sparse with words, generous in victory, and focused from the first move.",
            portraitRes = R.drawable.opponent_grandmaster,
        ),
    )

    private val byLevelId = all.associateBy { it.level.id }
    val quickPlay: OpponentProfile get() = byLevelId.getValue("casual")

    init {
        check(all.map { it.level.id } == BotDifficultyCatalog.namedLevels.map { it.id }) {
            "Every named bot level must have exactly one opponent profile"
        }
        check(byLevelId.size == all.size) { "Opponent profile level ids must be unique" }
    }

    fun forLevel(level: NamedBotLevel): OpponentProfile =
        byLevelId[level.id] ?: error("No opponent profile for level '${level.id}'")
}

@Composable
internal fun OpponentPortrait(
    profile: OpponentProfile,
    size: Dp,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    thinking: Boolean = false,
) {
    val ringColor = if (emphasized || thinking) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier = modifier
            .size(size)
            .border(3.dp, ringColor, CircleShape)
            .padding(3.dp),
    ) {
        Image(
            painter = painterResource(profile.portraitRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
        )
        if (thinking) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(15.dp)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}
