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
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.drawlesschess.R
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.NamedBotLevel

internal data class OpponentProfile(
    val level: NamedBotLevel,
    val name: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val epithetRes: Int,
    @param:StringRes val personalityRes: Int,
    val portraitRes: Int,
)

internal object OpponentProfiles {
    val all: List<OpponentProfile> = listOf(
        OpponentProfile(
            level = BotDifficultyCatalog.named("learner"),
            name = "Mira",
            nameRes = R.string.opponent_mira_name,
            epithetRes = R.string.opponent_mira_epithet,
            personalityRes = R.string.opponent_mira_personality,
            portraitRes = R.drawable.opponent_learner,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("casual"),
            name = "Theo",
            nameRes = R.string.opponent_theo_name,
            epithetRes = R.string.opponent_theo_epithet,
            personalityRes = R.string.opponent_theo_personality,
            portraitRes = R.drawable.opponent_casual,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("challenger"),
            name = "Rhea",
            nameRes = R.string.opponent_rhea_name,
            epithetRes = R.string.opponent_rhea_epithet,
            personalityRes = R.string.opponent_rhea_personality,
            portraitRes = R.drawable.opponent_challenger,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("club"),
            name = "Mateo",
            nameRes = R.string.opponent_mateo_name,
            epithetRes = R.string.opponent_mateo_epithet,
            personalityRes = R.string.opponent_mateo_personality,
            portraitRes = R.drawable.opponent_club,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("expert"),
            name = "Yuna",
            nameRes = R.string.opponent_yuna_name,
            epithetRes = R.string.opponent_yuna_epithet,
            personalityRes = R.string.opponent_yuna_personality,
            portraitRes = R.drawable.opponent_expert,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("master"),
            name = "Amara",
            nameRes = R.string.opponent_amara_name,
            epithetRes = R.string.opponent_amara_epithet,
            personalityRes = R.string.opponent_amara_personality,
            portraitRes = R.drawable.opponent_master,
        ),
        OpponentProfile(
            level = BotDifficultyCatalog.named("grandmaster"),
            name = "Lucian",
            nameRes = R.string.opponent_lucian_name,
            epithetRes = R.string.opponent_lucian_epithet,
            personalityRes = R.string.opponent_lucian_personality,
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
internal fun opponentName(profile: OpponentProfile): String = stringResource(profile.nameRes)

@Composable
internal fun botLevelName(level: NamedBotLevel): String = stringResource(
    when (level.id) {
        "learner" -> R.string.difficulty_learner
        "casual" -> R.string.difficulty_casual
        "challenger" -> R.string.difficulty_challenger
        "club" -> R.string.difficulty_club
        "expert" -> R.string.difficulty_expert
        "master" -> R.string.difficulty_master
        "grandmaster" -> R.string.difficulty_grandmaster
        else -> R.string.difficulty_casual
    },
)

@Composable
internal fun botLevelDescription(level: NamedBotLevel): String = stringResource(
    when (level.id) {
        "learner" -> R.string.difficulty_description_learner
        "casual" -> R.string.difficulty_description_casual
        "challenger" -> R.string.difficulty_description_challenger
        "club" -> R.string.difficulty_description_club
        "expert" -> R.string.difficulty_description_expert
        "master" -> R.string.difficulty_description_master
        "grandmaster" -> R.string.difficulty_description_grandmaster
        else -> R.string.difficulty_description_casual
    },
)

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
