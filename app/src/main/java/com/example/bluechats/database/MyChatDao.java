package com.example.bluechats.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface MyChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(MyChatEntity chat);

    @Update
    void update(MyChatEntity chat);

    @Query("SELECT * FROM my_chats WHERE senderId = :peerId OR destId = :peerId ORDER BY timestamp ASC")
    List<MyChatEntity> getChatsWith(String peerId);

    @Query("SELECT * FROM my_chats WHERE (senderId = :peerId AND destId = :localId) OR (senderId = :localId AND destId = :peerId) ORDER BY timestamp ASC")
    List<MyChatEntity> getChatsWith(String peerId, String localId);

    @Query("SELECT * FROM my_chats ORDER BY timestamp DESC")
    List<MyChatEntity> getAllChats();

    @Query("SELECT DISTINCT CASE WHEN isSentByMe = 1 THEN destId ELSE senderId END as peerId FROM my_chats")
    List<String> getAllChatPeers();

    @Query("DELETE FROM my_chats")
    void deleteAll();
}
