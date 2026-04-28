package com.example.bluechats.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface MessageQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(MessageQueueEntity message);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<MessageQueueEntity> messages);

    @Update
    void update(MessageQueueEntity message);

    @Delete
    void delete(MessageQueueEntity message);

    @Query("SELECT * FROM message_queue WHERE ttl > 0 ORDER BY timestamp DESC")
    List<MessageQueueEntity> getAllActiveMessages();

    @Query("SELECT * FROM message_queue WHERE msgId = :msgId LIMIT 1")
    MessageQueueEntity getMessageById(String msgId);

    @Query("SELECT * FROM message_queue WHERE ttl > 0 AND NOT EXISTS (SELECT 1 FROM json_each(seenBy) WHERE value = :macAddress) LIMIT 50")
    List<MessageQueueEntity> getMessagesNotSeenBy(String macAddress);

    @Query("DELETE FROM message_queue WHERE ttl <= 0 OR timestamp < :expiryTime")
    void deleteExpiredMessages(long expiryTime);

    @Query("DELETE FROM message_queue WHERE timestamp < :oneHourAgo")
    int deleteOldUnacknowledgedMessages(long oneHourAgo);

    @Query("SELECT COUNT(*) FROM message_queue")
    int getQueueCount();

    @Query("DELETE FROM message_queue")
    void deleteAll();
}
