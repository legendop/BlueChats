package com.example.bluechats.model;

public class Node {
    public String deviceId;
    public String deviceName;
    public String publicKeyBase64;
    public String signPubKeyBase64;
    public int hops;
    public long lastSeen;

    public Node(String id, String name, int hops) {
        this.deviceId = id;
        this.deviceName = name;
        this.hops = hops;
        this.lastSeen = System.currentTimeMillis();
    }

    public Node(String id, String name, String pubKey, String signKey, int hops) {
        this(id, name, hops);
        this.publicKeyBase64 = pubKey;
        this.signPubKeyBase64 = signKey;
    }
}
