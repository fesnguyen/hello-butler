package com.hellobutler.app.data.repository

import com.hellobutler.app.auth.ApiException
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.data.local.SavedContextDao
import com.hellobutler.app.data.local.SavedContextEntity
import com.hellobutler.app.data.remote.SavedContextUpdateDto
import com.hellobutler.app.data.remote.UserSettingsApi
import com.hellobutler.app.data.remote.UserSettingsDto
import com.hellobutler.app.data.remote.UserSettingsUpdateDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

interface UserSettingsDataSource {
    fun observeContext(): Flow<List<SavedContextEntity>>
    suspend fun synchronize()
    suspend fun saveContext(id: String, update: SavedContextUpdateDto)
    suspend fun deleteContext(item: SavedContextEntity)

    suspend fun get(): UserSettingsDto
    suspend fun save(update: UserSettingsUpdateDto): UserSettingsDto
}

class UserSettingsRepository(
    private val api: UserSettingsApi,
    private val auth: AuthRepository,
    private val dao: SavedContextDao,
) : UserSettingsDataSource {
    private val contextLock = Mutex()

    override fun observeContext() = dao.observe()

    override suspend fun synchronize() = contextLock.withLock { refreshContext() }

    private suspend fun refreshContext() {
        val items = body(authorized(api::listContext))
        dao.reconcile(items.map { SavedContextEntity(it.id, it.content, it.isPreference, it.version) })
    }

    override suspend fun saveContext(id: String, update: SavedContextUpdateDto) = contextLock.withLock {
        val response = authorized { api.saveContext(it, id, update) }
        reconcileConflict(response.code())
        val item = body(response)
        dao.upsert(listOf(SavedContextEntity(item.id, item.content, item.isPreference, item.version)))
    }

    override suspend fun deleteContext(item: SavedContextEntity) = contextLock.withLock {
        val response = authorized { api.deleteContext(it, item.id, item.version) }
        reconcileConflict(response.code())
        if (!response.isSuccessful) throw ApiException("Could not delete saved item (${response.code()})", response.code())
        dao.delete(item.id)
    }

    private suspend fun reconcileConflict(code: Int) {
        if (code == 409) {
            refreshContext()
            throw ApiException("Saved item changed. Close and reopen it to try again.", code)
        }
    }

    suspend fun clear() = contextLock.withLock { dao.clear() }

    override suspend fun get(): UserSettingsDto = body(authorized(api::get))

    override suspend fun save(update: UserSettingsUpdateDto): UserSettingsDto = body(
        authorized { authorization -> api.update(authorization, update) }
    )

    private suspend fun <T> authorized(call: suspend (String) -> Response<T>): Response<T> {
        var token = auth.accessToken()
        var response = call("Bearer $token")
        if (response.code() == 401) {
            token = auth.refreshAfterUnauthorized(token)
            response = call("Bearer $token")
        }
        return response
    }

    private fun <T> body(response: Response<T>): T {
        if (!response.isSuccessful) {
            throw ApiException("User Settings request failed (${response.code()})", response.code())
        }
        return response.body() ?: throw ApiException("User Settings returned an empty response")
    }
}
