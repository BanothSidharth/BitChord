package com.music.bitchord.data.backup

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.parseToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.channels.consumeEach
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File
import java.util.UUID

/**
 * Local-first client for the backup contract. The server is assumed to return
 * JSON using snake_case and to treat event_id as an idempotency key.
 */
object BackupService {
    val devices = MutableSharedFlow<List<Device>>(replay = 1, extraBufferCapacity = 1)
    val playbackUpdates = MutableSharedFlow<PlaybackState>(replay = 1, extraBufferCapacity = 8)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var queueFile: File
    private var cursor: String? = null
    private var client: HttpClient? = null

    fun init(context: Context) {
        queueFile = File(context.filesDir, "backup_queue.json")
        if (!queueFile.exists()) queueFile.writeText("[]")
        client = HttpClient {
            install(ContentNegotiation) { json(json) }
            install(WebSockets)
        }
        scope.launch { syncLoop() }
        scope.launch { websocketLoop() }
    }

    fun enqueue(type: String, payload: kotlinx.serialization.json.JsonObject) {
        if (!BackupSettings.configured) return
        scope.launch {
            mutex.withLock {
                val current = readQueue().toMutableList()
                current += BackupChange(UUID.randomUUID().toString(), type, BackupSettings.deviceId.value, payload)
                writeQueue(current)
            }
            if (BackupSettings.autoSync.value) syncOnce()
        }
    }

    fun publishPlayback(state: PlaybackState) {
        if (!BackupSettings.configured) return
        scope.launch {
            request<Unit>("/playback") {
                method = HttpMethod.Put
                setBody(PlaybackUpdate(BackupSettings.deviceId.value, state))
            }
        }
    }

    fun sendCommand(deviceId: String, command: PlaybackCommand) {
        if (!BackupSettings.configured) return
        scope.launch {
            request<Unit>("/playback/commands") {
                method = HttpMethod.Post
                setBody(command.copy(commandId = command.commandId ?: UUID.randomUUID().toString()))
            }
        }
    }

    private suspend fun syncLoop() {
        while (scope.isActive) {
            if (BackupSettings.configured && BackupSettings.autoSync.value) syncOnce()
            delay(SYNC_INTERVAL_MS)
        }
    }

    private suspend fun syncOnce() {
        runCatching {
            request<Unit>("/health") { method = HttpMethod.Get }
            request<Unit>("/devices/register") {
                method = HttpMethod.Post
                setBody(DeviceRegistration(BackupSettings.deviceId.value, BackupSettings.deviceName.value, "android"))
            }

            val pending = mutex.withLock { readQueue() }
            if (pending.isNotEmpty()) {
                val response = request<SyncPushResponse>("/sync/push") {
                    method = HttpMethod.Post
                    setBody(SyncPushRequest(pending))
                }
                response.cursor?.let { cursor = it }
                mutex.withLock { writeQueue(readQueue().drop(pending.size)) }
            }
            val pulled = request<SyncPullResponse>("/sync/pull") {
                method = HttpMethod.Get
                parameter("cursor", cursor)
            }
            cursor = pulled.cursor ?: cursor
            pulled.changes.forEach { applyChange(it) }
            val currentDevices = request<List<Device>>("/devices") { method = HttpMethod.Get }
            devices.tryEmit(currentDevices)
        }.onFailure { Log.w(TAG, "Backup sync unavailable", it) }
    }

    private suspend fun websocketLoop() {
        while (scope.isActive) {
            if (!BackupSettings.configured) {
                delay(SYNC_INTERVAL_MS)
                continue
            }
            runCatching {
                val base = BackupSettings.serverUrl.value.trimEnd('/')
                    .replaceFirst("https://", "wss://")
                    .replaceFirst("http://", "ws://")
                client!!.webSocket("$base/ws?token=${BackupSettings.token.value}") {
                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            runCatching {
                                                val message = json.parseToJsonElement(frame.readText()).jsonObject
                                                message["state"]?.let {
                                                    playbackUpdates.emit(
                                                        json.decodeFromJsonElement(PlaybackState.serializer(), it),
                                                    )
                                                }
                            }
                        }
                    }
                }
            }.onFailure { Log.d(TAG, "Backup websocket disconnected", it) }
            delay(SYNC_INTERVAL_MS)
        }
    }

    private suspend fun applyChange(change: BackupChange) {
        if (change.type == "playback_state") {
            runCatching { playbackUpdates.emit(json.decodeFromJsonElement(PlaybackState.serializer(), change.payload)) }
        }
    }

    private suspend inline fun <reified T> request(
        path: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): T {
        val base = BackupSettings.serverUrl.value.trimEnd('/')
        require(base.isNotBlank())
        val response = client!!.request("$base$path") {
            header(HttpHeaders.Authorization, "Bearer " + BackupSettings.token.value)
            contentType(ContentType.Application.Json)
            block()
        }
        return response.body()
    }

    private suspend fun readQueue(): List<BackupChange> =
        runCatching { json.decodeFromString(queueFile.readText()) }.getOrDefault(emptyList())

    private fun writeQueue(changes: List<BackupChange>) {
        val temp = File(queueFile.parentFile, "${queueFile.name}.tmp")
        temp.writeText(json.encodeToString(changes))
        check(temp.renameTo(queueFile)) { "Unable to replace backup queue" }
    }

    private const val TAG = "BackupService"
    private const val SYNC_INTERVAL_MS = 30_000L
}
