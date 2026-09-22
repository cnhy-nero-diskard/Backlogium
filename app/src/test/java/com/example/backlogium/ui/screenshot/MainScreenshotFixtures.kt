package com.example.backlogium.ui.screenshot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Test-only, stateless states for the five primary app destinations and their high-risk edges.
 * These hosts intentionally use local values and deterministic painters instead of production
 * ViewModels, network images, Lottie, Room, WorkManager, or wall-clock reads.
 */
internal enum class MainFixtureKind {
    HOME_POPULATED,
    HOME_NOW_PLAYING,
    HOME_FIRST_LOAD,
    LIBRARY_POPULATED,
    LIBRARY_NO_RESULTS,
    LIBRARY_SELECTION,
    HISTORY_POPULATED,
    HISTORY_EMPTY,
    ANALYTICS_POPULATED,
    ANALYTICS_SELECTED_DAY,
    ANALYTICS_EMPTY_WINDOW,
    SETTINGS_OVERVIEW,
    SETTINGS_HEALTHY,
    SETTINGS_ATTENTION,
}

@Composable
internal fun MainScreenshotFixtureHost(fixture: MainScreenshotFixture) {
    when (fixture.kind) {
        MainFixtureKind.HOME_POPULATED -> HomePopulatedFixture()
        MainFixtureKind.HOME_NOW_PLAYING -> HomeNowPlayingFixture()
        MainFixtureKind.HOME_FIRST_LOAD -> HomeFirstLoadFixture()
        MainFixtureKind.LIBRARY_POPULATED -> LibraryPopulatedFixture()
        MainFixtureKind.LIBRARY_NO_RESULTS -> LibraryNoResultsFixture()
        MainFixtureKind.LIBRARY_SELECTION -> LibrarySelectionFixture()
        MainFixtureKind.HISTORY_POPULATED -> HistoryPopulatedFixture()
        MainFixtureKind.HISTORY_EMPTY -> HistoryEmptyFixture()
        MainFixtureKind.ANALYTICS_POPULATED -> AnalyticsPopulatedFixture()
        MainFixtureKind.ANALYTICS_SELECTED_DAY -> AnalyticsSelectedDayFixture()
        MainFixtureKind.ANALYTICS_EMPTY_WINDOW -> AnalyticsEmptyWindowFixture()
        MainFixtureKind.SETTINGS_OVERVIEW -> SettingsOverviewFixture()
        MainFixtureKind.SETTINGS_HEALTHY -> SettingsHealthyFixture()
        MainFixtureKind.SETTINGS_ATTENTION -> SettingsAttentionFixture()
    }
}

@Composable
private fun MainScreenFrame(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = {
            ScreenHeader(title = title, subtitle = subtitle)
            content()
        },
    )
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "B",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("A", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun HomePopulatedFixture() {
    MainScreenFrame(title = "Home", subtitle = "Friday · 15 January 2026") {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(162.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DeterministicArtwork(
                    label = "HADES II",
                    modifier = Modifier
                        .width(98.dp)
                        .fillMaxHeight(),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusPill(text = "CONTINUE PLAYING")
                    Text(
                        text = "Hades II",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "12.5 hours played · 64% complete",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DeterministicProgress(progress = 0.64f)
                    Text(
                        text = "Pick up where you left off",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        SectionLabel("Today's focus", "3 games in your rotation")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GameMiniCard(
                title = "Outer Wilds",
                detail = "Next up",
                artwork = "OUTER",
                modifier = Modifier.weight(1f),
            )
            GameMiniCard(
                title = "Celeste",
                detail = "Backlog",
                artwork = "CELESTE",
                modifier = Modifier.weight(1f),
            )
        }
        SectionLabel("Weekly rhythm", "A small step every day")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Metric(label = "PLAY STREAK", value = "4 days")
                Metric(label = "THIS WEEK", value = "6.2 h")
                Metric(label = "FINISHED", value = "2 games")
            }
        }
    }
}

@Composable
private fun HomeNowPlayingFixture() {
    MainScreenFrame(title = "Home", subtitle = "Friday · 15 January 2026") {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DeterministicArtwork(
                    label = "HADES II",
                    modifier = Modifier
                        .width(108.dp)
                        .fillMaxHeight(),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    StatusPill(text = "NOW PLAYING", emphasized = true)
                    Text(
                        text = "Hades II",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Session started at 19:20",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DeterministicProgress(progress = 0.72f)
                    Text(
                        text = "Paused at the Crossroads",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        SectionLabel("Up next", "Your short list is ready")
        GameListRow("Balatro", "45 minutes played", "BALATRO")
        GameListRow("Tunic", "2 achievements remaining", "TUNIC")
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = "Playing is the plan · synced locally",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeFirstLoadFixture() {
    MainScreenFrame(title = "Home", subtitle = "Friday · 15 January 2026") {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = "Loading your library",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Your local collection is being prepared for the first view.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(3.dp))
                DeterministicProgress(progress = 0.42f)
                Text(
                    text = "Step 2 of 3 · reading cached game details",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        SectionLabel("While we prepare things", "Nothing is lost")
        GameListRow("Your backlog", "Games stay on this device", "LOCAL")
        GameListRow("Your history", "Sessions will appear here", "HISTORY")
        Spacer(modifier = Modifier.weight(1f))
        StatusPill(text = "OFFLINE-FIRST", emphasized = true)
    }
}

@Composable
private fun LibraryPopulatedFixture() {
    MainScreenFrame(title = "Library", subtitle = "42 games · sorted by recent activity") {
        FilterRow(filters = listOf("All games", "Playing", "Backlog"), selected = 0)
        GameListRow("Hades II", "Playing · 12.5 hours", "HADES II", progress = 0.64f)
        GameListRow("Outer Wilds", "Backlog · added 14 Jan", "OUTER WILDS")
        GameListRow("Celeste", "Backlog · added 10 Jan", "CELESTE")
        GameListRow("Balatro", "Finished · 8.1 hours", "BALATRO", progress = 1f)
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = "Showing 4 of 42 · filters are local",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun LibraryNoResultsFixture() {
    MainScreenFrame(title = "Library", subtitle = "Combined filters") {
        FilterRow(filters = listOf("Finished", "Strategy", "Under 10 h"), selected = 0)
        FilterRow(filters = listOf("3 active filters", "Clear all"), selected = 0)
        EmptyPanel(
            title = "No games match these filters",
            detail = "Try removing a genre or widening the playtime range.",
            action = "Clear filters",
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Library stays unchanged while filters are active",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibrarySelectionFixture() {
    MainScreenFrame(title = "Library", subtitle = "Select games to organize") {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "2 selected",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "Move to collection",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        SelectableGameRow("Outer Wilds", "Backlog", "OUTER", selected = true)
        SelectableGameRow("Celeste", "Backlog", "CELESTE", selected = true)
        SelectableGameRow("Tunic", "Playing", "TUNIC", selected = false)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Tap a row to change selection",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryPopulatedFixture() {
    MainScreenFrame(title = "History", subtitle = "A deterministic local timeline") {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Metric(label = "THIS WEEK", value = "6.2 h")
                Metric(label = "SESSIONS", value = "8")
                Metric(label = "LONGEST", value = "2.1 h")
            }
        }
        Text(
            text = "WEDNESDAY · 14 JANUARY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        HistoryEntry("Hades II", "19:20 – 20:45", "1 h 25 m", "HADES", last = false)
        HistoryEntry("Outer Wilds", "16:10 – 17:00", "50 m", "OUTER", last = false)
        Text(
            text = "TUESDAY · 13 JANUARY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        HistoryEntry("Celeste", "21:05 – 21:42", "37 m", "CELESTE", last = true)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Times are shown in your fixed local timeline",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryEmptyFixture() {
    MainScreenFrame(title = "History", subtitle = "Your play sessions") {
        EmptyPanel(
            title = "No play history yet",
            detail = "Start a game and your local timeline will appear here.",
            action = "Open library",
            modifier = Modifier.fillMaxWidth().height(224.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = "History is private by default",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Only local session summaries are shown on this screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AnalyticsPopulatedFixture() {
    MainScreenFrame(title = "Analytics", subtitle = "Your recent momentum") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricCard("PLAY TIME", "6.2 h", "this week", Modifier.weight(1f))
            MetricCard("SESSIONS", "8", "across 4 games", Modifier.weight(1f))
            MetricCard("STREAK", "4 days", "best: 9", Modifier.weight(1f))
        }
        AnalyticsChart(title = "Play time", detail = "Last 7 days · hours")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = "Most played",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                AnalyticsBar(label = "Hades II", value = "2.7 h", progress = 0.84f)
                AnalyticsBar(label = "Outer Wilds", value = "1.8 h", progress = 0.56f)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AnalyticsSelectedDayFixture() {
    MainScreenFrame(title = "Analytics", subtitle = "A closer look at one day") {
        DateStrip(selected = "Wed 14", dates = listOf("Mon 12", "Tue 13", "Wed 14", "Thu 15"))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Wednesday, 14 January",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "2 h 15 m · 2 sessions · 2 games",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        AnalyticsChart(title = "Hourly activity", detail = "19:00 – 21:00", highlighted = true)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Selected day is pinned for comparison",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AnalyticsEmptyWindowFixture() {
    MainScreenFrame(title = "Analytics", subtitle = "Custom date range") {
        DateStrip(selected = "Jan 01–07", dates = listOf("Last week", "Jan 01–07", "This week"))
        EmptyPanel(
            title = "No sessions in this range",
            detail = "Choose another range to see your play-time trend.",
            action = "Reset range",
            modifier = Modifier.fillMaxWidth().height(218.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Metric(label = "PLAY TIME", value = "0 m")
                Metric(label = "SESSIONS", value = "0")
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingsOverviewFixture() {
    MainScreenFrame(title = "Settings", subtitle = "Backlogium preferences") {
        SettingsGroup(
            title = "Account",
            rows = listOf(
                "Steam account" to "Connected",
                "Sync" to "Local changes only",
            ),
            status = "READY",
        )
        SettingsGroup(
            title = "Appearance",
            rows = listOf(
                "Theme" to "System default",
                "Library density" to "Comfortable",
            ),
        )
        SettingsGroup(
            title = "Data & privacy",
            rows = listOf(
                "Cloud presence" to "Off",
                "Diagnostics" to "Not sharing",
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Backlogium · settings are stored on this device",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsHealthyFixture() {
    MainScreenFrame(title = "Settings", subtitle = "Everything is ready") {
        StatusCard(
            title = "All systems healthy",
            detail = "Your library is current and local storage is available.",
            status = "HEALTHY",
            emphasized = true,
        )
        SettingsGroup(
            title = "Connected services",
            rows = listOf(
                "Steam library" to "Ready",
                "Last refresh" to "Today · 18:40",
            ),
        )
        SettingsGroup(
            title = "Local data",
            rows = listOf(
                "Storage" to "142 MB used",
                "Backup" to "Not configured",
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "No credentials or test environment values are displayed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsAttentionFixture() {
    MainScreenFrame(title = "Settings", subtitle = "One item needs your attention") {
        StatusCard(
            title = "Refresh needed",
            detail = "Reconnect your Steam library to update game details.",
            status = "ATTENTION",
            emphasized = false,
        )
        SettingsGroup(
            title = "Connected services",
            rows = listOf(
                "Steam library" to "Needs reconnect",
                "Last refresh" to "Not available",
            ),
        )
        SettingsGroup(
            title = "Privacy",
            rows = listOf(
                "Cloud presence" to "Off",
                "Diagnostics" to "Not sharing",
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Text(
                text = "Reconnect is optional · your local library remains available",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeterministicArtwork(label: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(scheme.primary, scheme.secondary, scheme.tertiary),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.15f),
                radius = size.minDimension * 0.42f,
                center = Offset(size.width * 0.73f, size.height * 0.26f),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.24f),
                start = Offset(0f, size.height * 0.76f),
                end = Offset(size.width, size.height * 0.34f),
                strokeWidth = 3f,
            )
        }
        Text(
            text = label,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(7.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatusPill(text: String, emphasized: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (emphasized) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DeterministicProgress(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun GameMiniCard(
    title: String,
    detail: String,
    artwork: String,
    modifier: Modifier,
) {
    Card(modifier = modifier.height(118.dp)) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DeterministicArtwork(
                label = artwork,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            )
            Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GameListRow(
    title: String,
    detail: String,
    artwork: String,
    progress: Float? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (progress == null) 68.dp else 78.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeterministicArtwork(artwork, Modifier.size(52.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress != null) DeterministicProgress(progress)
            }
        }
    }
}

@Composable
private fun FilterRow(filters: List<String>, selected: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        filters.forEachIndexed { index, filter ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                color = if (index == selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = filter,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectableGameRow(title: String, detail: String, artwork: String, selected: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DeterministicArtwork(artwork, Modifier.size(52.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selected) "✓" else "",
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, detail: String, action: String, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("· · ·", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(modifier = Modifier.height(9.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = detail,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.padding(top = 12.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = action,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetricCard(label: String, value: String, detail: String, modifier: Modifier) {
    Card(modifier = modifier.height(78.dp)) {
        Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HistoryEntry(title: String, time: String, duration: String, artwork: String, last: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(18.dp).fillMaxHeight()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLine(
                    color = scheme.outlineVariant,
                    start = Offset(size.width / 2f, if (last) 0f else size.height / 2f),
                    end = Offset(size.width / 2f, if (last) size.height / 2f else size.height),
                    strokeWidth = 2f,
                )
                drawCircle(
                    color = scheme.primary,
                    radius = 4f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        }
        DeterministicArtwork(artwork, Modifier.size(48.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = duration,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AnalyticsChart(title: String, detail: String, highlighted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().height(176.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val grid = scheme.outlineVariant.copy(alpha = 0.45f)
                for (row in 1..3) {
                    val y = size.height * row / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val points = listOf(
                    Offset(0f, size.height * 0.78f),
                    Offset(size.width * 0.16f, size.height * 0.62f),
                    Offset(size.width * 0.33f, size.height * 0.68f),
                    Offset(size.width * 0.5f, size.height * 0.30f),
                    Offset(size.width * 0.67f, size.height * 0.52f),
                    Offset(size.width * 0.84f, size.height * 0.25f),
                    Offset(size.width, size.height * if (highlighted) 0.42f else 0.38f),
                )
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = path,
                    color = scheme.primary,
                    style = Stroke(width = 4f, cap = StrokeCap.Round),
                )
                points.forEach { point ->
                    drawCircle(scheme.primary, radius = 4f, center = point)
                }
            }
        }
    }
}

@Composable
private fun AnalyticsBar(label: String, value: String, progress: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, modifier = Modifier.width(84.dp), style = MaterialTheme.typography.labelMedium)
        DeterministicProgress(progress = progress)
        Text(text = value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DateStrip(selected: String, dates: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        dates.forEach { date ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (date == selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = date,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (date == selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (date == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, rows: List<Pair<String, String>>, status: String? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (status != null) StatusPill(status)
            }
            rows.forEach { (label, value) ->
                SettingRow(label = label, value = value)
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatusCard(title: String, detail: String, status: String, emphasized: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(status, emphasized = emphasized)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (emphasized) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (emphasized) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
        }
    }
}
