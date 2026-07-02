package com.example.domain.model.ai.assistant

/**
 * Pure domain result of preparing a persisted user message with media for an AI vision request.
 *
 * This object is intentionally transient: it lives only in memory during a single assistant turn
 * and must never be serialized to Room, sync queues, or logs.
 */
data class PreparedVisionRequest(
    val requestId: String,
    val conversationId: String,
    val userMessageId: String,
    val effectiveAiText: String,
    val attachments: List<PreparedVisionAttachment>
) {
    val totalByteSize: Long
        get() = attachments.fold(0L) { acc, attachment -> acc + attachment.byteSize }

    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(userMessageId.isNotBlank()) { "userMessageId must not be blank" }
        require(attachments.size in ALLOWED_ATTACHMENT_COUNT_RANGE) {
            "attachments count must be in $ALLOWED_ATTACHMENT_COUNT_RANGE, got ${attachments.size}"
        }
    }

    companion object {
        val ALLOWED_ATTACHMENT_COUNT_RANGE = 1..6
        const val MAX_SINGLE_ATTACHMENT_BYTES = 640L * 1024L
        const val MAX_TOTAL_ATTACHMENT_BYTES = 4L * 1024L * 1024L
        const val MIME_TYPE_JPEG = "image/jpeg"
        const val IMAGE_ONLY_PROMPT = "请识别这些图片中的食物，并帮我生成饮食记录确认卡。"
    }
}

data class PreparedVisionAttachment(
    val mediaId: String,
    val mimeType: String,
    val base64: String,
    val byteSize: Long
) {
    init {
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
        require(mimeType == PreparedVisionRequest.MIME_TYPE_JPEG) { "unsupported mimeType: $mimeType" }
        require(base64.isNotBlank()) { "base64 must not be blank" }
        require(byteSize > 0L) { "byteSize must be positive" }
    }

    override fun toString(): String {
        return "PreparedVisionAttachment(mediaId=$mediaId, mimeType=$mimeType, byteSize=$byteSize, base64Length=${base64.length})"
    }
}

sealed class VisionPreparationFailure : Throwable() {
    abstract val errorCode: String

    data class MessageNotFound(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "MESSAGE_NOT_FOUND"
    }

    data class InvalidMessage(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "INVALID_MESSAGE"
    }

    data class InvalidMediaContract(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "INVALID_MEDIA_CONTRACT"
    }

    data class MediaNotFound(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "MEDIA_NOT_FOUND"
    }

    data class MediaNotReady(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "MEDIA_NOT_READY"
    }

    data class MediaBindingMismatch(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "MEDIA_BINDING_MISMATCH"
    }

    data class MasterFileMissing(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "MASTER_FILE_MISSING"
    }

    data class UnsafePath(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "UNSAFE_PATH"
    }

    data class DecodeFailed(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "DECODE_FAILED"
    }

    data class OutOfMemory(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "OUT_OF_MEMORY"
    }

    data class WriteFailed(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "WRITE_FAILED"
    }

    data class ImageTooLarge(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "IMAGE_TOO_LARGE"
    }

    data class TotalPayloadTooLarge(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "TOTAL_PAYLOAD_TOO_LARGE"
    }

    data class Base64Failed(override val message: String) : VisionPreparationFailure() {
        override val errorCode: String = "BASE64_FAILED"
    }

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : VisionPreparationFailure() {
        override val errorCode: String = "UNKNOWN"
    }

    override fun toString(): String {
        return "VisionPreparationFailure(code=$errorCode, message=$message)"
    }
}
