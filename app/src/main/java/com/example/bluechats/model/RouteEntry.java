package com.example.bluechats.model;

public class RouteEntry {
    public String destId;
    public String nextHop;
    public int metric;
    public long seqNo;
    public long lastUpdated;

    public RouteEntry(String destId, String nextHop, int metric, long seqNo) {
        this.destId = destId;
        this.nextHop = nextHop;
        this.metric = metric;
        this.seqNo = seqNo;
        this.lastUpdated = System.currentTimeMillis();
    }
}
