package com.example.core

import com.example.ai.SocialTaskKind
import com.example.system.SocialPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ShareHandoffDestination {
    CHAT_ASK,
    SOCIAL_DRAFT,
    WHATSAPP_DRAFT
}

data class IncomingSharePayload(
    val id: Long = System.currentTimeMillis(),
    val text: String = "",
    val subject: String = "",
    val imageUris: List<String> = emptyList(),
    val destination: ShareHandoffDestination = ShareHandoffDestination.CHAT_ASK,
    val targetPlatform: SocialPlatform = SocialPlatform.INSTAGRAM,
    val targetTaskKind: SocialTaskKind = SocialTaskKind.CREATE_POST,
    val recipientHint: String = ""
)

object IncomingShareBridge {
    const val EXTRA_SHARE_ROUTE = "teena_share_route"
    const val EXTRA_OPEN_AUTOMATION_TAB = "teena_open_automation_tab"

    private val _pendingShare = MutableStateFlow<IncomingSharePayload?>(null)
    val pendingShare: StateFlow<IncomingSharePayload?> = _pendingShare.asStateFlow()

    fun publish(payload: IncomingSharePayload) {
        _pendingShare.value = payload
    }

    fun consume(): IncomingSharePayload? {
        val current = _pendingShare.value
        _pendingShare.value = null
        return current
    }
}
