package com.example.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CardBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun SyncStatusPanel(
    state: SyncStatusUiState,
    onManualSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.visible) return

    Card(
        colors = CardDefaults.cardColors(containerColor = statusBackground(state.level)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "数据同步",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (state.showManualSync) {
                    TextButton(
                        onClick = onManualSync,
                        enabled = !state.isRefreshing
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = TextSecondary
                            )
                        } else {
                            Text("立即同步", color = TextPrimary)
                        }
                    }
                }
            }

            Text(
                text = state.title,
                color = statusTextColor(state.level),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            state.message?.let { message ->
                Text(
                    text = message,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                state.lastSyncedText?.let {
                    Text(text = it, color = TextSecondary, fontSize = 12.sp)
                }
                state.pendingText?.let {
                    Text(text = it, color = TextSecondary, fontSize = 12.sp)
                }
            }

            state.actionText?.let {
                Text(text = it, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

private fun statusBackground(level: SyncStatusLevel): Color {
    return when (level) {
        SyncStatusLevel.Warning,
        SyncStatusLevel.Error -> Color(0xFFFFF4E4)
        SyncStatusLevel.RemoteDisabled -> Color(0xFFF4F4F1)
        else -> CardBackground
    }
}

private fun statusTextColor(level: SyncStatusLevel): Color {
    return when (level) {
        SyncStatusLevel.Warning -> Color(0xFF8A5A00)
        SyncStatusLevel.Error -> Color(0xFF9F2D20)
        SyncStatusLevel.RemoteDisabled -> TextSecondary
        SyncStatusLevel.Pending,
        SyncStatusLevel.Syncing -> TextPrimary
        else -> TextPrimary
    }
}
