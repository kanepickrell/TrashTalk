package com.trashtalk.ui.ranks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trashtalk.data.model.LeaderboardEntry
import com.trashtalk.data.model.LeaderboardScope
import com.trashtalk.data.model.Team
import com.trashtalk.ui.MainViewModel
import com.trashtalk.ui.components.Chip
import com.trashtalk.ui.components.EmptyState
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.components.Panel
import com.trashtalk.ui.components.SectionHeader
import com.trashtalk.ui.components.SegmentedPicker
import com.trashtalk.ui.components.TabularNumber
import com.trashtalk.ui.components.TrashTalkOutlineButton
import com.trashtalk.ui.theme.TT

/**
 * Leaderboards and crews.
 *
 * Boards are bracketed by week and scope rather than being one all-time list,
 * because a single lifetime ranking is unwinnable for anyone who joins late --
 * and a ranking nobody can move is a ranking nobody competes on. The all-time
 * view still exists, as a record rather than a contest.
 */
@Composable
fun RanksScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val scope by vm.leaderboardScope.collectAsStateWithLifecycle()
    val rows by vm.leaderboard.collectAsStateWithLifecycle()
    val teams by vm.teams.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TT.Void),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                SectionHeader(eyebrow = "Standings", title = "Who is doing the work")
                Spacer(Modifier.height(12.dp))
                SegmentedPicker(
                    options = LeaderboardScope.entries,
                    selected = scope,
                    label = { it.label },
                    onSelect = { vm.setLeaderboardScope(it) },
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        if (scope == LeaderboardScope.TEAM_WEEK && profile.teamId == null) {
            item {
                Panel {
                    EmptyState(
                        title = "You have not joined a crew",
                        body = "Crews are where the weekly competition actually bites. " +
                            "Pick one below and your team board fills in.",
                    )
                }
            }
        } else if (rows.isEmpty()) {
            item {
                Panel {
                    EmptyState(
                        title = "Nothing logged yet",
                        body = "Log a pickup and you will appear here.",
                    )
                }
            }
        } else {
            // The player's own standing is pinned so it never requires scrolling.
            val me = rows.firstOrNull { it.isCurrentUser }
            if (me != null && me.rank > 5) {
                item {
                    Panel(accent = TT.Teal) {
                        Column {
                            Eyebrow("Your standing")
                            Spacer(Modifier.height(8.dp))
                            LeaderRow(entry = me, highlight = true)
                        }
                    }
                }
            }

            item {
                Panel {
                    Column {
                        Eyebrow(scope.label)
                        Spacer(Modifier.height(4.dp))
                        rows.take(25).forEach { entry ->
                            LeaderRow(entry = entry, highlight = entry.isCurrentUser)
                        }
                        if (rows.size > 25) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Showing the top 25 of ${rows.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TT.InkMuted,
                            )
                        }
                    }
                }
            }
        }

        // ----------------------------------------------------------- the crews
        item {
            Column {
                Spacer(Modifier.height(10.dp))
                SectionHeader(eyebrow = "Crews", title = "Pick a team to compete with")
                Spacer(Modifier.height(6.dp))
            }
        }

        items(teams.sortedByDescending { it.weeklyPoints }, key = { it.id }) { team ->
            TeamCard(
                team = team,
                isMine = profile.teamId == team.id,
                onJoin = { vm.joinTeam(if (profile.teamId == team.id) null else team.id) },
            )
        }
    }
}

@Composable
private fun LeaderRow(entry: LeaderboardEntry, highlight: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlight) TT.Surface2 else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = if (highlight) 9.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(27.dp)) {
            Text(
                text = "${entry.rank}",
                style = MaterialTheme.typography.labelMedium,
                color = when (entry.rank) {
                    1 -> TT.LitterRampOrdinal[3]
                    2, 3 -> TT.InkSecondary
                    else -> TT.InkMuted
                },
                fontWeight = if (entry.rank <= 3) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (highlight) TT.Teal else TT.Ink,
                    fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (entry.pathLabel != null) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "L${entry.level} ${entry.pathLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TT.InkMuted,
                    )
                }
            }
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            TabularNumber("${entry.points}")
            Text(
                text = "pts",
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
        }
    }
}

@Composable
private fun TeamCard(team: Team, isMine: Boolean, onJoin: () -> Unit) {
    Panel(
        modifier = Modifier.fillMaxWidth(),
        accent = if (isMine) TT.Teal else null,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = team.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = TT.Ink,
                        )
                        if (isMine) {
                            Spacer(Modifier.width(7.dp))
                            Chip(text = "Your crew", tint = TT.Teal, filled = true)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = team.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = TT.InkMuted,
                    )
                }
                Spacer(Modifier.width(10.dp))
                TrashTalkOutlineButton(
                    text = if (isMine) "Leave" else "Join",
                    onClick = onJoin,
                    tint = if (isMine) TT.InkMuted else TT.Teal,
                )
            }
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TeamStat("${team.memberCount}", "members")
                TeamStat("${team.weeklyPoints}", "pts this week")
                TeamStat("${team.totalPieces}", "pieces all time")
            }
        }
    }
}

@Composable
private fun TeamStat(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = TT.Ink,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TT.InkMuted,
        )
    }
}
