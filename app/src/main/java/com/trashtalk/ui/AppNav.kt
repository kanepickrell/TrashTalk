package com.trashtalk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trashtalk.domain.EarnedBadgeInfo
import com.trashtalk.domain.ProgressDelta
import com.trashtalk.ui.collect.CollectScreen
import com.trashtalk.ui.components.Chip
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.components.Panel
import com.trashtalk.ui.components.TrashTalkButton
import com.trashtalk.ui.levels.LevelDetailScreen
import com.trashtalk.ui.levels.LevelsScreen
import com.trashtalk.ui.lia.ReportScreen
import com.trashtalk.ui.map.MapScreen
import com.trashtalk.ui.paths.PathsScreen
import com.trashtalk.ui.profile.MeScreen
import com.trashtalk.ui.ranks.RanksScreen
import com.trashtalk.ui.theme.TT
import kotlinx.coroutines.delay

/** Top-level destinations, in the order they appear in the bar. */
private enum class Tab(val route: String, val label: String) {
    MAP("map", "Map"),
    LEVELS("levels", "Levels"),
    PATHS("paths", "Paths"),
    RANKS("ranks", "Ranks"),
    ME("me", "Me"),
}

private object Routes {
    const val COLLECT = "collect"
    const val REPORT = "report"
    const val LEVEL_DETAIL = "level"
}

@Composable
fun TrashTalkRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Celebrations arrive as events rather than being derived from state, so a
    // badge is announced exactly once even though the profile that earned it will
    // be re-emitted many times.
    var celebration by remember { mutableStateOf<ProgressDelta?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { delta ->
            if (delta.isNoteworthy) celebration = delta
        }
    }
    LaunchedEffect(Unit) {
        vm.toasts.collect { message ->
            toast = message
            delay(2600)
            toast = null
        }
    }

    val showBottomBar = Tab.entries.any { it.route == route }

    Scaffold(
        containerColor = TT.Void,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BottomBar(
                    current = Tab.entries.firstOrNull { it.route == route } ?: Tab.MAP,
                    onSelect = { tab ->
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = Tab.MAP.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Tab.MAP.route) {
                    // The map is full-bleed: it draws behind the bar on purpose, so
                    // only the bottom inset is applied.
                    MapScreen(
                        vm = vm,
                        onLogPickup = { segment ->
                            nav.navigate("${Routes.COLLECT}?segmentId=${segment?.id ?: ""}")
                        },
                        onOpenReport = { nav.navigate(Routes.REPORT) },
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    )
                }

                composable(Tab.LEVELS.route) {
                    LevelsScreen(
                        vm = vm,
                        onOpenLevel = { index -> nav.navigate("${Routes.LEVEL_DETAIL}/$index") },
                        onChoosePath = { nav.navigate(Tab.PATHS.route) },
                        modifier = Modifier.padding(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding(),
                        ),
                    )
                }

                composable(Tab.PATHS.route) {
                    PathsScreen(
                        vm = vm,
                        modifier = Modifier.padding(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding(),
                        ),
                    )
                }

                composable(Tab.RANKS.route) {
                    RanksScreen(
                        vm = vm,
                        modifier = Modifier.padding(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding(),
                        ),
                    )
                }

                composable(Tab.ME.route) {
                    MeScreen(
                        vm = vm,
                        onOpenReport = { nav.navigate(Routes.REPORT) },
                        modifier = Modifier.padding(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding(),
                        ),
                    )
                }

                composable("${Routes.COLLECT}?segmentId={segmentId}") { entry ->
                    CollectScreen(
                        vm = vm,
                        initialSegmentId = entry.arguments
                            ?.getString("segmentId")
                            ?.takeIf { it.isNotEmpty() },
                        onDone = { nav.popBackStack() },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                    )
                }

                composable(Routes.REPORT) {
                    ReportScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                    )
                }

                composable("${Routes.LEVEL_DETAIL}/{index}") { entry ->
                    LevelDetailScreen(
                        vm = vm,
                        levelIndex = entry.arguments?.getString("index")?.toIntOrNull() ?: 1,
                        onBack = { nav.popBackStack() },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                    )
                }
            }

            // Transient message, above everything, never blocking.
            AnimatedVisibility(
                visible = toast != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            ) {
                Surface(
                    color = TT.Surface3,
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Text(
                        text = toast.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TT.Ink,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    )
                }
            }
        }
    }

    celebration?.let { delta ->
        CelebrationOverlay(delta = delta, onDismiss = { celebration = null })
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Surface(color = TT.Surface1) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { tab ->
                val selected = tab == current
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TabGlyph(tab = tab, selected = selected)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) TT.Teal else TT.InkMuted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Hand-drawn tab glyphs.
 *
 * Drawing five small marks directly is a few lines of canvas and keeps the icon
 * set on-brand -- the map glyph is literally a fragment of the segment map. It
 * also avoids pulling in the extended Material icon set, which is a large
 * dependency to carry for five shapes.
 */
@Composable
private fun TabGlyph(tab: Tab, selected: Boolean) {
    val tint = if (selected) TT.Teal else TT.InkMuted
    val emphasis by animateFloatAsState(if (selected) 1f else 0.72f, label = "glyph")

    androidx.compose.foundation.Canvas(Modifier.size(21.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.9f * emphasis, cap = StrokeCap.Round)

        when (tab) {
            Tab.MAP -> {
                // Two segments meeting at a node.
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.82f),
                    androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.48f), stroke.width, StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.48f),
                    androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.14f), stroke.width, StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.48f),
                    androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.66f), stroke.width, StrokeCap.Round)
                drawCircle(tint, radius = 2.4f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.48f))
            }

            Tab.LEVELS -> {
                // A rising staircase: the narrative.
                for (i in 0..2) {
                    val y = h * (0.78f - i * 0.22f)
                    drawLine(
                        tint,
                        androidx.compose.ui.geometry.Offset(w * (0.16f + i * 0.08f), y),
                        androidx.compose.ui.geometry.Offset(w * (0.52f + i * 0.14f), y),
                        stroke.width,
                        StrokeCap.Round,
                    )
                }
            }

            Tab.PATHS -> {
                // Four nodes around a centre: the ecosystem.
                drawCircle(tint, 2.2f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.17f))
                drawCircle(tint, 2.2f, androidx.compose.ui.geometry.Offset(w * 0.83f, h * 0.5f))
                drawCircle(tint, 2.2f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.83f))
                drawCircle(tint, 2.2f, androidx.compose.ui.geometry.Offset(w * 0.17f, h * 0.5f))
                drawCircle(
                    tint.copy(alpha = 0.55f),
                    radius = w * 0.33f,
                    center = center,
                    style = Stroke(width = 1.2f),
                )
            }

            Tab.RANKS -> {
                // Three bars of unequal height.
                val heights = listOf(0.42f, 0.74f, 0.56f)
                heights.forEachIndexed { i, frac ->
                    val x = w * (0.24f + i * 0.26f)
                    drawLine(
                        tint,
                        androidx.compose.ui.geometry.Offset(x, h * 0.84f),
                        androidx.compose.ui.geometry.Offset(x, h * (0.84f - frac)),
                        stroke.width * 1.5f,
                        StrokeCap.Round,
                    )
                }
            }

            Tab.ME -> {
                drawCircle(
                    tint,
                    radius = w * 0.19f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.34f),
                    style = Stroke(width = stroke.width),
                )
                drawArc(
                    color = tint,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.17f, h * 0.52f),
                    size = androidx.compose.ui.geometry.Size(w * 0.66f, h * 0.62f),
                    style = Stroke(width = stroke.width, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * The reward moment: a full-screen acknowledgement of badge tiers and path
 * level-ups. Deliberately a single modal that lists everything earned, rather
 * than a queue of popups, so logging a big haul does not become a tapping chore.
 */
@Composable
private fun CelebrationOverlay(delta: ProgressDelta, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TT.Void.copy(alpha = 0.93f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            delta.pathLevelUp?.let { level ->
                Eyebrow("Level up", color = TT.Teal)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Level $level",
                    style = MaterialTheme.typography.displaySmall,
                    color = TT.Teal,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
            }

            if (delta.newBadges.isNotEmpty()) {
                Eyebrow(
                    if (delta.newBadges.size == 1) "Badge earned" else "${delta.newBadges.size} badges earned",
                )
                Spacer(Modifier.height(12.dp))
                delta.newBadges.forEach { info ->
                    BadgeAward(info)
                    Spacer(Modifier.height(9.dp))
                }
            }

            if (delta.pointsAwarded > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "+${delta.pointsAwarded} points",
                    style = MaterialTheme.typography.titleMedium,
                    color = TT.Ink,
                )
            }

            Spacer(Modifier.height(26.dp))
            TrashTalkButton(text = "Keep going", onClick = onDismiss)
        }
    }
}

@Composable
private fun BadgeAward(info: EarnedBadgeInfo) {
    Panel(modifier = Modifier.fillMaxWidth(), accent = TT.Teal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(TT.Teal.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = info.def.tierLabel(info.tier),
                    style = MaterialTheme.typography.titleSmall,
                    color = TT.Teal,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = info.def.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TT.Ink,
                )
                Text(
                    text = info.def.criteria,
                    style = MaterialTheme.typography.bodySmall,
                    color = TT.InkMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            Chip(text = info.def.path.label, tint = TT.InkSecondary)
        }
    }
}
