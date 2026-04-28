package com.example.bluechats.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ChatMessage implements Serializable {
    public enum Type { ROUTE_UPDATE, DATA, ACK, QUEUE_SYNC }

    public Type type;
    public String msgId;
    public String senderId;
    public String destId;
    public int ttl;
    public long timestamp;
    public String encryptedPayload;
    public String signature;
    public List<String> seenBy;

    public ChatMessage() {
        this.timestamp = System.currentTimeMillis();
        this.seenBy = new ArrayList<>();
    }

    public ChatMessage(String senderId, String destId, String encryptedPayload, int ttl) {
        this();
        this.type = Type.DATA;
        this.senderId = senderId;
        this.destId = destId;
        this.encryptedPayload = encryptedPayload;
        this.ttl = ttl;
        this.msgId = generateMessageId(senderId, destId, timestamp);
    }

    public static String generateMessageId(String senderMac, String destMac, long timestamp) {
        return senderMac + "_" + destMac + "_" + timestamp;
    }

    public void addSeenBy(String macAddress) {
        if (!seenBy.contains(macAddress)) {
            seenBy.add(macAddress);
        }
    }

    public static ChatMessage createDataMessage(String sender, String dest, String ciphertext, int ttl) {
        return new ChatMessage(sender, dest, ciphertext, ttl);
    }

    public static ChatMessage createData(String msgId, String sender, String dest, String plaintext, int ttl) {
        ChatMessage msg = new ChatMessage();
        msg.type = Type.DATA;
        msg.msgId = msgId;
        msg.senderId = sender;
        msg.destId = dest;
        msg.encryptedPayload = plaintext;
        msg.ttl = ttl;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ChatMessage createAck(String originalMsgId, String sender, String dest) {
        ChatMessage ack = new ChatMessage();
        ack.type = Type.ACK;
        ack.msgId = originalMsgId;
        ack.senderId = sender;
        ack.destId = dest;
        ack.ttl = 10;
        ack.timestamp = System.currentTimeMillis();
        return ack;
    }

    public static ChatMessage parseMessageId(String msgId) {
        String[] parts = msgId.split("_");
        if (parts.length == 3) {
            ChatMessage msg = new ChatMessage();
            msg.msgId = msgId;
            msg.senderId = parts[0];
            msg.destId = parts[1];
            try {
                msg.timestamp = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                msg.timestamp = System.currentTimeMillis();
            }
            return msg;
        }
        return null;
    }
}
