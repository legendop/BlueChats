package com.example.bluechats.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "my_chats")
public class MyChatEntity {
    @PrimaryKey
    @NonNull
    public String msgId;

    public String senderId;
    public String destId;
    public String text;
    public long timestamp;
    public boolean isSentByMe;
    public boolean isDelivered;

    public MyChatEntity(@NonNull String msgId, String senderId, String destId,
                       String text, long timestamp, boolean isSentByMe, boolean isDelivered) {
        this.msgId = msgId;
        this.senderId = senderId;
        this.destId = destId;
        this.text = text;
        this.timestamp = timestamp;
        this.isSentByMe = isSentByMe;
        this.isDelivered = isDelivered;
    }
}
