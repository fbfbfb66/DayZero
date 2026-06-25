package com.example.ui.components.ai

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.ui.theme.BorderLight
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.BrandRed
import com.example.ui.theme.CardBackground
import com.example.ui.theme.SurfaceSecondary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

object FoodDraftConfirmCardTestTags {
    const val NutritionCapsule = "food_draft_nutrition_capsule"
    const val ConfirmButton = "food_draft_confirm_button"
    const val CancelButton = "food_draft_cancel_button"
    const val WeightSection = "food_draft_weight_section"
    const val EditFoodButton = "food_draft_edit_food_button"
    const val DeleteFoodButton = "food_draft_delete_food_button"
    const val AddFoodButton = "food_draft_add_food_button"
}

private data class FoodEditTarget(
    val mealIndex: Int,
    val itemIndex: Int?
)

@Composable
fun FoodDraftConfirmCard(
    card: ShowConfirmCardPayload,
    onOptionSelected: (interactionId: String, optionId: String, optionLabel: String, payloadSummary: PayloadSummary) -> Unit,
    onDraftChanged: (interactionId: String, weightKg: Double?, meals: List<ConfirmCardMeal>) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val initialMeals = remember(card) { card.normalizedMeals() }

    var draftMeals by remember(card.id) { mutableStateOf(initialMeals) }
    var weightKg by remember(card.id) { mutableStateOf(card.weightKg) }
    var showWeightDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<FoodEditTarget?>(null) }

    fun persistDraft(meals: List<ConfirmCardMeal> = draftMeals, weight: Double? = weightKg) {
        onDraftChanged(card.id, weight, meals)
    }

    val totalCalories = remember(draftMeals) {
        draftMeals.sumOf { meal -> meal.items.sumOf { it.calories } }
    }
    val nutritionSummary = remember(draftMeals) {
        NutritionCapsuleCalculator.calculate(draftMeals)
    }

    var lastNonNullSummary by remember(card.id) { mutableStateOf<NutritionCapsuleSummary?>(null) }
    if (nutritionSummary != null) {
        lastNonNullSummary = nutritionSummary
    }

    if (showWeightDialog) {
        var inputWeight by remember { mutableStateOf(weightKg?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { showWeightDialog = false },
            title = { Text("填写体重") },
            text = {
                OutlinedTextField(
                    value = inputWeight,
                    onValueChange = { inputWeight = it },
                    label = { Text("体重 (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newWeight = inputWeight.toDoubleOrNull()
                        weightKg = newWeight
                        persistDraft(weight = newWeight)
                        showWeightDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWeightDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    showEditDialog?.let { target ->
        val item = target.itemIndex?.let { draftMeals[target.mealIndex].items[it] }
        var inputName by remember(target) { mutableStateOf(item?.name ?: "") }
        var inputAmount by remember(target) { mutableStateOf(item?.amountText ?: "") }
        var inputCalories by remember(target) { mutableStateOf(item?.calories?.toString() ?: "") }

        AlertDialog(
            onDismissRequest = { showEditDialog = null },
            title = { Text(if (item == null) "添加食物" else "编辑食物") },
            text = {
                Column {
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("食物名称") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputAmount,
                        onValueChange = { inputAmount = it },
                        label = { Text("份量 (例如 1个)") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputCalories,
                        onValueChange = { inputCalories = it },
                        label = { Text("估算热量 (kcal)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleanName = inputName.trim()
                        if (cleanName.isBlank()) {
                            Toast.makeText(context, "请填写食物名称", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val newCalories = inputCalories.toIntOrNull() ?: item?.calories ?: 0
                        val newAmount = inputAmount.ifBlank { null }
                        val newMeals = draftMeals.toMutableList()
                        val newItems = newMeals[target.mealIndex].items.toMutableList()
                        if (item == null) {
                            newItems.add(
                                NutritionCapsuleCalculator.newItem(
                                    name = cleanName,
                                    amountText = newAmount,
                                    calories = newCalories
                                )
                            )
                        } else {
                            newItems[target.itemIndex!!] = NutritionCapsuleCalculator.applyFoodEdit(
                                item = item,
                                name = cleanName,
                                amountText = newAmount,
                                calories = newCalories
                            )
                        }
                        newMeals[target.mealIndex] = newMeals[target.mealIndex].copy(items = newItems)
                        draftMeals = newMeals
                        persistDraft(meals = newMeals)
                        showEditDialog = null
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    AiBusinessCardContainer {
        Header(card = card, totalCalories = totalCalories)

        WeightSection(
            weightKg = weightKg,
            enabled = card.state == "pending",
            onClick = { showWeightDialog = true }
        )

        draftMeals.forEachIndexed { mealIndex, meal ->
            if (meal.items.isNotEmpty()) {
                MealSection(
                    meal = meal,
                    pending = card.state == "pending",
                    onEdit = { itemIndex -> showEditDialog = FoodEditTarget(mealIndex, itemIndex) },
                    onDelete = { itemIndex ->
                        val newMeals = draftMeals.toMutableList()
                        val newItems = newMeals[mealIndex].items.toMutableList()
                        newItems.removeAt(itemIndex)
                        newMeals[mealIndex] = newMeals[mealIndex].copy(items = newItems)
                        draftMeals = newMeals
                        persistDraft(meals = newMeals)
                    },
                    onAdd = { showEditDialog = FoodEditTarget(mealIndex, null) }
                )
            }
        }

        val isVisible = nutritionSummary != null && lastNonNullSummary != null
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(durationMillis = 240)) +
                    expandVertically(animationSpec = tween(durationMillis = 240)),
            exit = fadeOut(animationSpec = tween(durationMillis = 180)) +
                   shrinkVertically(animationSpec = tween(durationMillis = 180))
        ) {
            lastNonNullSummary?.let { summary ->
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    NutritionCapsule(card = card, summary = summary)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (card.state == "pending") {
            PendingButtons(
                card = card,
                weightKg = weightKg,
                draftMeals = draftMeals,
                onOptionSelected = onOptionSelected
            )
        } else {
            StatusText(state = card.state)
        }
    }
}

@Composable
private fun Header(card: ShowConfirmCardPayload, totalCalories: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "今日记录草稿",
                color = TextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            val dateText = card.date ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            Text(
                text = dateText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = TextPrimary
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "预计总热量",
                color = TextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$totalCalories",
                    color = BrandGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                Text(
                    text = " kcal",
                    color = BrandGreen,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun WeightSection(
    weightKg: Double?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(FoodDraftConfirmCardTestTags.WeightSection)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "体重记录 (kg)", fontWeight = FontWeight.Medium, color = TextPrimary)
            if (weightKg != null) {
                Text(text = "$weightKg kg", color = BrandGreen, fontWeight = FontWeight.Bold)
            } else {
                Text(text = "点击填写", color = TextTertiary)
            }
        }
    }
}

@Composable
private fun MealSection(
    meal: ConfirmCardMeal,
    pending: Boolean,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = meal.mealLabel ?: meal.mealType,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                val mealCalories = meal.items.sumOf { it.calories }
                Text(text = "$mealCalories kcal", color = BrandGreen, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(8.dp))

            meal.items.forEachIndexed { itemIndex, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.name, color = TextPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${item.amountText ?: "1份"} · ${item.calories} kcal",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            if (item.calorieConfidence == "estimated") {
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    color = BrandGreen.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "预计",
                                        color = BrandGreen,
                                        fontSize = 8.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    if (pending) {
                        IconButton(
                            onClick = { onEdit(itemIndex) },
                            modifier = Modifier
                                .size(24.dp)
                                .testTag(FoodDraftConfirmCardTestTags.EditFoodButton)
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "编辑",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { onDelete(itemIndex) },
                            modifier = Modifier
                                .size(24.dp)
                                .testTag(FoodDraftConfirmCardTestTags.DeleteFoodButton)
                        ) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "删除",
                                tint = Color.Red.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (pending) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onAdd,
                    modifier = Modifier.testTag(FoodDraftConfirmCardTestTags.AddFoodButton)
                ) {
                    Text("添加食物", color = BrandGreen)
                }
            }
        }
    }
}

@Composable
private fun NutritionCapsule(card: ShowConfirmCardPayload, summary: NutritionCapsuleSummary) {
    var startAnim by remember(card.id) { mutableStateOf(false) }
    LaunchedEffect(card.id) {
        startAnim = true
    }

    val revealProgress by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, delayMillis = 200, easing = FastOutSlowInEasing),
        label = "revealProgress"
    )

    val activeComponents = summary.components.filter { it.ratio > 0.0001f }

    val netCarbsText = "${formatNutritionGrams(summary.netCarbohydratesG)}克"
    val proteinText = "${formatNutritionGrams(summary.proteinG)}克"
    val fatText = "${formatNutritionGrams(summary.fatG)}克"
    val fiberText = "${formatNutritionGrams(summary.fiberG)}克"

    val netCarbsPct = "${round(summary.components[0].ratio * 100).toInt()}%"
    val proteinPct = "${round(summary.components[1].ratio * 100).toInt()}%"
    val fatPct = "${round(summary.components[2].ratio * 100).toInt()}%"
    val fiberPct = "${round(summary.components[3].ratio * 100).toInt()}%"

    val contentDesc = "营养构成：净碳水 $netCarbsText，占比 $netCarbsPct；蛋白质 $proteinText，占比 $proteinPct；脂肪 $fatText，占比 $fatPct；膳食纤维 $fiberText，占比 $fiberPct。"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FoodDraftConfirmCardTestTags.NutritionCapsule)
            .semantics(mergeDescendants = true) {
                contentDescription = contentDesc
            }
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "营养构成",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = "按克数占比",
                color = TextTertiary,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        val separatorColor = MaterialTheme.colorScheme.surface
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .drawWithContent {
                    clipRect(right = size.width * revealProgress) {
                        this@drawWithContent.drawContent()
                    }
                }
                .clip(RoundedCornerShape(999.dp))
                .background(SurfaceSecondary)
        ) {
            activeComponents.forEachIndexed { index, component ->
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(component.ratio)
                        .background(component.color())
                )
                if (index < activeComponents.size - 1) {
                    Spacer(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(separatorColor)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            summary.components.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { component ->
                        val globalIndex = summary.components.indexOf(component)
                        NutritionLegendItem(
                            component = component,
                            startAnim = startAnim,
                            index = globalIndex,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun NutritionPercentageRing(
    progress: Float,
    color: Color,
    percentageText: String,
    modifier: Modifier = Modifier
) {
    val trackColor = if (isSystemInDarkTheme()) Color(0xFF404040) else Color(0xFFEFEFEF)
    Box(modifier = modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            val clampedProgress = progress.coerceIn(0f, 1f)
            if (clampedProgress > 0.001f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = clampedProgress * 360f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Text(
            text = percentageText,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun NutritionLegendItem(
    component: NutritionCapsuleComponent,
    startAnim: Boolean,
    index: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(component.color())
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = component.label,
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
        }

        val animatedRatio by animateFloatAsState(
            targetValue = if (startAnim) component.ratio else 0f,
            animationSpec = tween(durationMillis = 1200, delayMillis = 400 + index * 100, easing = FastOutSlowInEasing),
            label = "ratio"
        )

        val percentage = round(animatedRatio * 100).toInt()
        val gramsText = formatNutritionGrams(component.grams)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${gramsText}g",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(10.dp))
            NutritionPercentageRing(
                progress = animatedRatio,
                color = component.color(),
                percentageText = "${percentage}%"
            )
        }
    }
}

@Composable
private fun PendingButtons(
    card: ShowConfirmCardPayload,
    weightKg: Double?,
    draftMeals: List<ConfirmCardMeal>,
    onOptionSelected: (interactionId: String, optionId: String, optionLabel: String, payloadSummary: PayloadSummary) -> Unit
) {
    val confirmOption = card.buttons.find { it.id == "confirm" }
    val otherOptions = card.buttons.filter { it.id != "confirm" }

    if (otherOptions.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            otherOptions.forEach { option ->
                OutlinedButton(
                    onClick = {
                        onOptionSelected(
                            card.id,
                            option.id,
                            option.label,
                            PayloadSummary(
                                originalText = card.originalText,
                                weightKg = weightKg,
                                meals = draftMeals
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(FoodDraftConfirmCardTestTags.CancelButton),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGreen)
                ) {
                    Text(text = option.label)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    confirmOption?.let { option ->
        Button(
            onClick = {
                onOptionSelected(
                    card.id,
                    option.id,
                    option.label,
                    PayloadSummary(
                        originalText = card.originalText,
                        weightKg = weightKg,
                        meals = draftMeals
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag(FoodDraftConfirmCardTestTags.ConfirmButton),
            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(text = option.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusText(state: String) {
    val statusText = if (state == "confirmed") "已确认记录" else "已取消记录"
    val statusColor = if (state == "confirmed") BrandGreen else TextTertiary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun ShowConfirmCardPayload.normalizedMeals(): List<ConfirmCardMeal> {
    val existingMeals = meals
    val legacyMealType = mealType
    return when {
        !existingMeals.isNullOrEmpty() -> existingMeals
        legacyMealType != null && items.isNotEmpty() -> {
            listOf(
                ConfirmCardMeal(
                    mealType = legacyMealType,
                    mealLabel = legacyMealType.toMealLabel(),
                    subtotalCalories = items.sumOf { it.calories },
                    items = items
                )
            )
        }
        else -> emptyList()
    }
}

private fun String.toMealLabel(): String {
    return when (lowercase()) {
        "breakfast" -> "早餐"
        "lunch" -> "午餐"
        "dinner" -> "晚餐"
        "snack" -> "加餐"
        else -> this
    }
}

@Composable
private fun NutritionCapsuleComponent.color(): Color {
    val isDark = isSystemInDarkTheme()
    return when (kind) {
        NutritionCapsuleComponentKind.NetCarbs -> if (isDark) Color(0xFFE5D4C0) else Color(0xFFDECBB7)
        NutritionCapsuleComponentKind.Protein -> if (isDark) Color(0xFFB1C2B0) else Color(0xFFA5BBA3)
        NutritionCapsuleComponentKind.Fat -> if (isDark) Color(0xFFE2B29F) else Color(0xFFDCA18A)
        NutritionCapsuleComponentKind.Fiber -> if (isDark) Color(0xFFB5A8C2) else Color(0xFF9E8FA9)
    }
}

private fun formatNutritionGrams(value: Float): String {
    if (!value.isFinite() || value < 0f) return ""
    val rounded = round(value)
    return if (abs(value - rounded) < 0.05f) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}
