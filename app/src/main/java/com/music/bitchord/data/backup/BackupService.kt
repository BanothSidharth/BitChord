package com.music.bitchord.data.backup

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/** Local-first client for the documented v1 backup contract. */
object BackupService {
    val devices = MutableSharedFlow<List<Device>>(replay = 1, extraBufferCapacity = 1)
    val playbackUpdates = MutableSharedFlow<PlaybackState>(replay = 1, extraBufferCapacity = 8)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var queueFile: File
    private var cursor: String? = null
    private lateinit var client: HttpClient

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
                val changes = readQueue().toMutableList()
                changes += BackupChange(UUID.randomUUID().toString(), type, payload)
                writeQueue(changes)
            }
            if (BackupSettings.autoSync.value) syncOnce()
        }
    }

    fun publishPlayback(state: PlaybackState) {
        if (!BackupSettings.configured) return
        scope.launch {
            request<Unit>("/v1/playback/state", HttpMethod.Put) { setBody(state) }
        }
    }

    fun sendCommand(deviceId: String, command: PlaybackCommand) {
        if (!BackupSettings.configured) return
        scope.launch {
            request<Unit>("/v1/devices/$deviceId/commands", HttpMethod.Post) {
                setBody(command.copy(commandId = command.commandId ?: UUID.randomUUID().toString()))
            }
        }
    }

    private suspend fun syncLoop() {
        while (scope.isActive) {
            if (BackupSettings.configured && BackupSettings.autoSync.value) syncOnce()
            delay(30_000)
        }
    }

    private suspend fun syncOnce() {
        runCatching {
            request<Unit>("/health", HttpMethod.Get)
            request<Unit>("/v1/devices/register", HttpMethod.Post) {
                setBody(DeviceRegistration(BackupSettings.deviceName.value))
            }
            val pending = mutex.withLock { readQueue() }
            if (pending.isNotEmpty()) {
                val response = request<SyncPushResponse>("/v1/sync/push", HttpMethod.Post) {
                    setBody(SyncPushRequest(pending))
                }
                cursor = response.cursor ?: cursor
                mutex.withLock { writeQueue(readQueue().drop(pending.size)) }
            }
            val pulled = request<SyncPullResponse>("/v1/sync/pull", HttpMethod.Get) {
                parameter("cursor", cursor)
            }
            cursor = pulled.cursor ?: cursor
            for (change in pulled.changes) {
                applyChange(change)
            }
            devices.tryEmit(request("/v1/devices", HttpMethod.Get))
        }.onFailure { Log.d(TAG, "Backup sync unavailable", it) }
    }

    private suspend fun websocketLoop() {
        while (scope.isActive) {
            if (!BackupSettings.configured) {
                delay(30_000)
                continue
            }
            runCatching {
                val base = BackupSettings.serverUrl.value.trimEnd('/')
                    .replaceFirst("https://", "wss://")
                    .replaceFirst("http://", "ws://")
                client.webSocket(request = {
                    url("$base/v1/ws")
                    header(HttpHeaders.Authorization, "Bearer " + BackupSettings.token.value)
                }) {
                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            val objectValue = json.parseToJsonElement(frame.readText()).jsonObject
                            objectValue["state"]?.let {
                                playbackUpdates.emit(json.decodeFromJsonElement(PlaybackState.serializer(), it))
                            }
                        }
                    }
                }
            }.onFailure { Log.d(TAG, "Backup websocket disconnected", it) }
            delay(30_000)
        }
    }

    private suspend fun applyChange(change: BackupChange) {
        if (change.type == "playback_state") {
            runCatching {
                playbackUpdates.emit(json.decodeFromJsonElement(PlaybackState.serializer(), change.payload))
            }
        }
    }

    private suspend inline fun <reified T> request(
        path: String,
        method: HttpMethod,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val base = BackupSettings.serverUrl.value.trimEnd('/')
        require(base.isNotBlank())
        return client.request("$base$path") {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer " + BackupSettings.token.value)
            contentType(ContentType.Application.Json)
            block()
        }.body()
    }

    private suspend fun readQueue(): List<BackupChange> =
        runCatching {
            json.decodeFromString<List<BackupChange>>(queueFile.readText())
        }.getOrDefault(emptyList())

    private fun writeQueue(changes: List<BackupChange>) {
        val temp = File(queueFile.parentFile, "${queueFile.name}.tmp")
        temp.writeText(json.encodeToString(ListSerializer(BackupChange.serializer()), changes))
        check(temp.renameTo(queueFile)) { "Unable to replace backup queue" }
    }

    private const val TAG = "BackupService"
}
