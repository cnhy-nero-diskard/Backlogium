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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.PlanIntensity
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.X
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The gap-plan builder: one pushed surface carrying setup and results together.
 *
 * Setup stays visible above the result rather than being replaced by it, so adjusting an input and
 * regenerating is one screen rather than a round trip — and nothing about setup is written
 * anywhere, so going back costs nothing.
 *
 * Deliberately silent: every control here is navigation, filtering, or list interaction, none of
 * which is in the app's haptic vocabulary. No platform haptic call appears in this package.
 */
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
    val onManualHoursChange: (String) -> Unit = {},
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
            modifier = Modifier.fillMaxSize(),
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
            GapPlanDateField(
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
                OutlinedTextField(
                    value = state.setup.manualTotalHours,
                    onValueChange = actions.onManualHoursChange,
                    label = { Text("Hours you expect to have") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_HOURS_FIELD),
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
 * A plain ISO date field rather than a picker dialog.
 *
 * A malformed or partial entry clears the date instead of guessing one: generation is gated on a
 * date being present, so "not yet a date" has to be representable.
 */
@Composable
private fun GapPlanDateField(value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            onChange(
                try {
                    LocalDate.parse(text.trim())
                } catch (_: DateTimeParseException) {
                    null
                },
            )
        },
        label = { Text("Target date (YYYY-MM-DD)") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_DATE_FIELD),
    )
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            member.reasons.forEach { reason ->
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
internal const val TAG_TITLE_FIELD = "gapplan-title"
internal const val TAG_DATE_FIELD = "gapplan-date"
internal const val TAG_HOURS_FIELD = "gapplan-hours"
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
