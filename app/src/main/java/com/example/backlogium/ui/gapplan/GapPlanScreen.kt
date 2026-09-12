package com.example.backlogium.ui.gapplan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.backlogium.ui.gamedetail.GameDetailPresentation
import com.example.backlogium.ui.gamedetail.GameDetailScreen
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.ArrowBack
import compose.icons.tablericons.Clock
import compose.icons.tablericons.Pencil
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
    /** Produce the first set, and reroll every set after it. There is only ever this one. */
    val onGenerate: () -> Unit = {},
    val onInspect: (Long) -> Unit = {},
    val onDismissInspection: () -> Unit = {},
    val onReviewSave: (PlanIntensity) -> Unit = {},
    val onConfirmSave: () -> Unit = {},
    val onDismissConfirmation: () -> Unit = {},
    val onDone: () -> Unit = {},
)

/**
 * The recommendation builder: one pushed surface carrying setup and three recommendations.
 *
 * Nothing about setup is written anywhere, so going back costs nothing.
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
 * Once a result exists the setup inputs collapse to one summary row. Keeping the form above the
 * recommendations is what made scrolling mandatory, and after generating, the inputs are not the
 * thing the player is looking at. A separate setup destination was rejected instead: adjusting an
 * input and rebuilding is the core loop, and a round trip would make the cheapest action the
 * slowest one.
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

    var editingSetup by rememberSaveable { mutableStateOf(false) }
    val result = state.result
    val showingSetup = result == null || editingSetup

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GapPlanHeader(onDone = actions.onDone)

        if (showingSetup) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag(TAG_SETUP_FORM),
            ) {
                GapPlanSetupCard(
                    state = state,
                    actions = actions,
                    onGenerate = {
                        editingSetup = false
                        actions.onGenerate()
                    },
                )
            }
        }

        if (result != null && !showingSetup) {
            GapPlanSummaryRow(
                result = result,
                generating = state.generating,
                onEdit = { editingSetup = true },
                onRebuild = actions.onGenerate,
            )

            if (state.rebuildDidNotVary) {
                Notice(
                    text = GapPlanPresentation.rebuildDidNotVaryMessage(),
                    modifier = Modifier.testTag(TAG_NO_VARIATION),
                )
            }
            GapPlanPresentation.coverageDisclosures(result.coverage).forEach { disclosure ->
                Notice(text = disclosure)
            }

            // A plain Column, not a lazy list: three cards are not a list, and a lazy container
            // would leave the third uncomposed until it was scrolled to — which is exactly the
            // behaviour this surface is meant not to have. The scroll modifier is a safety net for
            // a very small screen or a very large font, not the expected way to read this.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag(TAG_PLAN_LIST),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                result.picks.forEach { pick ->
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
            Text(
                text = GapPlanPresentation.saveFailureMessage(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag(TAG_SAVE_ERROR),
            )
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDone) {
            Icon(TablerIcons.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = "Recommendations",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * The collapsed request: what was asked for, how much time it found, and the two things that can
 * be done about it.
 *
 * The rebuild control lives here once the form is closed, so there is still exactly one control
 * that produces a new set — the form's button and this one are never on screen together.
 */
@Composable
private fun GapPlanSummaryRow(
    result: GapPlanResultUi,
    generating: Boolean,
    onEdit: () -> Unit,
    onRebuild: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag(TAG_SETUP_SUMMARY)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = GapPlanPresentation.requestSummary(result),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        TablerIcons.Clock,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = GapPlanPresentation.fullCapacity(result.fullCapacityMinutes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TAG_FULL_CAPACITY),
                    )
                    GapPlanPresentation.capacityCaveat(result.provenance)?.let { caveat ->
                        Text(
                            text = "· $caveat",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(TAG_CAPACITY_SOURCE),
                        )
                    }
                    Text(
                        text = "· ${GapPlanPresentation.selectionExplanation()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TAG_SELECTION_EXPLANATION),
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.testTag(TAG_EDIT_SETUP)) {
                Icon(TablerIcons.Pencil, contentDescription = "Change what you are waiting for")
            }
            IconButton(
                onClick = onRebuild,
                enabled = !generating,
                modifier = Modifier.testTag(TAG_GENERATE),
            ) {
                Icon(
                    TablerIcons.Refresh,
                    contentDescription = "Recommend three more",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** A short, icon-led aside. Never a paragraph — the surface has no room for one. */
@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            TablerIcons.AlertCircle,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The setup form. The manual-hours field appears only when Personal Pace is learning, and says why
 * it is being asked for rather than only that it is required.
 */
@Composable
private fun GapPlanSetupCard(
    state: GapPlanUiState,
    actions: GapPlanActions,
    onGenerate: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.setup.anticipatedTitle,
                onValueChange = actions.onTitleChange,
                label = { Text("Waiting for") },
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
                label = "Never started",
                checked = state.setup.includeUnplayed,
                onCheckedChange = actions.onIncludeUnplayedChange,
            )
            GapPlanToggleRow(
                label = "Already started",
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

            Button(
                onClick = onGenerate,
                enabled = state.canGenerate,
                modifier = Modifier.testTag(TAG_GENERATE),
            ) {
                Text("Recommend three games")
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
            text = if (hours > 0) "About $hours hours" else "Drag to set your hours",
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

/**
 * A pick's detail, in place.
 *
 * The **existing** collection game-detail overlay, not a second compact detail built for this
 * surface: that treatment already has specified artwork fallbacks and dismissal behaviour, and a
 * parallel implementation would drift from them. `skipPartiallyExpanded = false` matches the
 * collection surface exactly, which is what leaves the recommendations visible above the sheet.
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
 * The container holding the three recommendation cards.
 *
 * A plain scrolling Column rather than a lazy list, so every card is composed whether or not it is
 * on screen — which is both what the surface promises the player and what lets a test assert all
 * three without driving a scroll.
 */
internal const val TAG_PLAN_LIST = "gapplan-list"
internal const val TAG_SETUP_FORM = "gapplan-setup-form"
internal const val TAG_SETUP_SUMMARY = "gapplan-setup-summary"
internal const val TAG_EDIT_SETUP = "gapplan-edit-setup"
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
internal fun hookTag(intensity: PlanIntensity) = "gapplan-hook-${intensity.name}"
internal fun emptyTag(intensity: PlanIntensity) = "gapplan-empty-${intensity.name}"
internal fun saveTag(intensity: PlanIntensity) = "gapplan-save-${intensity.name}"
internal fun gameTag(appId: Long) = "gapplan-game-$appId"
internal fun genreTag(appId: Long) = "gapplan-genres-$appId"
internal fun reviewTag(appId: Long) = "gapplan-review-$appId"
internal fun playersTag(appId: Long) = "gapplan-players-$appId"
internal fun affinityTag(appId: Long) = "gapplan-affinity-$appId"
internal fun progressTag(appId: Long) = "gapplan-progress-$appId"
internal fun familySharedTag(appId: Long) = "gapplan-family-shared-$appId"

/** Matches the collection editor deadline formatting, so one date reads the same everywhere. */
private val gapPlanDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
