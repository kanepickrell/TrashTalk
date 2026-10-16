package com.trashtalk.ui.paths

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.trashtalk.data.AdvocacyAction
import com.trashtalk.data.model.BadgeCatalog
import com.trashtalk.data.model.BadgeDef
import com.trashtalk.data.model.BadgeMetric
import com.trashtalk.data.model.TrashPath
import com.trashtalk.data.model.UserProfile
import com.trashtalk.domain.BadgeEngine
import com.trashtalk.ui.MainViewModel
import com.trashtalk.ui.components.Chip
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.components.Hairline
import com.trashtalk.ui.components.Panel
import com.trashtalk.ui.components.ProgressRing
import com.trashtalk.ui.components.ProgressTrack
import com.trashtalk.ui.components.SectionHeader
import com.trashtalk.ui.components.SegmentedPicker
import com.trashtalk.ui.components.TrashTalkButton
import com.trashtalk.ui.components.TrashTalkOutlineButton
import com.trashtalk.ui.theme.TT

/**
 * Paths and badges: the part of the game with no ceiling.
 *
 * Each path colour comes from the categorical palette in fixed slot order -- the
 * paths are identities, not magnitudes, so they never get a ramp and the colour
 * for Scientist never shifts because a different path is selected.
 */
private fun pathColor(path: TrashPath): Color = when (path) {
    TrashPath.SCIENTIST -> TT.categorical(0)
    TrashPath.ECO_WARRIOR -> TT.categorical(1)
    TrashPath.ADVOCATE -> TT.categorical(2)
    TrashPath.SEEKER -> TT.categorical(6)
}

@Composable
fun PathsScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    var focused by remember(profile.path) {
        mutableStateOf(profile.path ?: TrashPath.SCIENTIST)
    }
    var showEcosystem by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TT.Void),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                SectionHeader(
                    eyebrow = "Four paths",
                    title = profile.path?.let { "Level ${profile.pathLevel(it)} ${it.label}" }
                        ?: "Choose how you contribute",
                )
                Spacer(Modifier.height(10.dp))

                if (!profile.narrativeComplete) {
                    Panel(accent = TT.Warning) {
                        Column {
                            Eyebrow("Locked until level 7")
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = "Finish the seven-level narrative first. The paths " +
                                    "only make sense once you know what the project is " +
                                    "arguing -- picking a role before that is picking a " +
                                    "costume.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TT.InkSecondary,
                            )
                            Spacer(Modifier.height(9.dp))
                            ProgressTrack(
                                fraction = profile.completedLevels.size / 7f,
                                tint = TT.Warning,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${profile.completedLevels.size} of 7 levels complete",
                                style = MaterialTheme.typography.labelSmall,
                                color = TT.InkMuted,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // The mechanism explainer. It is collapsed by default because it is
                // the kind of thing people want once, not every visit.
                Panel(
                    modifier = Modifier.clickable { showEcosystem = !showEcosystem },
                    tone = TT.Surface1,
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Eyebrow("How the ecosystem works")
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "M1 competes. M2 collaborates.",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TT.Ink,
                                )
                            }
                            Text(
                                text = if (showEcosystem) "Hide" else "Show",
                                style = MaterialTheme.typography.labelMedium,
                                color = TT.Teal,
                            )
                        }
                        AnimatedVisibility(visible = showEcosystem) {
                            Column {
                                Spacer(Modifier.height(12.dp))
                                MechanismRow(
                                    tag = "M1",
                                    left = TrashPath.SEEKER,
                                    right = TrashPath.ECO_WARRIOR,
                                    blurb = "Seekers and Eco-warriors engage each other by " +
                                        "nature. Both compete on output, so between them they " +
                                        "generate records, rivalry and raw volume.",
                                )
                                Spacer(Modifier.height(12.dp))
                                MechanismRow(
                                    tag = "M2",
                                    left = TrashPath.SCIENTIST,
                                    right = TrashPath.ADVOCATE,
                                    blurb = "Scientists and Advocates work with each other by " +
                                        "nature. One produces the evidence; the other turns it " +
                                        "into recruitment and pressure.",
                                )
                                Spacer(Modifier.height(12.dp))
                                Hairline()
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "M2 supplies M1 with motivation and resources. M1 " +
                                        "supplies M2 with results, recruits and better data. " +
                                        "That loop is the product -- the badges just make it " +
                                        "legible.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TT.InkSecondary,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        // ---------------------------------------------------------- path cards
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TrashPath.entries.forEach { path ->
                    PathCard(
                        path = path,
                        profile = profile,
                        isCurrent = profile.path == path,
                        isFocused = focused == path,
                        canChoose = profile.narrativeComplete,
                        onFocus = { focused = path },
                        onChoose = { vm.choosePath(path) },
                    )
                }
            }
        }

        // -------------------------------------------------------------- badges
        item {
            Column {
                Spacer(Modifier.height(10.dp))
                SectionHeader(
                    eyebrow = "Badges",
                    title = "${profile.totalTiers} tiers banked of 100",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Twenty badges, five per path, five repeatable tiers each. Five " +
                        "banked tiers raise your level on that path, and there is no maximum.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TT.InkSecondary,
                )
                Spacer(Modifier.height(12.dp))
                SegmentedPicker(
                    options = TrashPath.entries,
                    selected = focused,
                    label = { it.label },
                    onSelect = { focused = it },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BadgeCatalog.forPath(focused).forEach { badge ->
                    BadgeCard(
                        badge = badge,
                        profile = profile,
                        onQuickAction = { action, amount -> vm.recordAdvocacy(action, amount) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MechanismRow(
    tag: String,
    left: TrashPath,
    right: TrashPath,
    blurb: String,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(text = tag, tint = TT.Teal, filled = true)
            Spacer(Modifier.width(9.dp))
            Chip(text = left.label, tint = pathColor(left), filled = true)
            Text(
                text = "  +  ",
                style = MaterialTheme.typography.labelMedium,
                color = TT.InkMuted,
            )
            Chip(text = right.label, tint = pathColor(right), filled = true)
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = TT.InkSecondary,
        )
    }
}

@Composable
private fun PathCard(
    path: TrashPath,
    profile: UserProfile,
    isCurrent: Boolean,
    isFocused: Boolean,
    canChoose: Boolean,
    onFocus: () -> Unit,
    onChoose: () -> Unit,
) {
    val tint = pathColor(path)
    val tiers = profile.tiersIn(path)
    val level = profile.pathLevel(path)
    val toNext = profile.tiersToNextPathLevel(path)

    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onFocus),
        accent = if (isCurrent) tint else if (isFocused) TT.Hairline else null,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    fraction = (tiers % BadgeCatalog.TIERS_PER_PATH_LEVEL) /
                        BadgeCatalog.TIERS_PER_PATH_LEVEL.toFloat(),
                    tint = tint,
                    thickness = 4.dp,
                    modifier = Modifier.size(50.dp),
                ) {
                    Text(
                        text = "$level",
                        style = MaterialTheme.typography.titleMedium,
                        color = tint,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = path.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = TT.Ink,
                        )
                        if (isCurrent) {
                            Spacer(Modifier.width(7.dp))
                            Chip(text = "Your path", tint = tint, filled = true)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = path.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = TT.InkSecondary,
                    )
                }
            }

            AnimatedVisibility(visible = isFocused) {
                Column {
                    Spacer(Modifier.height(13.dp))
                    Text(
                        text = path.mission,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TT.InkSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Panel(tone = TT.Surface2, corner = 12.dp) {
                        Column {
                            Eyebrow("Pairs with ${path.partner}")
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = path.mechanism,
                                style = MaterialTheme.typography.bodySmall,
                                color = TT.InkSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.height(11.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$tiers tiers banked - $toNext to level ${level + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TT.InkMuted,
                            modifier = Modifier.weight(1f),
                        )
                        if (!isCurrent && canChoose) {
                            TrashTalkOutlineButton(
                                text = if (profile.path == null) "Choose" else "Switch",
                                onClick = onChoose,
                                tint = tint,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeCard(
    badge: BadgeDef,
    profile: UserProfile,
    onQuickAction: (AdvocacyAction, Int) -> Unit,
) {
    val tint = pathColor(badge.path)
    val value = profile.stats.metric(badge.metric)
    val banked = profile.badgeTiers[badge.id] ?: 0
    val next = badge.nextTarget(value)
    val progress = BadgeEngine.progress(badge, value)
    var expanded by remember { mutableStateOf(false) }

    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        accent = if (banked > 0) tint else null,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = badge.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (banked > 0) TT.Ink else TT.InkSecondary,
                        )
                        if (banked > 0) {
                            Spacer(Modifier.width(7.dp))
                            Chip(
                                text = badge.tierLabel(banked),
                                tint = tint,
                                filled = true,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = badge.criteria,
                        style = MaterialTheme.typography.bodySmall,
                        color = TT.InkMuted,
                    )
                }
                Spacer(Modifier.width(10.dp))
                // Five pips: the tier ladder at a glance.
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(badge.maxTier) { i ->
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (i < banked) tint else TT.Surface3),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            ProgressTrack(fraction = progress, tint = tint, thickness = 5.dp)
            Spacer(Modifier.height(5.dp))
            Text(
                text = if (next != null) {
                    "$value of $next toward ${badge.tierLabel(banked + 1)}"
                } else {
                    "Every tier earned - $value logged"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Hairline()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = badge.howTo,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TT.InkSecondary,
                    )

                    // Badges whose metric is a self-reported action get a button
                    // right here, so the loop from "what counts" to "I did it" is
                    // one tap rather than a hunt through the app.
                    quickActionFor(badge.metric)?.let { (action, amount, label) ->
                        Spacer(Modifier.height(12.dp))
                        TrashTalkButton(
                            text = label,
                            onClick = { onQuickAction(action, amount) },
                            tone = tint,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The self-reported badge metrics, with the action that records one.
 *
 * These are on the honour system, which is a deliberate trade: an invite or a
 * conversation with a shop owner cannot be verified by an app, and refusing to
 * count what cannot be verified would mean the Advocate path does not exist.
 */
private fun quickActionFor(metric: BadgeMetric): Triple<AdvocacyAction, Int, String>? =
    when (metric) {
        BadgeMetric.SHARES -> Triple(AdvocacyAction.SHARE_STATSHOT, 1, "Share a statshot")
        BadgeMetric.INVITES_SENT -> Triple(AdvocacyAction.SEND_INVITE, 1, "Log an invite")
        BadgeMetric.REFERRALS -> Triple(AdvocacyAction.LOG_REFERRAL, 1, "Log a referral")
        BadgeMetric.MOTIVATION_MESSAGES ->
            Triple(AdvocacyAction.TEAM_MESSAGE, 1, "Message your team")
        BadgeMetric.TRIVIA_WEEKS ->
            Triple(AdvocacyAction.FINISH_TRIVIA, 1, "Mark this week's trivia done")
        BadgeMetric.IMAGES_LABELLED ->
            Triple(AdvocacyAction.LABEL_IMAGES, 25, "Label 25 images")
        BadgeMetric.PEER_RESURVEYS ->
            Triple(AdvocacyAction.PEER_RESURVEY, 1, "Log a peer re-survey")
        BadgeMetric.REMINDERS_ANSWERED ->
            Triple(AdvocacyAction.ANSWER_REMINDER, 1, "Answered a reminder")
        else -> null
    }
