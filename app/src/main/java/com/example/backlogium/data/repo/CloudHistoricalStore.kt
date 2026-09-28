package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.CloudHistoricalDao
import com.example.backlogium.data.local.entity.CloudHistoricalBoundary
import com.example.backlogium.data.local.entity.CloudHistoricalInterval
import com.example.backlogium.data.local.entity.CloudHistoricalOperation
import javax.inject.Inject
import javax.inject.Singleton

/** Durable seam for independent historical pages; ordinary read progress is not part of this store. */
interface CloudHistoricalStore {
    suspend fun insertOperation(operation: CloudHistoricalOperation): Boolean
    suspend fun operation(operationId: String): CloudHistoricalOperation?
    suspend fun operationForIdentity(
        account: String,
        readerGeneration: Long,
        endpointIdentity: String,
        state: String,
    ): CloudHistoricalOperation?
    suspend fun boundary(operationId: String, kind: String): CloudHistoricalBoundary?
    suspend fun commitPage(
        previous: CloudHistoricalOperation,
        intervals: List<CloudHistoricalInterval>,
        boundaries: List<CloudHistoricalBoundary>,
        progress: CloudHistoricalOperation,
    ): CloudHistoricalOperation?
}

@Singleton
class RoomCloudHistoricalStore @Inject constructor(
    private val dao: CloudHistoricalDao,
) : CloudHistoricalStore {
    override suspend fun insertOperation(operation: CloudHistoricalOperation): Boolean =
        dao.insertOperation(operation) != -1L

    override suspend fun operation(operationId: String): CloudHistoricalOperation? =
        dao.operation(operationId)

    override suspend fun operationForIdentity(
        account: String,
        readerGeneration: Long,
        endpointIdentity: String,
        state: String,
    ): CloudHistoricalOperation? = dao.operationForIdentity(
        account, readerGeneration, endpointIdentity, state,
    )

    override suspend fun boundary(operationId: String, kind: String): CloudHistoricalBoundary? =
        dao.boundary(operationId, kind)

    override suspend fun commitPage(
        previous: CloudHistoricalOperation,
        intervals: List<CloudHistoricalInterval>,
        boundaries: List<CloudHistoricalBoundary>,
        progress: CloudHistoricalOperation,
    ): CloudHistoricalOperation? = dao.commitPage(previous, intervals, boundaries, progress)
}

/** Keeps legacy protocol-only repository tests independent of a Room database. */
internal object EmptyCloudHistoricalStore : CloudHistoricalStore {
    override suspend fun insertOperation(operation: CloudHistoricalOperation) = false
    override suspend fun operation(operationId: String): CloudHistoricalOperation? = null
    override suspend fun operationForIdentity(
        account: String,
        readerGeneration: Long,
        endpointIdentity: String,
        state: String,
    ): CloudHistoricalOperation? = null
    override suspend fun boundary(operationId: String, kind: String): CloudHistoricalBoundary? = null
    override suspend fun commitPage(
        previous: CloudHistoricalOperation,
        intervals: List<CloudHistoricalInterval>,
        boundaries: List<CloudHistoricalBoundary>,
        progress: CloudHistoricalOperation,
    ): CloudHistoricalOperation? = null
}
