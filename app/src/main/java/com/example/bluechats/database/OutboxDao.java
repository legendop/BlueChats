package com.example.bluechats.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface OutboxDao {
    @Insert
    void insertOutboxMessage(OutboxEntity message);

    @Query("SELECT * FROM outbox WHERE nextRetryTime <= :currentTime ORDER BY timestamp ASC")
    List<OutboxEntity> getPendingMessages(long currentTime);

    @Query("DELETE FROM outbox WHERE msgId = :msgId")
    void deleteMessage(String msgId);

    @Query("UPDATE outbox SET retryCount = :retryCount, nextRetryTime = :nextRetryTime WHERE msgId = :msgId")
    void updateRetry(String msgId, int retryCount, long nextRetryTime);

    @Query("DELETE FROM outbox WHERE timestamp < :cutoffTime")
    void deleteExpiredMessages(long cutoffTime);
}
