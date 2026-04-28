package com.example.bluechats.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RoutingTable {
    private final Map<String, RouteEntry> routes = new ConcurrentHashMap<>();
    private final String localId;
    private long localSeqNo = 0;

    public RoutingTable(String localId) {
        this.localId = localId;
        routes.put(localId, new RouteEntry(localId, localId, 0, ++localSeqNo));
    }

    public synchronized void addRoute(String destination, String nextHop, int metric, long seqNo) {
        RouteEntry current = routes.get(destination);
        if (current == null || seqNo > current.seqNo ||
            (seqNo == current.seqNo && metric < current.metric)) {
            routes.put(destination, new RouteEntry(destination, nextHop, metric, seqNo));
        }
    }

    public String getNextHop(String destination) {
        RouteEntry entry = routes.get(destination);
        return entry != null ? entry.nextHop : null;
    }

    public List<RouteEntry> getAllRoutes() {
        return new ArrayList<>(routes.values());
    }

    public List<RouteEntry> getRoutesForAdvertisement(String neighborToPoison) {
        List<RouteEntry> result = new ArrayList<>();
        for (RouteEntry entry : routes.values()) {
            if (entry.nextHop.equals(neighborToPoison)) {
                result.add(new RouteEntry(entry.destId, entry.nextHop, Integer.MAX_VALUE / 2, entry.seqNo));
            } else {
                result.add(entry);
            }
        }
        return result;
    }

    public long incrementLocalSeq() {
        return ++localSeqNo;
    }

    public void addLocalRoute(String destId) {
        routes.put(destId, new RouteEntry(destId, destId, 0, ++localSeqNo));
    }
}
