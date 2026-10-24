package pt.tiago.nightfleet.ui.navigation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.audio.MusicTrack
import pt.tiago.nightfleet.catalog.CatalogResult
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.game.analysis.AnalysisInput
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.game.campaign.CampaignContinue
import pt.tiago.nightfleet.game.campaign.CampaignHome
import pt.tiago.nightfleet.game.campaign.CampaignRules
import pt.tiago.nightfleet.ui.screens.daily.DailyHuntFlow
import pt.tiago.nightfleet.ui.screens.daily.DailyScreen
import java.time.LocalDate
import pt.tiago.nightfleet.game.campaign.campaignHome
import pt.tiago.nightfleet.game.daily.DailyChallenge
import pt.tiago.nightfleet.game.puzzle.PuzzleRequest
import pt.tiago.nightfleet.meta.Seas
import pt.tiago.nightfleet.ui.screens.achievements.AchievementsRoute
import pt.tiago.nightfleet.ui.screens.analysis.AnalysisScreen
import pt.tiago.nightfleet.ui.screens.campaign.CampaignText
import pt.tiago.nightfleet.ui.screens.campaign.MissionFlow
import pt.tiago.nightfleet.ui.screens.campaign.MissionSheetScreen
import pt.tiago.nightfleet.ui.screens.campaign.SeaMapScreen
import pt.tiago.nightfleet.ui.screens.debug.BoardGalleryScreen
import pt.tiago.nightfleet.ui.screens.diagnostics.DiagnosticsRoute
import pt.tiago.nightfleet.data.AnalysisSummary
import pt.tiago.nightfleet.ui.screens.duo.DuoScreen
import pt.tiago.nightfleet.ui.screens.freeplay.FreePlayFlow
import pt.tiago.nightfleet.ui.screens.freeplay.admiralNameRes
import pt.tiago.nightfleet.ui.screens.gallery.GalleryRoute
import pt.tiago.nightfleet.ui.screens.home.HomeScreen
import pt.tiago.nightfleet.ui.screens.home.HomeTexts
import pt.tiago.nightfleet.ui.screens.logbook.LogbookRoute
import pt.tiago.nightfleet.ui.screens.modes.ModesMenuScreen
import pt.tiago.nightfleet.ui.screens.modes.PatrolFlow
import pt.tiago.nightfleet.ui.screens.modes.TournamentFlow
import pt.tiago.nightfleet.ui.screens.profile.ProfileRoute
import pt.tiago.nightfleet.ui.screens.puzzle.PuzzleEntryDialog
import pt.tiago.nightfleet.ui.screens.puzzle.PuzzleRoute
import pt.tiago.nightfleet.ui.screens.replay.ReplayScreen
import pt.tiago.nightfleet.ui.screens.settings.SettingsRoute
import pt.tiago.nightfleet.ui.screens.settings.grandAdmiralActive
import pt.tiago.nightfleet.ui.screens.settings.toPalette
import pt.tiago.nightfleet.ui.screens.settings.toThemeId
import pt.tiago.nightfleet.ui.screens.settings.textScale
import pt.tiago.nightfleet.ui.screens.stats.StatisticsRoute
import pt.tiago.nightfleet.ui.theme.FleetTheme
import pt.tiago.nightfleet.ui.theme.LocalFleetColors

/**
 * The back stack of the app: a [NavStack] kept in saved instance state, so rotation and a process death
 * return to the same screen. Every screen change goes through here.
 */
class Navigator internal constructor(private val state: MutableState<NavStack>) {
    val stack: NavStack get() = state.value
    val current: Route get() = state.value.current

    fun push(route: Route) {
        state.value = state.value.push(route)
    }

    /** Replaces the current screen (for example the sea map when the sea or the difficulty changes). */
    fun replaceTop(route: Route) {
        state.value = state.value.replaceTop(route)
    }

    /** Goes back to [route] when it is on the stack, or pushes it. */
    fun popTo(route: Route) {
        state.value = state.value.popTo(route)
    }

    /** Goes back one screen. Returns false when the stack is at Home (the activity then leaves). */
    fun pop(): Boolean {
        if (!state.value.canGoBack) return false
        state.value = state.value.pop()
        return true
    }
}

@Composable
fun rememberNavigator(): Navigator {
    val saver = listSaver<MutableState<NavStack>, String>(
        save = { it.value.encode() },
        restore = { mutableStateOf(NavStack.decode(it)) },
    )
    val state = rememberSaveable(saver = saver) { mutableStateOf(NavStack()) }
    return remember(state) { Navigator(state) }
}

/**
 * The whole app: the theme, the text scale and the screens of the navigation graph. The
 * theme, the palette, the reduced motion and the larger text come from the saved settings.
 */
@Composable
fun NightFleetApp(container: AppContainer) {
    val settings by container.settings.settings.collectAsState(initial = Settings())
    val navigator = rememberNavigator()
    var profile by remember { mutableStateOf(Profile()) }
    var difficulty by rememberSaveable { mutableStateOf(Difficulty.MEDIUM) }
    val route = navigator.current
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * settings.textScale())

    LaunchedEffect(route) {
        profile = runCatching { container.profile.load() }.getOrDefault(profile)
        music(container, route)
    }

    BackHandler(enabled = navigator.stack.canGoBack) { navigator.pop() }

    CompositionLocalProvider(LocalDensity provides scaled) {
        FleetTheme(
            themeId = settings.theme.toThemeId(),
            palette = settings.palette.toPalette(),
            grandAdmiral = settings.grandAdmiralActive(profile),
        ) {
            val colors = LocalFleetColors.current
            val themeId = settings.theme.toThemeId()
            // The one place that keeps every screen clear of the status bar and the navigation bar.
            // Screens must not add their own safe-drawing padding, or it is applied twice.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(colors.bg, colors.bg2)))
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
            ProvideRouteViewModels(navigator.stack) {
            when (route) {
                Route.Home -> HomeRoute(container, difficulty, navigator)
                is Route.SeaMap -> SeaMapScreen(
                    container = container,
                    sea = route.sea,
                    difficulty = route.difficulty,
                    onMission = { navigator.push(Route.MissionSheet(it, route.difficulty)) },
                    onSea = { sea, d ->
                        difficulty = d
                        if (sea in 1..Seas.COUNT) navigator.replaceTop(Route.SeaMap(sea, d))
                    },
                    onBack = { navigator.pop() },
                )
                is Route.MissionSheet -> MissionSheetScreen(
                    container = container,
                    mission = route.mission,
                    difficulty = route.difficulty,
                    onPlay = { navigator.push(Route.Mission(route.mission, route.difficulty)) },
                    onBack = { navigator.pop() },
                )
                is Route.Mission -> MissionFlow(
                    container = container,
                    themeId = themeId,
                    mission = route.mission,
                    difficulty = route.difficulty,
                    onExit = { navigator.popTo(Route.SeaMap(CampaignRules.seaOf(route.mission), route.difficulty)) },
                    onNextMission = { next -> navigator.replaceTop(Route.MissionSheet(next, route.difficulty)) },
                )
                Route.Daily -> DailyRoute(
                    container = container,
                    onBack = { navigator.pop() },
                    onPlayPuzzle = { date, d -> navigator.push(Route.Puzzle(PuzzleRequest.Daily(date, d).encode())) },
                    onPlayHunt = { date, d -> navigator.push(Route.DailyHunt(date, d)) },
                )
                is Route.DailyHunt -> DailyHuntFlow(
                    container = container,
                    themeId = themeId,
                    date = route.date,
                    difficulty = route.difficulty,
                    onExit = { navigator.popTo(Route.Daily) },
                )
                is Route.FreePlay -> FreePlayFlow(container, themeId, admiralId = route.admiralId, onExit = { navigator.pop() })
                Route.Duel -> DuoScreen(container, themeId, onExit = { navigator.pop() })
                Route.MoreModes -> ModesMenuScreen(
                    onEndless = { navigator.push(Route.EndlessPuzzles) },
                    onPatrol = { navigator.push(Route.Patrol) },
                    onTournament = { navigator.push(Route.Tournament) },
                    onBack = { navigator.pop() },
                )
                Route.Patrol -> PatrolFlow(container, themeId, onExit = { navigator.pop() })
                Route.Tournament -> TournamentFlow(
                    container = container,
                    themeId = themeId,
                    onExit = { navigator.pop() },
                    onCampaign = { sea, d ->
                        difficulty = d
                        navigator.replaceTop(Route.SeaMap(sea, d))
                    },
                )
                Route.EndlessPuzzles -> PuzzleEntryDialog(
                    onDismiss = { navigator.pop() },
                    onStart = { navigator.replaceTop(Route.Puzzle(it.encode())) },
                )
                is Route.Puzzle -> PuzzleRequest.decode(route.request)?.let { request ->
                    PuzzleRoute(
                        request = request,
                        onBack = { navigator.pop() },
                        onNext = { navigator.replaceTop(Route.Puzzle(it.encode())) },
                        // The daily puzzle has no next puzzle; the solved sheet only leaves.
                        showNext = request !is PuzzleRequest.Daily,
                    )
                } ?: ComingSoon(R.string.daily_coming_soon) { navigator.pop() }
                Route.Profile -> ProfileRoute(
                    container = container,
                    onBack = { navigator.pop() },
                    onOpenGallery = { navigator.push(Route.Gallery) },
                    onOpenAchievements = { navigator.push(Route.Achievements) },
                    onOpenStatistics = { navigator.push(Route.Statistics) },
                )
                Route.Gallery -> GalleryRoute(
                    container = container,
                    onBack = { navigator.pop() },
                    onPlayAgainst = { navigator.push(Route.FreePlay(it)) },
                )
                Route.Achievements -> AchievementsRoute(container, onBack = { navigator.pop() })
                Route.Statistics -> StatisticsRoute(container, onBack = { navigator.pop() })
                Route.Logbook -> LogbookRoute(
                    container = container,
                    onBack = { navigator.pop() },
                    onReplay = { navigator.push(Route.Replay(it)) },
                    onAnalysis = { navigator.push(Route.Analysis(it)) },
                )
                is Route.Analysis -> LogEntryRoute(container, route.logId, navigator) { entry, replay ->
                    AnalysisScreen(
                        input = AnalysisInput(logId = route.logId, replay = replay, stored = entry.analysis),
                        onSave = { analysis -> saveAnalysis(container, route.logId, analysis) },
                        onReplay = { navigator.replaceTop(Route.Replay(route.logId)) },
                        onBack = { navigator.pop() },
                    )
                }
                is Route.Replay -> LogEntryRoute(container, route.logId, navigator) { _, replay ->
                    ReplayScreen(payload = replay, themeId = themeId, onBack = { navigator.pop() })
                }
                Route.Settings -> SettingsRoute(
                    container,
                    onBack = { navigator.pop() },
                    audio = container.audio,
                    onOpenDiagnostics = { navigator.push(Route.Diagnostics) },
                )
                // The About section lives at the foot of Settings (privacy, source, licences).
                Route.About -> SettingsRoute(
                    container,
                    onBack = { navigator.pop() },
                    audio = container.audio,
                    onOpenDiagnostics = { navigator.push(Route.Diagnostics) },
                )
                Route.BoardGallery -> BoardGalleryScreen(
                    onBack = { navigator.pop() },
                    onDiagnostics = { navigator.push(Route.Diagnostics) },
                )
                Route.Diagnostics -> DiagnosticsRoute(container, onBack = { navigator.pop() })
            }
            }
            }
        }
    }
}

/** Home with the real campaign numbers. Continue opens the next mission sheet. */
@Composable
private fun HomeRoute(container: AppContainer, difficulty: Difficulty, navigator: Navigator) {
    val context = LocalContext.current
    var home by remember { mutableStateOf<CampaignHome?>(null) }
    var texts by remember { mutableStateOf(HomeTexts(missionTitle = "", admiralName = "")) }
    var savedMatch by remember { mutableStateOf<SavedMatch?>(null) }
    var loadedProfile by remember { mutableStateOf(Profile()) }
    var todayStars by remember { mutableStateOf(DailyChallenge.TodayStars(puzzle = 0, hunt = 0)) }
    LaunchedEffect(difficulty) {
        val profile = container.profile.load()
        loadedProfile = profile
        val summary = campaignHome(profile, difficulty)
        home = summary
        savedMatch = runCatching { container.matches.activeMatch(MatchKind.CAMPAIGN, profile) }.getOrNull()
        todayStars = DailyChallenge.today(profile, LocalDate.now(), difficulty)
        texts = HomeTexts(
            missionTitle = summary.currentMission?.let { missionTitle(context, container, it) }.orEmpty(),
            admiralName = admiralName(context, container, summary.sea),
        )
    }
    val dailyCaption = stringResource(
        R.string.home_row_daily_caption,
        DailyChallenge.starGlyphs(todayStars.puzzle),
        DailyChallenge.starGlyphs(todayStars.hunt),
    )
    home?.let { summary ->
        HomeScreen(
            home = summary,
            texts = texts.copy(dailyCaption = dailyCaption),
            hasSavedCampaign = savedMatch != null,
            onContinue = {
                when (val target = CampaignContinue.target(loadedProfile, savedMatch, difficulty)) {
                    is CampaignContinue.Target.Resume -> navigator.push(Route.Mission(target.mission, target.difficulty))
                    is CampaignContinue.Target.NextMission -> navigator.push(Route.MissionSheet(target.mission, target.difficulty))
                    CampaignContinue.Target.None -> Unit
                }
            },
            onSeaMap = { navigator.push(Route.SeaMap(summary.sea, difficulty)) },
            onDaily = { navigator.push(Route.Daily) },
            onDuel = { navigator.push(Route.Duel) },
            onFreePlay = { navigator.push(Route.FreePlay()) },
            onMoreModes = { navigator.push(Route.MoreModes) },
            onLogbook = { navigator.push(Route.Logbook) },
            onSettings = { navigator.push(Route.Settings) },
            onProfile = { navigator.push(Route.Profile) },
            onOpenBoardGallery = { navigator.push(Route.BoardGallery) },
        )
    }
}

/** Loads a logbook entry and its replay payload, then shows [content]. */
@Composable
private fun LogEntryRoute(
    container: AppContainer,
    logId: String,
    navigator: Navigator,
    content: @Composable (entry: pt.tiago.nightfleet.data.LogEntry, replay: JsonObject) -> Unit,
) {
    var loaded by remember(logId) { mutableStateOf<Pair<pt.tiago.nightfleet.data.LogEntry, JsonObject>?>(null) }
    var missing by remember(logId) { mutableStateOf(false) }
    LaunchedEffect(logId) {
        val entry = runCatching { container.logbook.load().firstOrNull { it.id == logId } }.getOrNull()
        val replay = entry?.replay as? JsonObject
        if (entry != null && replay != null) loaded = entry to replay else missing = true
    }
    loaded?.let { (entry, replay) -> content(entry, replay) }
    if (missing) ComingSoon(R.string.logbook_entry_missing) { navigator.pop() }
}

private suspend fun saveAnalysis(container: AppContainer, logId: String, analysis: AnalysisSummary) {
    runCatching {
        val entries = container.logbook.load()
        container.logbook.replaceAll(entries.map { if (it.id == logId) it.copy(analysis = analysis) else it })
    }
}

/** The daily challenge, dated when it opens: the date is kept across rotation as text. */
@Composable
private fun DailyRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onPlayPuzzle: (LocalDate, Difficulty) -> Unit,
    onPlayHunt: (LocalDate, Difficulty) -> Unit,
) {
    val date = LocalDate.parse(rememberSaveable { LocalDate.now().toString() })
    DailyScreen(
        container = container,
        date = date,
        onBack = onBack,
        onPlayPuzzle = { onPlayPuzzle(date, it) },
        onPlayHunt = { onPlayHunt(date, it) },
    )
}

@Composable
private fun ComingSoon(textRes: Int, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Text(stringResource(textRes))
            TextButton(onClick = onBack) { Text(stringResource(R.string.campaign_back)) }
        }
    }
}

private suspend fun missionTitle(context: Context, container: AppContainer, mission: Int): String {
    val dto: MissionDto = (container.catalog.mission(Seas.missionId(CampaignRules.seaOf(mission), mission)) as? CatalogResult.Ok)?.value
        ?: return ""
    return CampaignText.titleOf(context, dto.type, dto.tags)
}

private suspend fun admiralName(context: Context, container: AppContainer, sea: Int): String {
    val id = (container.catalog.sea(sea) as? CatalogResult.Ok)?.value?.admiralId.orEmpty()
    return context.getString(admiralNameRes(id))
}

/** Background music per screen: the home track on menus, the sea track on the sea map and in missions. */
private fun music(container: AppContainer, route: Route) {
    val track = when (route) {
        is Route.SeaMap -> seaTrack(route.sea)
        is Route.Mission -> seaTrack(CampaignRules.seaOf(route.mission))
        is Route.MissionSheet -> seaTrack(CampaignRules.seaOf(route.mission))
        else -> MusicTrack.HOME
    }
    container.audio.music.playTrack(track)
}

private fun seaTrack(sea: Int): MusicTrack = MusicTrack.entries.getOrElse(sea.coerceIn(1, Seas.COUNT)) { MusicTrack.HOME }
