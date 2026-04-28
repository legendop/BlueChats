package com.example.bluechats.model;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BluetoothMeshManager {
    private static final String TAG = "BTMeshManager";
    private static final UUID APP_UUID = UUID.fromString("2f1b9b7e-9a5a-11ee-be56-0242ac120002");

    private final BluetoothAdapter adapter;
    private final RoutingTable routingTable;
    private final Map<String, BluetoothDevice> discoveredDevices = new ConcurrentHashMap<>();
    private final Map<String, ConnectedThread> connections = new ConcurrentHashMap<>();
    private final String localId;
    private final Gson gson = new Gson();

    private AcceptThread acceptThread;
    private DiscoveryThread discoveryThread;
    private MessageCallback messageCallback;
    private DeviceDiscoveryCallback discoveryCallback;

    public interface DeviceDiscoveryCallback {
        void onBlueChatDeviceFound(BluetoothDevice device);
    }

    public interface MessageCallback {
        void onMessageReceived(ChatMessage message, String fromAddress);
    }

    public BluetoothMeshManager(Context ctx, String localId) {
        this.adapter = BluetoothAdapter.getDefaultAdapter();
        this.localId = localId;
        this.routingTable = new RoutingTable(localId);
    }

    public void setMessageCallback(MessageCallback callback) {
        this.messageCallback = callback;
    }

    public void setDeviceDiscoveryCallback(DeviceDiscoveryCallback callback) {
        this.discoveryCallback = callback;
    }

    public void startServer() {
        if (acceptThread != null) {
            acceptThread.cancel();
        }
        try {
            acceptThread = new AcceptThread();
            acceptThread.start();
            Log.i(TAG, "RFCOMM server started");
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server", e);
        }

        if (discoveryThread == null || !discoveryThread.isAlive()) {
            discoveryThread = new DiscoveryThread();
            discoveryThread.start();
            Log.i(TAG, "Auto-discovery started");
        }
    }

    public void startDiscovery() {
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
        adapter.startDiscovery();
        Log.i(TAG, "Discovery started");
    }

    public void onDeviceFound(BluetoothDevice device) {
        String addr = device.getAddress();
        discoveredDevices.put(addr, device);
        routingTable.addRoute(addr, addr, 1, System.currentTimeMillis());
        Log.i(TAG, "Device found: " + device.getName() + " [" + addr + "]");
    }

    public void connectTo(BluetoothDevice device) {
        new Thread(() -> {
            try {
                if (adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }

                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(APP_UUID);
                socket.connect();

                String deviceAddr = device.getAddress();
                ConnectedThread thread = new ConnectedThread(socket);
                connections.put(deviceAddr, thread);
                routingTable.addRoute(deviceAddr, deviceAddr, 1, System.currentTimeMillis());
                thread.start();

                Log.i(TAG, "Connected to " + deviceAddr + " and added route");
            } catch (IOException e) {
                Log.e(TAG, "Connection failed to " + device.getAddress(), e);
            }
        }).start();
    }

    public void sendMessage(ChatMessage message) {
        Log.i(TAG, "Sending message: msgId=" + message.msgId + ", from=" + message.senderId + ", to=" + message.destId + ", payload=" + message.encryptedPayload);
        String nextHop = routingTable.getNextHop(message.destId);
        if (nextHop == null) {
            Log.w(TAG, "No route to " + message.destId + ". Available connections: " + connections.keySet());
            return;
        }

        ConnectedThread thread = connections.get(nextHop);
        if (thread != null) {
            String json = gson.toJson(message);
            Log.i(TAG, "Sending JSON: " + json);
            thread.write(json);
        } else {
            Log.w(TAG, "Not connected to next hop: " + nextHop);
        }
    }

    public void broadcastRouteUpdate() {
        ChatMessage update = new ChatMessage();
        update.type = ChatMessage.Type.ROUTE_UPDATE;
        update.senderId = localId;
        update.encryptedPayload = gson.toJson(routingTable.getAllRoutes());

        String json = gson.toJson(update);
        for (ConnectedThread thread : connections.values()) {
            thread.write(json);
        }
    }

    public List<String> getConnectedPeers() {
        return new ArrayList<>(connections.keySet());
    }

    public RoutingTable getRoutingTable() {
        return routingTable;
    }

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;

        AcceptThread() throws IOException {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord("BlueChats", APP_UUID);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    BluetoothSocket socket = serverSocket.accept();
                    if (socket != null) {
                        String remoteAddr = socket.getRemoteDevice().getAddress();
                        ConnectedThread thread = new ConnectedThread(socket);
                        connections.put(remoteAddr, thread);
                        routingTable.addRoute(remoteAddr, remoteAddr, 1, System.currentTimeMillis());
                        thread.start();
                        Log.i(TAG, "Accepted connection from " + remoteAddr + " and added route");
                    }
                } catch (IOException e) {
                    break;
                }
            }
        }

        void cancel() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final BufferedReader input;
        private final PrintWriter output;
        private final String remoteAddr;

        ConnectedThread(BluetoothSocket socket) throws IOException {
            this.socket = socket;
            this.input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.output = new PrintWriter(socket.getOutputStream(), true);
            this.remoteAddr = socket.getRemoteDevice().getAddress();
        }

        @Override
        public void run() {
            String line;
            try {
                while ((line = input.readLine()) != null) {
                    try {
                        ChatMessage message = gson.fromJson(line, ChatMessage.class);
                        handleIncomingMessage(message, remoteAddr);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse message", e);
                    }
                }
            } catch (IOException e) {
                Log.i(TAG, "Connection lost: " + remoteAddr);
            } finally {
                connections.remove(remoteAddr);
                cancel();
            }
        }

        void write(String message) {
            output.println(message);
        }

        void cancel() {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void handleIncomingMessage(ChatMessage message, String fromAddr) {
        Log.i(TAG, "Received message: type=" + message.type + ", msgId=" + message.msgId + ", from=" + message.senderId + ", to=" + message.destId + ", localId=" + localId);
        if (message.type == ChatMessage.Type.ROUTE_UPDATE) {
            Log.i(TAG, "Received route update from " + fromAddr);
        } else if (message.type == ChatMessage.Type.DATA) {
            Log.i(TAG, "Comparing destId='" + message.destId + "' with localId='" + localId + "' - Match: " + message.destId.equals(localId));
            if (message.destId.equals(localId)) {
                Log.i(TAG, "Message is for me! Payload: " + message.encryptedPayload);
                if (messageCallback != null) {
                    messageCallback.onMessageReceived(message, fromAddr);
                } else {
                    Log.w(TAG, "messageCallback is null!");
                }
            } else {
                Log.i(TAG, "Message is not for me, checking if should forward...");
                message.ttl--;
                if (message.ttl > 0) {
                    sendMessage(message);
                    Log.i(TAG, "Forwarded message " + message.msgId);
                }
            }
        } else if (message.type == ChatMessage.Type.ACK) {
            Log.i(TAG, "Received ACK for message " + message.msgId);
        }
    }

    public void shutdown() {
        if (acceptThread != null) {
            acceptThread.cancel();
        }
        if (discoveryThread != null) {
            discoveryThread.interrupt();
        }
        for (ConnectedThread thread : connections.values()) {
            thread.cancel();
        }
        connections.clear();
    }

    private class DiscoveryThread extends Thread {
        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    Log.i(TAG, "Scanning for BlueChats devices...");

                    if (adapter.isDiscovering()) {
                        adapter.cancelDiscovery();
                    }

                    for (BluetoothDevice device : adapter.getBondedDevices()) {
                        if (!connections.containsKey(device.getAddress())) {
                            tryConnectToBlueChatsDevice(device);
                        }
                    }

                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        private void tryConnectToBlueChatsDevice(BluetoothDevice device) {
            new Thread(() -> {
                try {
                    if (adapter.isDiscovering()) {
                        adapter.cancelDiscovery();
                    }

                    BluetoothSocket socket = device.createRfcommSocketToServiceRecord(APP_UUID);
                    socket.connect();

                    String deviceAddr = device.getAddress();
                    ConnectedThread thread = new ConnectedThread(socket);
                    connections.put(deviceAddr, thread);
                    routingTable.addRoute(deviceAddr, deviceAddr, 1, System.currentTimeMillis());
                    thread.start();

                    Log.i(TAG, "Auto-connected to BlueChats device: " + deviceAddr);

                    if (discoveryCallback != null) {
                        discoveryCallback.onBlueChatDeviceFound(device);
                    }
                } catch (IOException e) {
                    Log.d(TAG, "Device " + device.getAddress() + " is not a BlueChats server");
                }
            }).start();
        }
    }
}
