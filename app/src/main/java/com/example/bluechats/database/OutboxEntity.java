package com.example.bluechats.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "outbox")
public class OutboxEntity {
    @PrimaryKey(autoGenerate = true)
    @NonNull
    public long id;

    public String msgId;
    public String senderId;
    public String destId;
    public String encryptedPayload;
    public int ttl;
    public long timestamp;
    public int retryCount;
    public long nextRetryTime;

    public OutboxEntity(String msgId, String senderId, String destId,
                       String encryptedPayload, int ttl, long timestamp,
                       int retryCount, long nextRetryTime) {
        this.msgId = msgId;
        this.senderId = senderId;
        this.destId = destId;
        this.encryptedPayload = encryptedPayload;
        this.ttl = ttl;
        this.timestamp = timestamp;
        this.retryCount = retryCount;
        this.nextRetryTime = nextRetryTime;
    }
}
