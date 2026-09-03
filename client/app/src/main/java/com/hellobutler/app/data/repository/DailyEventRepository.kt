package com.hellobutler.app.data.repository

import com.hellobutler.app.data.local.DailyEventDao
import com.hellobutler.app.data.local.DailyEventEntity
import kotlinx.coroutines.flow.Flow

class DailyEventRepository(private val dao: DailyEventDao) {
    fun observeDate(date: String): Flow<List<DailyEventEntity>> = dao.observeDate(date)

    suspend fun update(event: DailyEventEntity) {
        dao.upsert(event) // Server sync attaches here once the sync contract exists.
    }
}
