package com.example.backlogium.ui.gapplan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.PlanIntensity
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.gamedetail.GameDetailPresentation
import com.example.backlogium.ui.gamedetail.GameDetailScreen
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack
import compose.icons.tablericons.Refresh
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Every action the surface can take, hoisted into one value.
 *
 * A parameter object rather than a dozen lambdas so [GapPlanContent] stays stateless and directly
 * drivable from a behaviour test — the alternative is a test that can only reach the surface
 * through Hilt and a real ViewModel, which makes the interesting states hard to reach at all.
 */
data class GapPlanActions(
    val onTitleChange: (String) -> Unit = {},
    val onTargetDateChange: (LocalDate?) -> Unit = {},
    val onIntentChange: (GapPlanIntent) -> Unit = {},
    val onIncludeUnplayedChange: (Boolean) -> Unit = {},
    val onIncludeStartedChange: (Boolean) -> Unit = {},
    val onManualHoursChange: (Int) -> Unit = {},
    /** Build the first set, and reroll every set after it. There is only ever this one. */
    val onGenerate: () -> Unit = {},
    val onInspect: (Long) -> Unit = {},
    val onDismissInspection: () -> Unit = {},
    val onReviewSave: (PlanIntensity) -> Unit = {},
    val onConfirmSave: () -> Unit = {},
    val onDismissConfirmation: () -> Unit = {},
    val onDone: () -> Unit = {},
)

/**
 * The gap-plan builder: one pushed surface carrying setup and the three picks together.
 *
 * Setup stays visible above the result rather than being replaced by it, so adjusting an input and
 * rebuilding is one screen rather than a round trip — and nothing about setup is written anywhere,
 * so going back costs nothing.
 */
@Composable
fun GapPlanScreen(
    onDone: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    viewModel: GapPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdCollectionId) {
        state.createdCollectionId?.let { id ->
            viewModel.consumeCreatedCollection()
            onOpenCollection(id)
        }
    }

    GapPlanContent(
        state = state,
        actions = GapPlanActions(
            onTitleChange = viewModel::setAnticipatedTitle,
            onTargetDateChange = viewModel::setTargetDate,
            onIntentChange = viewModel::setIntent,
            onIncludeUnplayedChange = viewModel::setIncludeUnplayed,
            onIncludeStartedChange = viewModel::setIncludeStarted,
            onManualHoursChange = viewModel::setManualTotalHours,
            onGenerate = viewModel::generate,
            onInspect = viewModel::inspect,
            onDismissInspection = viewModel::dismissInspection,
            onReviewSave = viewModel::reviewSave,
            onConfirmSave = viewModel::confirmSave,
            onDismissConfirmation = viewModel::dismissConfirmation,
            onDone = onDone,
        ),
    )
}

/**
 * The stateless surface.
 *
 * Deliberately silent: every control here is navigation, filtering, or list interaction, none of
 * which is in the app's haptic vocabulary. No platform haptic call appears in this package.
 *
 * [detail] is the inspection overlay's body, defaulting to the existing collection game-detail
 * screen. It is a slot rather than a hard call because that screen resolves its own
 * `hiltViewModel`, which a behaviour test driving this composable from hoisted state cannot
 * provide — and the behaviour worth testing here is the overlay's *lifecycle*: that activating a
 * pick opens it, that both dismissal routes close it, and that neither rerolls. What the detail
 * itself renders is the game-detail screen's own contract, tested where it lives.
 */
@Composable
fun GapPlanContent(
    state: GapPlanUiState,
    actions: GapPlanActions,
    detail: @Composable (Long) -> Unit = { appId ->
        GameDetailScreen(
            appId = appId,
            presentation = GameDetailPresentation.COLLECTION_OVERLAY,
            viewModel = hiltViewModel(key = appId.toString()),
            onRemoved = actions.onDismissInspection,
            onDismiss = actions.onDismissInspection,
        )
    },
) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        GapPlanHeader(onDone = actions.onDone)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(TAG_PLAN_LIST),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GapPlanSetupCard(state = state, actions = actions) }

            state.result?.let { result ->
                item { GapPlanCapacityCard(result) }
                if (state.rebuildDidNotVary) {
                    item {
                        Text(
                            text = GapPlanPresentation.rebuildDidNotVaryMessage(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(TAG_NO_VARIATION),
                        )
                    }
                }
                GapPlanPresentation.coverageDisclosures(result.coverage).forEach { disclosure ->
                    item {
                        Text(
                            text = disclosure,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                result.picks.forEach { pick ->
                    item(key = "pick-${pick.intensity}") {
                        GapPlanPickCard(
                            pick = pick,
                            fullCapacityMinutes = result.fullCapacityMinutes,
                            onInspect = actions.onInspect,
                            onSave = { actions.onReviewSave(pick.intensity) },
                        )
                    }
                }
            }

            if (state.saveError) {
                item {
                    Text(
                        text = GapPlanPresentation.saveFailureMessage(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(TAG_SAVE_ERROR),
                    )
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }

    state.inspectingAppId?.let { appId ->
        GapPlanDetailOverlay(onDismiss = actions.onDismissInspection) { detail(appId) }
    }

    state.confirmation?.let { confirmation ->
        GapPlanSaveDialog(
            confirmation = confirmation,
            saving = state.saving,
            onConfirm = actions.onConfirmSave,
            onDismiss = actions.onDismissConfirmation,
        )
    }
}

@Composable
private fun GapPlanHeader(onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDone) {
            Icon(TablerIcons.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = "Plan the gap",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * The setup form. The manual-hours field appears only when Personal Pace is learning, and says why
 * it is being asked for rather than only that it is required.
 */
@Composable
private fun GapPlanSetupCard(state: GapPlanUiState, actions: GapPlanActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.setup.anticipatedTitle,
                onValueChange = actions.onTitleChange,
                label = { Text("Game or update you are waiting for") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_TITLE_FIELD),
            )
            GapPlanDateRow(
                value = state.setup.targetDate,
                onChange = actions.onTargetDateChange,
            )
            Text(
                text = if (state.requiresManualBudget) {
                    GapPlanPresentation.manualBudgetExplanation()
                } else {
                    GapPlanPresentation.reliablePaceExplanation()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.requiresManualBudget) {
                GapPlanHoursSlider(
                    hours = state.setup.manualTotalHours,
                    onChange = actions.onManualHoursChange,
                )
            }

            Text("How do you want to play them?", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GapPlanIntent.entries.forEach { intent ->
                    FilterChip(
                        selected = state.setup.intent == intent,
                        onClick = { actions.onIntentChange(intent) },
                        label = { Text(GapPlanPresentation.intentLabel(intent)) },
                    )
                }
            }

            GapPlanToggleRow(
                label = "Include games you have never started",
                checked = state.setup.includeUnplayed,
                onCheckedChange = actions.onIncludeUnplayedChange,
            )
            GapPlanToggleRow(
                label = "Include games you have already started",
                checked = state.setup.includeStarted,
                onCheckedChange = actions.onIncludeStartedChange,
            )

            state.validationError?.let { error ->
                Text(
                    text = GapPlanPresentation.validationMessage(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TAG_VALIDATION),
                )
            }

            // One control, and it genuinely rerolls. It was briefly a "Build plans" button beside
            // a separate "Regenerate" invoking the same action, which was worse than redundant
            // while picks were fully determined by the inputs: it promised a different result that
            // could not exist. Each generation now draws its own seed, so this produces a new set.
            Button(
                onClick = actions.onGenerate,
                enabled = state.canGenerate,
                modifier = Modifier.testTag(TAG_GENERATE),
            ) {
                if (state.result != null) {
                    Icon(TablerIcons.Refresh, contentDescription = null)
                    Text("Rebuild", Modifier.padding(start = 8.dp))
                } else {
                    Text("Suggest three games")
                }
            }
            if (state.generating) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}

/**
 * The target date, picked rather than typed.
 *
 * Matches the deadline-collection editor pattern: a read-only row with Pick and Clear actions over
 * a [DatePickerDialog]. Typing an ISO date on a phone keyboard to choose a day up to three years
 * out would be worse in every way, and a second way of entering the same kind of date would
 * eventually drift from the first.
 *
 * Clearing is offered because "not yet a date" has to stay representable. Generation is gated on a
 * date being present, and a field that always holds *some* date would quietly plan against a
 * default the player never chose.
 *
 * The picker works in UTC, like the collection editor, so the selected calendar day is the day the
 * player tapped rather than one shifted by the device offset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GapPlanDateRow(value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(TAG_DATE_ROW),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value?.format(gapPlanDateFormatter) ?: "No target date set",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = { showPicker = true }, modifier = Modifier.testTag(TAG_PICK_DATE)) {
                Text("Pick")
            }
            if (value != null) {
                TextButton(
                    onClick = { onChange(null) },
                    modifier = Modifier.testTag(TAG_CLEAR_DATE),
                ) {
                    Text("Clear")
                }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = value
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPicker = false
                        pickerState.selectedDateMillis?.let { millis ->
                            onChange(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * The one-off budget, as a coarse slider rather than a number field.
 *
 * The question being asked is "roughly how many hours do you expect to have", and a keyboard entry
 * would invite a precision the answer does not have: 137 is not a more honest estimate than 135.
 * Five-hour steps and a stated total keep it an estimate while still showing exactly what the plan
 * will be built from.
 *
 * Zero is a real position on the scale. It means "not chosen yet", which is what keeps generation
 * unavailable until the player actually answers.
 */
@Composable
private fun GapPlanHoursSlider(hours: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = if (hours > 0) {
                "About $hours hours before then"
            } else {
                "Drag to set the hours you expect to have"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(TAG_HOURS_VALUE),
        )
        Slider(
            value = hours.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..GapPlanViewModel.MAX_MANUAL_HOURS.toFloat(),
            // One stop per step, minus the two endpoints the range already provides.
            steps = (GapPlanViewModel.MAX_MANUAL_HOURS / GapPlanViewModel.MANUAL_HOURS_STEP) - 1,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_HOURS_SLIDER),
        )
    }
}

@Composable
private fun GapPlanToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** The request's whole forecast and where it came from, stated once above all three picks. */
@Composable
private fun GapPlanCapacityCard(result: GapPlanResultUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Before ${result.anticipatedTitle}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${result.targetDate} · ${GapPlanPresentation.intentBasisLabel(result.intent)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = GapPlanPresentation.fullCapacityLine(result.fullCapacityMinutes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(TAG_FULL_CAPACITY),
            )
            Text(
                text = GapPlanPresentation.capacitySource(result.provenance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_CAPACITY_SOURCE),
            )
            Text(
                text = GapPlanPresentation.selectionExplanation(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_SELECTION_EXPLANATION),
            )
        }
    }
}

/**
 * One tier: its share of the forecast, and the single game drawn for it.
 *
 * Both capacity figures appear — this tier's share here, the request's full forecast on the card
 * above — because a Relaxed card stating only its own smaller number would hide the time the 70%
 * intensity deliberately withheld.
 */
@Composable
private fun GapPlanPickCard(
    pick: GapPlanPickUi,
    fullCapacityMinutes: Int,
    onInspect: (Long) -> Unit,
    onSave: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag(pickTag(pick.intensity))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = GapPlanPresentation.intensityName(pick.intensity),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = GapPlanPresentation.intensityRule(pick.intensity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = GapPlanPresentation.pickShareLine(pick),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(shareTag(pick.intensity)),
            )
            GapPlanPresentation.withheldLine(pick, fullCapacityMinutes)?.let { withheld ->
                Text(
                    text = withheld,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(withheldTag(pick.intensity)),
                )
            }

            val game = pick.game
            if (game == null) {
                Text(
                    text = GapPlanPresentation.emptyPickMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(emptyTag(pick.intensity)),
                )
            } else {
                GapPlanGameRow(game = game, onInspect = { onInspect(game.appId) })
                Button(
                    onClick = onSave,
                    modifier = Modifier.testTag(saveTag(pick.intensity)),
                ) {
                    Text("Save as collection")
                }
            }
        }
    }
}

/**
 * The picked game, and the facts that let the player judge it.
 *
 * The whole row is the inspection control. Activating it opens the game-detail overlay rather than
 * navigating, so judging a suggestion never costs leaving the result — and it changes nothing about
 * the picks, which is what lets a player open all three in turn and still accept the first.
 */
@Composable
private fun GapPlanGameRow(game: GapPlanGameUi, onInspect: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspect)
            .testTag(gameTag(game.appId))
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameIcon(iconUrl = game.iconUrl)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // The remaining time is stated here and only here. It is the most prominent place
                // on the card, and a fact label repeating the identical words beside it is noise.
                Text(
                    text = GapPlanPresentation.remainingLine(game),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (game.isFamilyShared) {
                Text(
                    text = "Family shared",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(familySharedTag(game.appId)),
                )
            }
        }

        // Omitted entirely when no genres are cached, rather than rendered as an empty line: an
        // unavailable fact must not look like a fact with no content.
        GapPlanPresentation.genreLine(game)?.let { genres ->
            Text(
                text = genres,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(genreTag(game.appId)),
            )
        }

        // Rendered as read-only labels rather than disabled chips. A fact is something the player
        // is being asked to weigh, and a disabled control is styled to say "unavailable" — on
        // device the greyed-out text read as switched off rather than as information, which is the
        // opposite of the point. These are never tappable, so nothing is lost by not looking it.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            game.facts.forEach { fact ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = GapPlanPresentation.factLabel(fact),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * A pick's detail, in place.
 *
 * The **existing** collection game-detail overlay, not a second compact detail built for this
 * surface: that treatment already has specified artwork fallbacks and dismissal behaviour, and a
 * parallel implementation would drift from them. `skipPartiallyExpanded = false` matches the
 * collection surface exactly, which is what leaves the result visible above the sheet — the
 * property that makes inspecting a pick cheaper than navigating to it.
 *
 * [BackHandler] is explicit rather than relying on the sheet's own back handling, because the
 * dismissal must clear the ViewModel's inspection state too — a sheet that closed itself while the
 * state still named a game would reopen on the next recomposition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GapPlanDetailOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        modifier = Modifier.testTag(TAG_DETAIL_OVERLAY),
    ) {
        content()
    }
}

/** Restates exactly what is about to be written, before anything is. */
@Composable
private fun GapPlanSaveDialog(
    confirmation: GapPlanSaveConfirmationUi,
    saving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Create this collection?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(confirmation.collectionName, style = MaterialTheme.typography.titleSmall)
                Text("Deadline ${confirmation.targetDate}")
                Text(GapPlanPresentation.intentBasisLabel(confirmation.intent))
                Text(confirmation.gameName)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !saving,
                modifier = Modifier.testTag(TAG_CONFIRM_SAVE),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
        modifier = Modifier.testTag(TAG_SAVE_DIALOG),
    )
}

/** Stable handles for behaviour tests, so an assertion never depends on user-facing wording. */

/**
 * The scrolling container. A behaviour test has to drive *this* to reach a row, because a lazy
 * list has not composed the items that are still off screen and a node-level scroll cannot find
 * what does not yet exist.
 */
internal const val TAG_PLAN_LIST = "gapplan-list"
internal const val TAG_TITLE_FIELD = "gapplan-title"
internal const val TAG_DATE_ROW = "gapplan-date-row"
internal const val TAG_PICK_DATE = "gapplan-pick-date"
internal const val TAG_CLEAR_DATE = "gapplan-clear-date"
internal const val TAG_HOURS_SLIDER = "gapplan-hours-slider"
internal const val TAG_HOURS_VALUE = "gapplan-hours-value"
internal const val TAG_GENERATE = "gapplan-generate"
internal const val TAG_VALIDATION = "gapplan-validation"
internal const val TAG_FULL_CAPACITY = "gapplan-full-capacity"
internal const val TAG_CAPACITY_SOURCE = "gapplan-capacity-source"
internal const val TAG_SELECTION_EXPLANATION = "gapplan-selection-explanation"
internal const val TAG_NO_VARIATION = "gapplan-no-variation"
internal const val TAG_DETAIL_OVERLAY = "gapplan-detail-overlay"
internal const val TAG_SAVE_DIALOG = "gapplan-save-dialog"
internal const val TAG_CONFIRM_SAVE = "gapplan-confirm-save"
internal const val TAG_SAVE_ERROR = "gapplan-save-error"

internal fun pickTag(intensity: PlanIntensity) = "gapplan-pick-${intensity.name}"
internal fun shareTag(intensity: PlanIntensity) = "gapplan-share-${intensity.name}"
internal fun withheldTag(intensity: PlanIntensity) = "gapplan-withheld-${intensity.name}"
internal fun emptyTag(intensity: PlanIntensity) = "gapplan-empty-${intensity.name}"
internal fun saveTag(intensity: PlanIntensity) = "gapplan-save-${intensity.name}"
internal fun gameTag(appId: Long) = "gapplan-game-$appId"
internal fun genreTag(appId: Long) = "gapplan-genres-$appId"
internal fun familySharedTag(appId: Long) = "gapplan-family-shared-$appId"

/** Matches the collection editor deadline formatting, so one date reads the same everywhere. */
private val gapPlanDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
