package com.example.bluechats.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;

import java.util.List;

@Entity(tableName = "message_queue")
public class MessageQueueEntity {
    @PrimaryKey
    @NonNull
    public String msgId;

    public String senderId;
    public String destId;
    public String text;
    public long timestamp;
    public int ttl;

    @TypeConverters(StringListConverter.class)
    public List<String> seenBy;

    public MessageQueueEntity(@NonNull String msgId, String senderId, String destId,
                             String text, long timestamp, int ttl, List<String> seenBy) {
        this.msgId = msgId;
        this.senderId = senderId;
        this.destId = destId;
        this.text = text;
        this.timestamp = timestamp;
        this.ttl = ttl;
        this.seenBy = seenBy;
    }
}
