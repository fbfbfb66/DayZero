package com.example.ui.components.ai

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.ai.assistant.DebugChoiceCardPayload
import com.example.ui.theme.*

@Composable
fun DebugChoiceCard(
    card: DebugChoiceCardPayload,
    onOptionSelected: (interactionId: String, optionId: String, optionLabel: String) -> Unit
) {
    val context = LocalContext.current

    AiBusinessCardContainer {
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = card.message,
            fontSize = 14.sp,
            color = TextSecondary,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.options.forEach { option ->
                OutlinedButton(
                    onClick = {
                        Log.d("DayZeroAiV2", "debug option clicked interactionId=${card.id} optionId=${option.id}")
                        onOptionSelected(card.id, option.id, option.label)
                        Toast.makeText(context, "已选择: ${option.label}", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGreen)
                ) {
                    Text(text = option.label)
                }
            }
        }
    }
}
