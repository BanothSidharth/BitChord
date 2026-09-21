package com.music.bitchord

import com.music.bitchord.data.backup.BackupChange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupModelsTest {
    @Test
    fun changeUsesWireFieldNamesAndRoundTrips() {
        val original = BackupChange(
            eventId = "event-1",
            type = "listening_stats",
            payload = buildJsonObject { put("media_id", "abc") },
            createdAt = 42L,
        )
        val wire = Json.encodeToString(original)
        assertEquals(
            """{"event_id":"event-1","type":"listening_stats","payload":{"media_id":"abc"},"created_at":42}""",
            wire,
        )
        assertEquals(original, Json.decodeFromString<BackupChange>(wire))
    }
}
