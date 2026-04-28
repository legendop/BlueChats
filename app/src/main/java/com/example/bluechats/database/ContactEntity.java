package com.example.bluechats.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "contacts")
public class ContactEntity {
    @PrimaryKey(autoGenerate = false)
    @NonNull
    public String deviceId;

    public String deviceName;
    public String publicKeyBase64;
    public String signPubKeyBase64;
    public long lastSeen;
    public int hops;

    public ContactEntity(@NonNull String deviceId, String deviceName, String publicKeyBase64,
                        String signPubKeyBase64, long lastSeen, int hops) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.publicKeyBase64 = publicKeyBase64;
        this.signPubKeyBase64 = signPubKeyBase64;
        this.lastSeen = lastSeen;
        this.hops = hops;
    }
}
