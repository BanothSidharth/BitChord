package com.music.bitchord.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class BackupChange(
    @SerialName("event_id") val eventId: String,
    val type: String,
    val payload: JsonObject,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class DeviceRegistration(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val platform: String = "android",
)

@Serializable
data class Device(
    val id: String,
    val name: String,
    val platform: String = "android",
    val online: Boolean = false,
)

@Serializable
data class HealthResponse(
    val status: String,
    val service: String = "",
)

@Serializable
data class PlaybackUpdateRequest(
    @SerialName("device_id") val deviceId: String,
    val state: PlaybackState,
)

@Serializable
data class PlaybackCommandRequest(
    @SerialName("device_id") val deviceId: String,
    val action: String,
    val payload: PlaybackCommand,
    @SerialName("command_id") val commandId: String,
)

@Serializable
data class SyncPushRequest(val changes: List<BackupChange>)

@Serializable
data class SyncPushResponse(val cursor: String? = null)

@Serializable
data class SyncPullResponse(
    val cursor: String? = null,
    val changes: List<BackupChange> = emptyList(),
)

@Serializable
data class PlaybackState(
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("position_ms") val positionMs: Long = 0,
    val playing: Boolean = false,
    @SerialName("updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class PlaybackCommand(
    val command: String,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("command_id") val commandId: String? = null,
)
