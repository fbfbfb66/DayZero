package com.example.ui.sync

import com.example.data.sync.BackfillStatus
import com.example.data.sync.SyncHealthSnapshot
import java.util.concurrent.TimeUnit

enum class SyncStatusLevel {
    Hidden,
    Normal,
    Syncing,
    Pending,
    Warning,
    Error,
    RemoteDisabled
}

data class SyncStatusUiState(
    val visible: Boolean = false,
    val level: SyncStatusLevel = SyncStatusLevel.Hidden,
    val title: String = "",
    val message: String? = null,
    val lastSyncedText: String? = null,
    val pendingText: String? = null,
    val showManualSync: Boolean = false,
    val showManualRestore: Boolean = false,
    val showWarning: Boolean = false,
    val showDanger: Boolean = false,
    val requiresDangerousOperationWarning: Boolean = false,
    val isRefreshing: Boolean = false,
    val actionText: String? = null
)

object SyncStatusUiStateMapper {
    fun from(snapshot: SyncHealthSnapshot, showOnMainSurface: Boolean = true): SyncStatusUiState {
        val lastSyncedText = "最近同步：${formatRelativeTime(snapshot.lastSyncSuccessAt)}"
        val pendingTotal = snapshot.pendingCount + snapshot.retryableFailureCount + snapshot.waitingForAuthCount
        val pendingText = "等待同步：$pendingTotal 条"
        val dangerousOperationWarning = shouldWarnBeforeDangerousOperation(snapshot)

        if (!snapshot.remoteSyncEnabled) {
            return SyncStatusUiState(
                visible = showOnMainSurface,
                level = SyncStatusLevel.RemoteDisabled,
                title = "仅本地记录",
                message = "云端同步未启用，本地记录不受影响",
                lastSyncedText = "最近同步：从未同步",
                pendingText = pendingText,
                showManualSync = false,
                showManualRestore = false,
                requiresDangerousOperationWarning = dangerousOperationWarning
            )
        }

        if (snapshot.isRestoring) {
            return SyncStatusUiState(
                visible = showOnMainSurface,
                level = SyncStatusLevel.Syncing,
                title = "正在恢复云端记录",
                message = "可以继续聊天和记录，恢复会在后台完成",
                lastSyncedText = lastSyncedText,
                pendingText = pendingText,
                showManualSync = true,
                showManualRestore = false,
                requiresDangerousOperationWarning = dangerousOperationWarning
            )
        }

        if (!snapshot.hasRemoteIdentity) {
            return SyncStatusUiState(
                visible = showOnMainSurface,
                level = SyncStatusLevel.Pending,
                title = "云端身份准备中",
                message = "记录已保存在本地，云端备份稍后自动继续",
                lastSyncedText = lastSyncedText,
                pendingText = pendingText,
                showManualSync = true,
                showManualRestore = false,
                requiresDangerousOperationWarning = dangerousOperationWarning
            )
        }

        if (snapshot.initialRestoreAvailable) {
            return SyncStatusUiState(
                visible = showOnMainSurface,
                level = SyncStatusLevel.Pending,
                title = "可以检查云端记录",
                message = "如果云端有备份，会先恢复到本地记录",
                lastSyncedText = "最近恢复：${formatRelativeTime(snapshot.lastPullSuccessAt)}",
                pendingText = pendingText,
                showManualSync = true,
                showManualRestore = true,
                requiresDangerousOperationWarning = dangerousOperationWarning
            )
        }

        if (snapshot.fatalFailureCount > 0) {
            return SyncStatusUiState(
                visible = showOnMainSurface,
                level = SyncStatusLevel.Error,
                title = "部分记录需要处理",
                message = "本地记录仍然可用，云端同步遇到不可自动恢复的问题",
                lastSyncedText = lastSyncedText,
                pendingText = pendingText,
                showManualSync = true,
                showManualRestore = snapshot.hasRemoteIdentity,
                showWarning = true,
                showDanger = true,
                requiresDangerousOperationWarning = dangerousOperationWarning
            )
        }

        if (snapshot.retryableFailureCount > 0) {
            return SyncStatusUiState(
                visible = showOnMainSurface,
                level = SyncStatusLevel.Warning,
                title = "部分记录稍后自动重试",
                message = "同步失败不会影响本地已确认记录",
                lastSyncedText = lastSyncedText,
                pendingText = pendingText,
                showManualSync = true,
                showManualRestore = snapshot.hasRemoteIdentity,
                showWarning = true,
                requiresDangerousOperationWarning = dangerousOperationWarning
            )
        }

        if (snapshot.backfillStatus == BackfillStatus.RUNNING) {
            return SyncStatusUiState(
                visible = showOnMainSurface,
                level = SyncStatusLevel.Syncing,
                title = "正在整理本地记录",
                message = "可以继续聊天和记录，整理会在后台完成",
                lastSyncedText = lastSyncedText,
                pendingText = pendingText,
                showManualSync = true,
                showManualRestore = snapshot.hasRemoteIdentity,
                requiresDangerousOperationWarning = dangerousOperationWarning
            )
        }

        if (pendingTotal > 0 || snapshot.processingCount > 0) {
            return SyncStatusUiState(
                visible = showOnMainSurface,
                level = SyncStatusLevel.Pending,
                title = "等待同步",
                message = "记录已在本地保存，云端备份会在后台继续",
                lastSyncedText = lastSyncedText,
                pendingText = pendingText,
                showManualSync = true,
                showManualRestore = snapshot.hasRemoteIdentity,
                requiresDangerousOperationWarning = dangerousOperationWarning
            )
        }

        return SyncStatusUiState(
            visible = showOnMainSurface,
            level = SyncStatusLevel.Normal,
            title = if (snapshot.isHealthy) "同步正常" else "同步状态待检查",
            message = "本地记录和后台同步状态正常",
            lastSyncedText = lastSyncedText,
            pendingText = pendingText,
            showManualSync = true,
            showManualRestore = snapshot.hasRemoteIdentity,
            showWarning = !snapshot.isHealthy,
            requiresDangerousOperationWarning = dangerousOperationWarning
        )
    }

    fun shouldWarnBeforeDangerousOperation(snapshot: SyncHealthSnapshot): Boolean {
        return snapshot.pendingCount > 0 ||
            snapshot.retryableFailureCount > 0 ||
            snapshot.waitingForAuthCount > 0 ||
            snapshot.backfillStatus == BackfillStatus.RUNNING
    }

    private fun formatRelativeTime(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0L) return "从未同步"
        val ageMs = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ageMs)
        val hours = TimeUnit.MILLISECONDS.toHours(ageMs)
        val days = TimeUnit.MILLISECONDS.toDays(ageMs)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟前"
            hours < 24 -> "${hours} 小时前"
            else -> "${days} 天前"
        }
    }
}
