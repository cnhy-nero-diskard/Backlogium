package com.example.backlogium.ui.gapplan

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.example.backlogium.domain.gapplan.GapPlanReason
import com.example.backlogium.domain.gapplan.PlanIntensity
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.X
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
    val onGenerate: () -> Unit = {},
    val onRegenerate: () -> Unit = {},
    val onRemove: (PlanIntensity, Long) -> Unit = { _, _ -> },
    val onOfferReplacements: (PlanIntensity, Long) -> Unit = { _, _ -> },
    val onChooseReplacement: (Long) -> Unit = {},
    val onDismissReplacements: () -> Unit = {},
    val onReviewSave: (PlanIntensity) -> Unit = {},
    val onConfirmSave: () -> Unit = {},
    val onDismissConfirmation: () -> Unit = {},
    val onDone: () -> Unit = {},
)

/**
 * The gap-plan builder: one pushed surface carrying setup and results together.
 *
 * Setup stays visible above the result rather than being replaced by it, so adjusting an input and
 * regenerating is one screen rather than a round trip — and nothing about setup is written
 * anywhere, so going back costs nothing.
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
            onRegenerate = viewModel::regenerate,
            onRemove = viewModel::removeMember,
            onOfferReplacements = viewModel::offerReplacements,
            onChooseReplacement = { addAppId ->
                state.replacement?.let { replacement ->
                    viewModel.replaceMember(replacement.intensity, replacement.forAppId, addAppId)
                }
            },
            onDismissReplacements = viewModel::dismissReplacements,
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
 */
@Composable
fun GapPlanContent(state: GapPlanUiState, actions: GapPlanActions) {
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
                GapPlanPresentation.coverageDisclosures(result.coverage).forEach { disclosure ->
                    item {
                        Text(
                            text = disclosure,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                result.variants.forEach { variant ->
                    item(key = "variant-${variant.intensity}") {
                        GapPlanVariantCard(
                            variant = variant,
                            fullCapacityMinutes = result.fullCapacityMinutes,
                            onRemove = { appId -> actions.onRemove(variant.intensity, appId) },
                            onReplace = { appId ->
                                actions.onOfferReplacements(variant.intensity, appId)
                            },
                            onSave = { actions.onReviewSave(variant.intensity) },
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

    state.replacement?.let { replacement ->
        GapPlanReplacementDialog(
            replacement = replacement,
            onChoose = actions.onChooseReplacement,
            onDismiss = actions.onDismissReplacements,
        )
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions.onGenerate,
                    enabled = state.canGenerate,
                    modifier = Modifier.testTag(TAG_GENERATE),
                ) {
                    Text(if (state.result == null) "Build plans" else "Rebuild plans")
                }
                if (state.result != null) {
                    OutlinedButton(
                        onClick = actions.onRegenerate,
                        enabled = state.canGenerate,
                        modifier = Modifier.testTag(TAG_REGENERATE),
                    ) {
                        Icon(TablerIcons.Refresh, contentDescription = null)
                        Text("Regenerate", Modifier.padding(start = 8.dp))
                    }
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

/** The request's whole forecast and where it came from, stated once above all three variants. */
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
        }
    }
}

@Composable
private fun GapPlanVariantCard(
    variant: GapPlanVariantUi,
    fullCapacityMinutes: Int,
    onRemove: (Long) -> Unit,
    onReplace: (Long) -> Unit,
    onSave: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag(variantTag(variant.intensity))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = GapPlanPresentation.intensityName(variant.intensity),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = GapPlanPresentation.intensityRule(variant.intensity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = GapPlanPresentation.variantBudgetLine(variant),
                style = MaterialTheme.typography.bodyMedium,
            )
            GapPlanPresentation.withheldLine(variant, fullCapacityMinutes)?.let { withheld ->
                Text(
                    text = withheld,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (variant.isEmpty) {
                Text(
                    text = GapPlanPresentation.emptyVariantMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                variant.members.forEach { member ->
                    GapPlanMemberRow(
                        member = member,
                        onRemove = { onRemove(member.appId) },
                        onReplace = { onReplace(member.appId) },
                    )
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.testTag(saveTag(variant.intensity)),
                ) {
                    Text("Save as collection")
                }
            }
        }
    }
}

@Composable
private fun GapPlanMemberRow(
    member: GapPlanMemberUi,
    onRemove: () -> Unit,
    onReplace: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(memberTag(member.appId)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameIcon(iconUrl = member.iconUrl)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${UiFormat.minutes(member.remainingMinutes)} left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReplace, modifier = Modifier.testTag(replaceTag(member.appId))) {
                Text("Swap")
            }
            IconButton(onClick = onRemove, modifier = Modifier.testTag(removeTag(member.appId))) {
                Icon(TablerIcons.X, contentDescription = "Remove ${member.name}")
            }
        }
        // Fit is deliberately not repeated as a chip: the row above already states the remaining
        // time as its own subtitle, in the most prominent place on the card, and a chip beside it
        // saying the identical words is noise. The reason still exists on the candidate — it is
        // what guarantees a game recommended on duration alone has an explanation at all — this
        // is only a decision about not rendering the same fact twice.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            member.reasons.filterNot { it is GapPlanReason.Fit }.forEach { reason ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(GapPlanPresentation.reasonLabel(reason)) },
                )
            }
        }
    }
}

@Composable
private fun GapPlanReplacementDialog(
    replacement: GapPlanReplacementUi,
    onChoose: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Swap in another game") },
        text = {
            if (replacement.candidates.isEmpty()) {
                Text("Nothing else fits this plan's remaining time.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    replacement.candidates.take(MAX_REPLACEMENT_OPTIONS).forEach { candidate ->
                        TextButton(
                            onClick = { onChoose(candidate.appId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(replacementOptionTag(candidate.appId)),
                        ) {
                            Text(
                                text = "${candidate.name} · " +
                                    UiFormat.minutes(candidate.remainingMinutes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
                Text("${confirmation.memberCount} games")
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
internal const val TAG_REGENERATE = "gapplan-regenerate"
internal const val TAG_VALIDATION = "gapplan-validation"
internal const val TAG_FULL_CAPACITY = "gapplan-full-capacity"
internal const val TAG_CAPACITY_SOURCE = "gapplan-capacity-source"
internal const val TAG_SAVE_DIALOG = "gapplan-save-dialog"
internal const val TAG_CONFIRM_SAVE = "gapplan-confirm-save"
internal const val TAG_SAVE_ERROR = "gapplan-save-error"

internal fun variantTag(intensity: PlanIntensity) = "gapplan-variant-${intensity.name}"
internal fun saveTag(intensity: PlanIntensity) = "gapplan-save-${intensity.name}"
internal fun memberTag(appId: Long) = "gapplan-member-$appId"
internal fun removeTag(appId: Long) = "gapplan-remove-$appId"
internal fun replaceTag(appId: Long) = "gapplan-replace-$appId"
internal fun replacementOptionTag(appId: Long) = "gapplan-swap-option-$appId"

/** Keeps the swap list readable; the ranking already put the best candidates first. */
private const val MAX_REPLACEMENT_OPTIONS = 8

/** Matches the collection editor deadline formatting, so one date reads the same everywhere. */
private val gapPlanDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
