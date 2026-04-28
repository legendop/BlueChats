package com.example.bluechats.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MessageDao {
    @Insert
    void insertMessage(MessageEntity message);

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    List<MessageEntity> getRecentMessages(int limit);

    @Query("SELECT * FROM messages WHERE senderId = :contactId OR destId = :contactId ORDER BY timestamp DESC")
    List<MessageEntity> getMessagesWithContact(String contactId);

    @Query("DELETE FROM messages WHERE timestamp < :cutoffTime")
    void deleteOldMessages(long cutoffTime);
}
