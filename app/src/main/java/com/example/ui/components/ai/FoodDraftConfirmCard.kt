package com.example.ui.components.ai

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun FoodDraftConfirmCard(
    card: ShowConfirmCardPayload,
    onOptionSelected: (interactionId: String, optionId: String, optionLabel: String, payloadSummary: PayloadSummary) -> Unit
) {
    val context = LocalContext.current
    
    // Normalize payload to meals array internally
    val initialMeals = remember(card) {
        if (!card.meals.isNullOrEmpty()) {
            card.meals
        } else if (card.mealType != null && card.items.isNotEmpty()) {
            listOf(
                ConfirmCardMeal(
                    mealType = card.mealType,
                    mealLabel = when(card.mealType.lowercase()) {
                        "breakfast" -> "早餐"
                        "lunch" -> "午餐"
                        "dinner" -> "晚餐"
                        "snack" -> "加餐"
                        else -> card.mealType
                    },
                    subtotalCalories = card.items.sumOf { it.calories },
                    items = card.items
                )
            )
        } else {
            emptyList()
        }
    }
    
    var draftMeals by remember(card.id, card.state) { mutableStateOf(initialMeals) }
    var weightKg by remember(card.id, card.state) { mutableStateOf(card.weightKg) }
    
    val totalCalories = remember(draftMeals) {
        draftMeals.sumOf { meal -> meal.items.sumOf { it.calories } }
    }
    
    var showWeightDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<Pair<Int, Int>?>(null) } // mealIndex, itemIndex
    
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
                TextButton(onClick = {
                    weightKg = inputWeight.toDoubleOrNull()
                    showWeightDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showWeightDialog = false }) { Text("取消") }
            }
        )
    }
    
    showEditDialog?.let { (mIndex, iIndex) ->
        val item = draftMeals[mIndex].items[iIndex]
        var inputName by remember { mutableStateOf(item.name) }
        var inputAmount by remember { mutableStateOf(item.amountText ?: "") }
        var inputCalories by remember { mutableStateOf(item.calories.toString()) }
        
        AlertDialog(
            onDismissRequest = { showEditDialog = null },
            title = { Text("编辑食物") },
            text = {
                Column {
                    OutlinedTextField(value = inputName, onValueChange = { inputName = it }, label = { Text("食物名称") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = inputAmount, onValueChange = { inputAmount = it }, label = { Text("分量 (例如 1个)") })
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
                TextButton(onClick = {
                    val newCal = inputCalories.toIntOrNull() ?: item.calories
                    val newMeals = draftMeals.toMutableList()
                    val newItems = newMeals[mIndex].items.toMutableList()
                    newItems[iIndex] = item.copy(
                        name = inputName,
                        amountText = inputAmount.ifBlank { null },
                        calories = newCal,
                        calorieConfidence = "user_edited"
                    )
                    newMeals[mIndex] = newMeals[mIndex].copy(items = newItems)
                    draftMeals = newMeals
                    showEditDialog = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = null }) { Text("取消") }
            }
        )
    }

    AiBusinessCardContainer {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "今日记录草稿", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                val dateStr = card.date ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    color = TextPrimary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "预估总热量", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = "$totalCalories", color = BrandGreen, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Text(text = " kcal", color = BrandGreen, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
        
        // Weight Section
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = card.state == "pending") { showWeightDialog = true },
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderLight),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
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

        // Meal Section
        draftMeals.forEachIndexed { mIndex, meal ->
            if (meal.items.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize(),
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
                            Text(text = meal.mealLabel ?: meal.mealType, fontWeight = FontWeight.Bold, color = TextPrimary)
                            val mealCalories = meal.items.sumOf { it.calories }
                            Text(text = "$mealCalories kcal", color = BrandGreen, fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        meal.items.forEachIndexed { iIndex, item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                                                    text = "预估",
                                                    color = BrandGreen,
                                                    fontSize = 8.sp,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                                if (card.state == "pending") {
                                    IconButton(onClick = { showEditDialog = mIndex to iIndex }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(onClick = {
                                        val newMeals = draftMeals.toMutableList()
                                        val newItems = newMeals[mIndex].items.toMutableList()
                                        newItems.removeAt(iIndex)
                                        newMeals[mIndex] = newMeals[mIndex].copy(items = newItems)
                                        draftMeals = newMeals
                                    }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "删除", tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (card.state == "pending") {
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
                            modifier = Modifier.weight(1f),
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
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(text = option.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val statusText = if (card.state == "confirmed") "已确认记录" else "已取消记录"
            val statusColor = if (card.state == "confirmed") BrandGreen else TextTertiary
            
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
    }
}
