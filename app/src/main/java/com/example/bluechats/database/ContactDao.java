package com.example.bluechats.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertContact(ContactEntity contact);

    @Query("SELECT * FROM contacts")
    List<ContactEntity> getAllContacts();

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId")
    ContactEntity getContactById(String deviceId);

    @Query("DELETE FROM contacts WHERE deviceId = :deviceId")
    void deleteContact(String deviceId);

    @Query("UPDATE contacts SET lastSeen = :lastSeen WHERE deviceId = :deviceId")
    void updateLastSeen(String deviceId, long lastSeen);
}
