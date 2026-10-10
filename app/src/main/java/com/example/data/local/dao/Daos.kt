package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.DataUsageSnapshotEntity
import com.example.data.local.entity.NetworkEntity
import kotlinx.coroutines.flow.Flow

data class TotalUsageTuple(
    val totalRx: Long?,
    val totalTx: Long?
)

data class NetworkUsageTuple(
    val ssid: String,
    val rxBytes: Long?,
    val txBytes: Long?,
    val snapshotCount: Int
)

@Dao
interface NetworkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(network: NetworkEntity): Long

    @Update
    suspend fun update(network: NetworkEntity)

    @Query("SELECT * FROM wifi_networks WHERE ssid = :ssid LIMIT 1")
    suspend fun findBySsid(ssid: String): NetworkEntity?

    @Query("SELECT * FROM wifi_networks ORDER BY lastSeenTimestamp DESC")
    fun getAllNetworks(): Flow<List<NetworkEntity>>

    @Query("SELECT * FROM wifi_networks WHERE internalId = :id LIMIT 1")
    fun getNetworkById(id: Long): Flow<NetworkEntity?>

    @Query("SELECT * FROM wifi_networks WHERE ssid = :ssid LIMIT 1")
    fun getNetworkBySsidFlow(ssid: String): Flow<NetworkEntity?>
}

@Dao
interface DataUsageSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: DataUsageSnapshotEntity): Long

    @Query("SELECT * FROM data_usage_snapshots ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSnapshot(): DataUsageSnapshotEntity?

    @Query("SELECT * FROM data_usage_snapshots ORDER BY timestamp DESC LIMIT 1")
    fun getLatestSnapshotFlow(): Flow<DataUsageSnapshotEntity?>

    @Query("SELECT * FROM data_usage_snapshots ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSnapshots(limit: Int = 100): Flow<List<DataUsageSnapshotEntity>>

    @Query("SELECT * FROM data_usage_snapshots WHERE ssid = :ssid ORDER BY timestamp DESC")
    fun getSnapshotsForNetwork(ssid: String): Flow<List<DataUsageSnapshotEntity>>

    @Query("SELECT * FROM data_usage_snapshots WHERE ssid = :ssid AND timestamp >= :startTime ORDER BY timestamp DESC")
    fun getSnapshotsForNetworkSince(ssid: String, startTime: Long): Flow<List<DataUsageSnapshotEntity>>

    @Query("SELECT * FROM data_usage_snapshots WHERE ssid = :ssid AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    fun getSnapshotsForNetworkBetween(ssid: String, startTime: Long, endTime: Long): Flow<List<DataUsageSnapshotEntity>>

    @Query("SELECT SUM(deltaRxBytes) as totalRx, SUM(deltaTxBytes) as totalTx FROM data_usage_snapshots WHERE timestamp >= :startTime")
    fun getTotalUsageSince(startTime: Long): Flow<TotalUsageTuple?>

    @Query("SELECT SUM(deltaRxBytes) as totalRx, SUM(deltaTxBytes) as totalTx FROM data_usage_snapshots")
    fun getAllTimeUsage(): Flow<TotalUsageTuple?>

    @Query("""
        SELECT ssid, SUM(deltaRxBytes) as rxBytes, SUM(deltaTxBytes) as txBytes, COUNT(id) as snapshotCount 
        FROM data_usage_snapshots 
        WHERE timestamp >= :startTime 
        GROUP BY ssid
    """)
    fun getUsageByNetworkSince(startTime: Long): Flow<List<NetworkUsageTuple>>

    @Query("""
        SELECT ssid, SUM(deltaRxBytes) as rxBytes, SUM(deltaTxBytes) as txBytes, COUNT(id) as snapshotCount 
        FROM data_usage_snapshots 
        GROUP BY ssid
    """)
    fun getAllTimeUsageByNetwork(): Flow<List<NetworkUsageTuple>>
}
