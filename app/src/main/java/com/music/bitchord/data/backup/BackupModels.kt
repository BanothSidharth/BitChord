package com.music.bitchord.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BackupChange(
    @SerialName("event_id") val eventId: String,
    val type: String,
    val payload: kotlinx.serialization.json.JsonObject,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class DeviceRegistration(
    @SerialName("device_name") val deviceName: String,
    @SerialName("platform") val platform: String = "android",
)

@Serializable
data class Device(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val online: Boolean = false,
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
    val positionMs: Long = 0,
    val playing: Boolean = false,
    @SerialName("updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class PlaybackUpdate(
    @SerialName("device_id") val deviceId: String,
    val state: PlaybackState,
)

@Serializable
data class PlaybackCommand(
    val command: String,
    val positionMs: Long? = null,
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("command_id") val commandId: String? = null,
)
