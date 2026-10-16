package com.trashtalk.ui.levels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trashtalk.data.content.LevelContent
import com.trashtalk.data.content.NarrativeLevel
import com.trashtalk.data.content.QuizQuestion
import com.trashtalk.ui.MainViewModel
import com.trashtalk.ui.components.Chip
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.components.Hairline
import com.trashtalk.ui.components.Panel
import com.trashtalk.ui.components.ProgressTrack
import com.trashtalk.ui.components.SectionHeader
import com.trashtalk.ui.components.TrashTalkButton
import com.trashtalk.ui.components.TrashTalkOutlineButton
import com.trashtalk.ui.theme.TT

/**
 * The seven-level manifesto.
 *
 * Levels unlock in order and each ends in a short check, which is the mechanism
 * that makes the narrative mean something: a player who reaches the path choice
 * has demonstrably read the argument rather than tapped past it. The gate is
 * deliberately gentle -- you may retry immediately, and the explanation is shown
 * either way -- because the aim is comprehension, not attrition.
 */
@Composable
fun LevelsScreen(
    vm: MainViewModel,
    onOpenLevel: (Int) -> Unit,
    onChoosePath: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val levels = LevelContent.levels

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TT.Void),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                SectionHeader(
                    eyebrow = "The case",
                    title = "Seven levels, one argument",
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Litter is the visible end of a linear materials economy. " +
                        "These seven levels make that case, in order, and end with you " +
                        "choosing the role you will play in fixing it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TT.InkSecondary,
                )
                Spacer(Modifier.height(14.dp))
                Panel {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("Progress")
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${profile.completedLevels.size} of ${levels.size}",
                                style = MaterialTheme.typography.titleSmall,
                                color = TT.Ink,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ProgressTrack(
                            fraction = profile.completedLevels.size.toFloat() / levels.size,
                        )
                        if (profile.narrativeComplete) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = if (profile.path == null) {
                                    "The narrative is done. Choose your path to keep levelling."
                                } else {
                                    "You are on the ${profile.path?.label} path."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TT.InkSecondary,
                            )
                            Spacer(Modifier.height(10.dp))
                            TrashTalkButton(
                                text = if (profile.path == null) "Choose your path" else "View your path",
                                onClick = onChoosePath,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        items(levels, key = { it.index }) { level ->
            val completed = level.index in profile.completedLevels
            // Unlocked when it is the next one in sequence, or already done.
            val unlocked = completed || level.index <= profile.narrativeLevel + 1
            LevelCard(
                level = level,
                completed = completed,
                unlocked = unlocked,
                onClick = { if (unlocked) onOpenLevel(level.index) },
            )
        }
    }
}

@Composable
private fun LevelCard(
    level: NarrativeLevel,
    completed: Boolean,
    unlocked: Boolean,
    onClick: () -> Unit,
) {
    val accent = when {
        completed -> TT.Good
        unlocked -> TT.Teal
        else -> null
    }
    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = unlocked, onClick = onClick),
        accent = accent,
        tone = if (unlocked) TT.Surface1 else TT.Surface1.copy(alpha = 0.55f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            completed -> TT.Good.copy(alpha = 0.18f)
                            unlocked -> TT.Teal.copy(alpha = 0.16f)
                            else -> TT.Surface3
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (completed) "✓" else "${level.index}",
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        completed -> TT.Good
                        unlocked -> TT.Teal
                        else -> TT.InkMuted
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = level.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (unlocked) TT.Ink else TT.InkMuted,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (unlocked) level.question else "Finish the previous level first",
                    style = MaterialTheme.typography.bodySmall,
                    color = TT.InkMuted,
                    maxLines = 2,
                )
            }
            if (!completed && unlocked) {
                Spacer(Modifier.width(8.dp))
                Chip(text = "+${level.pointsAward}", tint = TT.Teal)
            }
        }
    }
}

/**
 * One level, read end to end, then its check.
 *
 * The body is long on purpose. This is the part of the product that has to carry
 * an argument, and an argument compressed into bullet points stops being one.
 */
@Composable
fun LevelDetailScreen(
    vm: MainViewModel,
    levelIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val level = LevelContent.level(levelIndex) ?: return
    val alreadyDone = levelIndex in profile.completedLevels

    var answers by remember(levelIndex) { mutableStateOf(mapOf<Int, Int>()) }
    var submitted by remember(levelIndex) { mutableStateOf(false) }

    val allCorrect = level.quiz.isEmpty() ||
        level.quiz.indices.all { i -> answers[i] == level.quiz[i].correctIndex }

    Column(
        modifier
            .fillMaxSize()
            .background(TT.Void)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Back",
                style = MaterialTheme.typography.labelLarge,
                color = TT.Teal,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.weight(1f))
            if (alreadyDone) Chip(text = "Completed", tint = TT.Good, filled = true)
        }

        Spacer(Modifier.height(14.dp))
        Eyebrow(level.eyebrow)
        Spacer(Modifier.height(5.dp))
        Text(
            text = level.title,
            style = MaterialTheme.typography.headlineMedium,
            color = TT.Ink,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = level.standfirst,
            style = MaterialTheme.typography.bodyLarge,
            color = TT.Teal,
        )

        if (level.stats.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            // Figures get their own band with their sources attached, so a
            // player repeating them downstream can say where they came from.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                level.stats.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { stat ->
                            Panel(
                                modifier = Modifier.weight(1f),
                                tone = TT.Surface2,
                                corner = 14.dp,
                            ) {
                                Column {
                                    Text(
                                        text = stat.value,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TT.LitterRampOrdinal[2],
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = stat.caption,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TT.InkSecondary,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = stat.source,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TT.InkMuted,
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        level.sections.forEachIndexed { i, section ->
            if (i > 0) Spacer(Modifier.height(20.dp))
            Text(
                text = section.heading,
                style = MaterialTheme.typography.titleMedium,
                color = TT.Ink,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = section.body,
                style = MaterialTheme.typography.bodyLarge,
                color = TT.InkSecondary,
            )
        }

        Spacer(Modifier.height(24.dp))
        Panel(tone = TT.Surface2, accent = TT.Teal) {
            Column {
                Eyebrow("Do this")
                Spacer(Modifier.height(5.dp))
                Text(
                    text = level.action,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TT.Ink,
                )
            }
        }

        if (level.quiz.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Hairline()
            Spacer(Modifier.height(18.dp))
            Eyebrow("Check")
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (level.quiz.size == 1) "One question" else "${level.quiz.size} questions",
                style = MaterialTheme.typography.headlineSmall,
                color = TT.Ink,
            )
            Spacer(Modifier.height(14.dp))

            level.quiz.forEachIndexed { qi, question ->
                QuizBlock(
                    question = question,
                    selected = answers[qi],
                    revealed = submitted,
                    onSelect = { choice ->
                        if (!submitted) answers = answers + (qi to choice)
                    },
                )
                Spacer(Modifier.height(16.dp))
            }

            val answeredAll = answers.size == level.quiz.size

            if (!submitted) {
                TrashTalkButton(
                    text = if (answeredAll) "Check answers" else "Answer every question",
                    onClick = { submitted = true },
                    enabled = answeredAll,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (allCorrect) {
                if (!alreadyDone) {
                    TrashTalkButton(
                        text = "Complete level ${level.index} (+${level.pointsAward} pts)",
                        onClick = {
                            vm.completeLevel(level.index)
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TrashTalkOutlineButton(
                        text = "Back to the levels",
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Column {
                    Text(
                        text = "Not quite. The explanations are above -- have another go.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TT.Warning,
                    )
                    Spacer(Modifier.height(10.dp))
                    TrashTalkOutlineButton(
                        text = "Try again",
                        onClick = {
                            submitted = false
                            answers = emptyMap()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Spacer(Modifier.height(22.dp))
            if (!alreadyDone) {
                TrashTalkButton(
                    text = "Complete level ${level.index}",
                    onClick = {
                        vm.completeLevel(level.index)
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun QuizBlock(
    question: QuizQuestion,
    selected: Int?,
    revealed: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column {
        Text(
            text = question.prompt,
            style = MaterialTheme.typography.titleSmall,
            color = TT.Ink,
        )
        Spacer(Modifier.height(9.dp))
        question.options.forEachIndexed { i, option ->
            val isSelected = selected == i
            val isCorrect = i == question.correctIndex
            val tint = when {
                revealed && isCorrect -> TT.Good
                revealed && isSelected -> TT.Critical
                isSelected -> TT.Teal
                else -> TT.Hairline
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) TT.Surface2 else Color.Transparent)
                    .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(11.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected || (revealed && isCorrect)) tint else TT.Surface3,
                        ),
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    text = option,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected || (revealed && isCorrect)) TT.Ink else TT.InkSecondary,
                )
            }
        }
        AnimatedVisibility(visible = revealed) {
            Column {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = question.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = TT.InkMuted,
                )
            }
        }
    }
}
