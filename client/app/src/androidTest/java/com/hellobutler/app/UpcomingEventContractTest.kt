package com.hellobutler.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hellobutler.app.data.remote.DailyPlanSnapshotDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpcomingEventContractTest {
    @Test
    fun dailyPlanSnapshotMapsUpcomingProjectionSeparatelyFromDailyEvents() {
        val snapshot = Json.decodeFromString<DailyPlanSnapshotDto>(
            """{
                "plan": null,
                "events": [],
                "upcoming_events": [{
                    "id": "context-1:2026-09-20",
                    "source_context_id": "context-1",
                    "source_context_version": 2,
                    "occurrence_date": "2026-09-20",
                    "title": "Client meeting",
                    "description": "Meet the client every day this week",
                    "starts_on": "2026-09-20",
                    "ends_on": "2026-09-20",
                    "start_time": "15:00",
                    "end_time": null,
                    "recurring": true
                }]
            }""".trimIndent()
        )

        assertTrue(snapshot.events.isEmpty())
        assertEquals("Client meeting", snapshot.upcomingEvents.single().title)
        assertEquals("context-1", snapshot.upcomingEvents.single().sourceContextId)
    }
}
