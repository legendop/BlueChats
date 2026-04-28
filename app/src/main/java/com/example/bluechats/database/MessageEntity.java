package com.example.bluechats.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "messages")
public class MessageEntity {
    @PrimaryKey(autoGenerate = true)
    @NonNull
    public long id;

    public String msgId;
    public String senderId;
    public String destId;
    public String encryptedPayload;
    public long timestamp;
    public int ttl;
    public String messageType;
    public boolean delivered;

    public MessageEntity(String msgId, String senderId, String destId,
                        String encryptedPayload, long timestamp, int ttl,
                        String messageType, boolean delivered) {
        this.msgId = msgId;
        this.senderId = senderId;
        this.destId = destId;
        this.encryptedPayload = encryptedPayload;
        this.timestamp = timestamp;
        this.ttl = ttl;
        this.messageType = messageType;
        this.delivered = delivered;
    }
}
