package com.example.ui.components.ai

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.ai.assistant.AskMissingInfoCardPayload
import com.example.ui.theme.*

@Composable
fun AskMissingInfoCard(
    card: AskMissingInfoCardPayload,
    onOptionSelected: (interactionId: String, optionId: String, optionLabel: String, field: String, originalText: String) -> Unit
) {
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
        Spacer(modifier = Modifier.height(12.dp))
        
        Surface(
            color = SurfaceSecondary,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "\"${card.originalText}\"",
                fontSize = 13.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = TextTertiary,
                modifier = Modifier.padding(12.dp).fillMaxWidth()
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.options.chunked(2).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowOptions.forEach { option ->
                        OutlinedButton(
                            onClick = {
                                Log.d("DayZeroAiV2", "missing info option clicked interactionId=${card.id} field=${card.field} optionId=${option.id}")
                                onOptionSelected(card.id, option.id, option.label, card.field, card.originalText)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGreen),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, BorderNormal)
                        ) {
                            Text(text = option.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (rowOptions.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
