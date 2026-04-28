package com.example.bluechats.model;

/*
 * BLE MESH CHAT MANAGER
 *
 * FLOW:
 * 1. On app start: Get permissions, create Room DB for messages, RAM queue for mesh
 * 2. Each device gets unique persistent ID (from DeviceIdManager)
 * 3. Advertise with "BC" prefix + 6-char ID (total <31 bytes)
 * 4. Scan for BlueChats devices (our SERVICE_UUID)
 * 5. QR code: Share/scan device IDs
 *
 * MESSAGE PAYLOAD:
 * - msgId: senderid_destid_timestamp
 * - senderId: sender device ID
 * - destId: destination device ID
 * - text: message content
 * - seenBy: list of device IDs that have seen it
 * - ttl: hops remaining
 *
 * MESH ROUTING:
 * - Connect to BlueChats devices, send queued messages, disconnect
 * - Messages forwarded if destId != localId
 * - ACK sent when message received by destination
 * - Queue cleaned after 1 hour
 *
 * CONVERSATIONS:
 * - Show only where user is sender OR receiver
 * - Display in ChatActivity filtered by contactId
 * - Update on broadcast when new message received
 */

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.example.bluechats.database.AppDatabase;
import com.example.bluechats.database.MessageQueueDao;
import com.example.bluechats.database.MessageQueueEntity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BleMeshManager {
    private static final String TAG = "BLEMeshManager";
    private static final UUID SERVICE_UUID = UUID.fromString("2f1b9b7e-9a5a-11ee-be56-0242ac120002");
    private static final UUID CHAR_MESSAGE_UUID = UUID.fromString("2f1b9b7f-9a5a-11ee-be56-0242ac120002");
    private static final UUID CHAR_ID_UUID = UUID.fromString("2f1b9b80-9a5a-11ee-be56-0242ac120002");

    private static BleMeshManager instance;

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final String localId;
    private final Gson gson = new Gson();
    private final RoutingTable routingTable;
    private final MessageQueueDao queueDao;

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer gattServer;
    private final Map<String, BluetoothGatt> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, Long> recentlyConnected = new ConcurrentHashMap<>();
    private static final long RECONNECT_COOLDOWN = 30000;

    private MessageCallback messageCallback;
    private DeviceDiscoveryCallback discoveryCallback;
    private volatile boolean isRunning = false;
    private volatile int scanCallbackCount = 0;
    private volatile int blueChatDeviceCount = 0;
    private final Map<String, Boolean> scannedDevices = new ConcurrentHashMap<>();
    private String myActualBleMac = null;
    private final Map<String, String> macToDeviceId = new ConcurrentHashMap<>();
    private final Map<String, String> deviceIdToMac = new ConcurrentHashMap<>();

    public interface MessageCallback {
        void onMessageReceived(ChatMessage message, String fromAddress);
    }

    public interface DeviceDiscoveryCallback {
        void onBleDeviceFound(BluetoothDevice device);
    }

    public interface StatusCallback {
        void onStatusUpdate(String message);
    }

    private StatusCallback statusCallback;

    public BleMeshManager(Context context, String localId) {
        this.context = context;
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager.getAdapter();

        this.localId = localId.substring(0, Math.min(12, localId.length()));
        this.myActualBleMac = null;

        Log.i(TAG, "Using ID (12 chars): " + this.localId + " from full: " + localId);
        Log.i(TAG, "Note: ID truncated to fit in BLE name (31-byte limit)");

        this.routingTable = new RoutingTable(this.localId);
        this.queueDao = AppDatabase.getInstance(context).messageQueueDao();
    }

    public static synchronized BleMeshManager getInstance(Context context) {
        if (instance == null) {
            String deviceId = com.example.bluechats.util.DeviceIdManager.getDeviceId(context);
            instance = new BleMeshManager(context.getApplicationContext(), deviceId);
        }
        return instance;
    }

    public void setMessageCallback(MessageCallback callback) {
        this.messageCallback = callback;
    }

    public void setDeviceDiscoveryCallback(DeviceDiscoveryCallback callback) {
        this.discoveryCallback = callback;
    }

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    public void start() {
        if (isRunning) {
            Log.i(TAG, "BLE mesh already running");
            return;
        }
        isRunning = true;
        startGattServer();
        startAdvertising();
        startScanning();
        startCleanupTask();
        Log.i(TAG, "BLE mesh started with ID: " + localId);
    }

    private void startCleanupTask() {
        new Thread(() -> {
            while (isRunning) {
                try {
                    Thread.sleep(60000);
                    long now = System.currentTimeMillis();
                    long oneDayAgo = now - (24 * 60 * 60 * 1000);
                    long oneHourAgo = now - (60 * 60 * 1000);

                    queueDao.deleteExpiredMessages(oneDayAgo);

                    int deletedCount = queueDao.deleteOldUnacknowledgedMessages(oneHourAgo);
                    if (deletedCount > 0) {
                        Log.i(TAG, "🗑️ Cleaned up " + deletedCount + " unacknowledged messages older than 1 hour");
                        if (statusCallback != null) {
                            statusCallback.onStatusUpdate("🗑️ Removed " + deletedCount + " old undelivered messages");
                        }
                    }

                    recentlyConnected.entrySet().removeIf(entry ->
                        now - entry.getValue() > RECONNECT_COOLDOWN
                    );
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    private void startGattServer() {
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic messageChar = new BluetoothGattCharacteristic(
                CHAR_MESSAGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        service.addCharacteristic(messageChar);

        BluetoothGattCharacteristic idChar = new BluetoothGattCharacteristic(
                CHAR_ID_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        service.addCharacteristic(idChar);

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        gattServer.addService(service);
        Log.i(TAG, "GATT server started");
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            String addr = device.getAddress();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                routingTable.addRoute(addr, addr, 1, System.currentTimeMillis());
                Log.i(TAG, "✓✓✓ GATT SERVER: Device connected to us: " + addr);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "✗✗✗ GATT SERVER: Device disconnected from us: " + addr);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (CHAR_ID_UUID.equals(characteristic.getUuid())) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                        localId.getBytes(StandardCharsets.UTF_8));
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            Log.i(TAG, ">>> GATT SERVER: onCharacteristicWriteRequest");
            Log.i(TAG, "    From device: " + device.getAddress());
            Log.i(TAG, "    Characteristic UUID: " + characteristic.getUuid());
            Log.i(TAG, "    Expected CHAR_MESSAGE_UUID: " + CHAR_MESSAGE_UUID);
            Log.i(TAG, "    Data length: " + (value != null ? value.length : 0) + " bytes");

            if (CHAR_MESSAGE_UUID.equals(characteristic.getUuid())) {
                Log.i(TAG, "    ✓ Characteristic UUID matches!");
                String json = new String(value, StandardCharsets.UTF_8);
                Log.i(TAG, "    JSON: " + json);
                try {
                    ChatMessage message = gson.fromJson(json, ChatMessage.class);
                    Log.i(TAG, "    ✓ Message parsed successfully");
                    Log.i(TAG, "    Message type: " + message.type);
                    Log.i(TAG, "    From: " + message.senderId + " To: " + message.destId);

                    if (statusCallback != null) {
                        statusCallback.onStatusUpdate("📨 RECEIVED msg from " + device.getAddress());
                    }

                    handleIncomingMessage(message, device.getAddress());
                    if (responseNeeded) {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "✗✗✗ Failed to parse message", e);
                    if (statusCallback != null) {
                        statusCallback.onStatusUpdate("✗ Failed to parse incoming message!");
                    }
                    if (responseNeeded) {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                    }
                }
            } else {
                Log.w(TAG, "    ✗ Characteristic UUID does NOT match!");
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                }
            }
        }
    };

    private void startAdvertising() {
        try {
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.e(TAG, "✗✗✗ BLE advertising not supported on this device");
                if (statusCallback != null) {
                    statusCallback.onStatusUpdate("⚠️ BLE advertising not supported on this phone!");
                }
                return;
            }

            try {
                String btName = "BC" + localId;
                bluetoothAdapter.setName(btName);
                Log.i(TAG, "Set Bluetooth name to: " + btName + " (BC prefix + " + localId.length() + " char ID)");
            } catch (SecurityException e) {
                Log.w(TAG, "Could not set Bluetooth name: " + e.getMessage());
            }

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();

            Log.i(TAG, "Starting advertising:");
            Log.i(TAG, "  Prefix: BC (BlueChats identifier)");
            Log.i(TAG, "  Service UUID: " + SERVICE_UUID + " (16 bytes)");
            Log.i(TAG, "  Device name: " + bluetoothAdapter.getName() + " (BC + 12 chars = 14 bytes)");
            Log.i(TAG, "  Total payload: ~30 bytes (under 31 byte limit)");

            advertiser.startAdvertising(settings, data, null, advertiseCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "✗✗✗ SECURITY EXCEPTION when advertising: " + e.getMessage());
            if (statusCallback != null) {
                statusCallback.onStatusUpdate("⚠️ Cannot advertise - missing BLUETOOTH_ADVERTISE permission!");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗✗✗ EXCEPTION when advertising: " + e.getMessage());
            e.printStackTrace();
            if (statusCallback != null) {
                statusCallback.onStatusUpdate("⚠️ Advertising failed: " + e.getMessage());
            }
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "✓✓✓ BLE ADVERTISING STARTED SUCCESSFULLY ✓✓✓");
            Log.i(TAG, "    Service UUID: " + SERVICE_UUID);
            Log.i(TAG, "    Device ID in advertisement: " + localId);
            Log.i(TAG, "    Bluetooth name: " + bluetoothAdapter.getName());
            Log.i(TAG, "    Mode: " + settingsInEffect.getMode());
            Log.i(TAG, "    Tx Power: " + settingsInEffect.getTxPowerLevel());

            if (statusCallback != null) {
                String name = bluetoothAdapter.getName();
                statusCallback.onStatusUpdate("✓ Advertising: " + (name != null ? name : "Unknown") + " | ID: " + localId.substring(0, Math.min(12, localId.length())));
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            String reason = "";
            String details = "";
            switch (errorCode) {
                case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                    reason = "ADVERTISE_FAILED_DATA_TOO_LARGE";
                    details = "Advertisement payload exceeds 31 bytes limit. Split into ad + scan response.";
                    Log.e(TAG, "✗✗✗ ADVERTISEMENT DATA TOO LARGE!");
                    Log.e(TAG, "    Device ID length: " + localId.length() + " bytes");
                    Log.e(TAG, "    SERVICE_UUID: 16 bytes");
                    Log.e(TAG, "    Total would be: ~" + (localId.length() + 20) + " bytes");
                    Log.e(TAG, "    Android BLE limit: 31 bytes for advertisement");
                    Log.e(TAG, "    Solution: Using scan response for device ID");
                    if (statusCallback != null) {
                        statusCallback.onStatusUpdate("✗ DATA TOO LARGE!");
                        statusCallback.onStatusUpdate("  Device ID: " + localId.length() + " bytes");
                        statusCallback.onStatusUpdate("  SERVICE_UUID: 16 bytes");
                        statusCallback.onStatusUpdate("  Total: ~" + (localId.length() + 20) + " bytes");
                        statusCallback.onStatusUpdate("  Android limit: 31 bytes");
                        statusCallback.onStatusUpdate("  Fix: Using scan response");
                    }
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    reason = "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS";
                    details = "Too many BLE advertisers active on this device.";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                    reason = "ADVERTISE_FAILED_ALREADY_STARTED";
                    details = "Advertising already started.";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                    reason = "ADVERTISE_FAILED_INTERNAL_ERROR";
                    details = "Internal Bluetooth error.";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    reason = "ADVERTISE_FAILED_FEATURE_UNSUPPORTED";
                    details = "BLE advertising not supported on this device.";
                    break;
                default:
                    reason = "UNKNOWN_ERROR_" + errorCode;
                    details = "Unknown advertising error.";
            }
            Log.e(TAG, "✗✗✗ BLE ADVERTISING FAILED: " + reason);
            Log.e(TAG, "    Details: " + details);
            if (statusCallback != null) {
                statusCallback.onStatusUpdate("✗ ADVERTISING FAILED: " + reason);
            }
        }
    };

    private void startScanning() {
        try {
            if (bluetoothAdapter == null) {
                Log.e(TAG, "✗✗✗ BluetoothAdapter is NULL");
                if (statusCallback != null) {
                    statusCallback.onStatusUpdate("✗ BluetoothAdapter is NULL!");
                }
                return;
            }

            if (!bluetoothAdapter.isEnabled()) {
                Log.e(TAG, "✗✗✗ Bluetooth is NOT ENABLED");
                if (statusCallback != null) {
                    statusCallback.onStatusUpdate("✗ Bluetooth is OFF!");
                }
                return;
            }

            scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner == null) {
                Log.e(TAG, "✗✗✗ BLE scanner is NULL - BLE not supported or Bluetooth OFF");
                if (statusCallback != null) {
                    statusCallback.onStatusUpdate("✗ BLE Scanner is NULL - Hardware issue?");
                }
                return;
            }

            Log.i(TAG, "Bluetooth adapter state: " + bluetoothAdapter.getState());
            Log.i(TAG, "Bluetooth enabled: " + bluetoothAdapter.isEnabled());
            Log.i(TAG, "BLE Scanner object: " + scanner);

            if (statusCallback != null) {
                statusCallback.onStatusUpdate("Starting BLE scan...");
            }

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build();

            Log.i(TAG, "Calling scanner.startScan()...");
            scanner.startScan(null, settings, scanCallback);
            Log.i(TAG, "✓✓✓ scanner.startScan() CALLED SUCCESSFULLY ✓✓✓");
            Log.i(TAG, "    Scan mode: LOW_LATENCY");
            Log.i(TAG, "    Callback: " + scanCallback);

            if (statusCallback != null) {
                statusCallback.onStatusUpdate("✓ Scan started - waiting for devices...");
            }

            Log.i(TAG, "⚠️ If you see 0 callbacks after 10s:");
            Log.i(TAG, "  1. Check Android Settings > Location > ON");
            Log.i(TAG, "  2. Check Android Settings > Apps > BlueChats > Battery > Unrestricted");
            Log.i(TAG, "  3. Disable any battery saver / power saving mode");
            Log.i(TAG, "  4. Try restarting Bluetooth (turn OFF then ON)");
            Log.i(TAG, "  5. Try restarting the phone");

            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    runOnUiThread(() -> {
                        if (scanCallbackCount == 0) {
                            Log.w(TAG, "⚠️ 2 seconds passed - NO SCAN CALLBACKS YET!");
                            if (statusCallback != null) {
                                statusCallback.onStatusUpdate("⚠️ 2s: 0 callbacks - BLE scan may be blocked!");
                            }
                        }
                    });

                    for (int i = 3; i <= 10; i++) {
                        Thread.sleep(1000);
                        final int count = i;
                        final int callbacks = scanCallbackCount;
                        runOnUiThread(() -> {
                            Log.i(TAG, "[" + count + "s] Scanning... (callbacks: " + callbacks + ")");
                            if (statusCallback != null) {
                                statusCallback.onStatusUpdate("Scanning " + count + "s - Found " + callbacks + " devices");
                            }
                        });
                    }
                    runOnUiThread(() -> {
                        if (statusCallback != null) {
                            if (scanCallbackCount == 0) {
                                statusCallback.onStatusUpdate("⚠️ 0 devices scanned! Bluetooth/Location/Permissions issue!");
                            } else if (blueChatDeviceCount == 0) {
                                statusCallback.onStatusUpdate("⚠️ Scanned " + scanCallbackCount + " devices - NONE have BlueChats UUID! Is other phone advertising?");
                            } else if (connectedDevices.size() == 0) {
                                statusCallback.onStatusUpdate("⚠️ Found " + blueChatDeviceCount + " BlueChats devices but connection failed!");
                            } else {
                                statusCallback.onStatusUpdate("✓ Connected to " + connectedDevices.size() + " BlueChats device(s)!");
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    Log.e(TAG, "Timer interrupted", e);
                }
            }).start();
        } catch (SecurityException e) {
            Log.e(TAG, "✗✗✗ SECURITY EXCEPTION - Missing permissions: " + e.getMessage());
            e.printStackTrace();
            if (statusCallback != null) {
                statusCallback.onStatusUpdate("⚠️ PERMISSION DENIED! Go to Settings > Apps > BlueChats > Permissions > Allow ALL");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗✗✗ EXCEPTION starting scan: " + e.getMessage());
            e.printStackTrace();
            if (statusCallback != null) {
                statusCallback.onStatusUpdate("⚠️ Scan failed: " + e.getMessage());
            }
        }

        new Thread(() -> {
            try {
                Thread.sleep(2000);
                runOnUiThread(() -> {
                    if (scanCallbackCount == 0 && statusCallback != null) {
                        statusCallback.onStatusUpdate("⚠️ NOT SCANNING! Check: Settings > Location > ON, then Settings > Apps > BlueChats > Permissions > Allow ALL");
                    }
                });
            } catch (InterruptedException e) {
                Log.e(TAG, "Timer interrupted", e);
            }
        }).start();
    }

    private void runOnUiThread(Runnable runnable) {
        android.os.Handler handler = new android.os.Handler(context.getMainLooper());
        handler.post(runnable);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String addr = device.getAddress();

            if (scannedDevices.containsKey(addr)) {
                return;
            }

            scannedDevices.put(addr, true);
            scanCallbackCount++;

            String name = device.getName();
            int rssi = result.getRssi();

            Log.i(TAG, ">>> UNIQUE DEVICE #" + scanCallbackCount + " - " + addr + " | Name: " + name + " | RSSI: " + rssi);

            boolean hasOurService = false;
            String remoteId = null;

            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                Log.d(TAG, "    Device " + addr + " advertises " + result.getScanRecord().getServiceUuids().size() + " services");
                for (ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                    Log.d(TAG, "      Service UUID: " + uuid.getUuid());
                    if (SERVICE_UUID.equals(uuid.getUuid())) {
                        hasOurService = true;
                        Log.i(TAG, "      ✓✓✓ FOUND BLUECHATS DEVICE!");

                        if (name != null && name.startsWith("BC") && name.length() > 2) {
                            remoteId = name.substring(2);
                            Log.i(TAG, "      ✓ Extracted device ID from name: " + remoteId);

                            macToDeviceId.put(addr, remoteId);
                            deviceIdToMac.put(remoteId, addr);
                            Log.i(TAG, "      ✓ Stored mapping: MAC " + addr + " <-> ID " + remoteId);
                        } else {
                            Log.w(TAG, "      BlueChats device but name issue - Name: " + name);
                            remoteId = addr;
                            macToDeviceId.put(addr, remoteId);
                            deviceIdToMac.put(remoteId, addr);
                            Log.i(TAG, "      Using MAC as ID: " + remoteId);
                        }
                        break;
                    }
                }
            } else {
                Log.d(TAG, "    Device " + addr + " has no service UUIDs in scan record");
            }

            if (!hasOurService) {
                Log.d(TAG, "    >>> Regular BLE device (not BlueChats) - ignoring");
                return;
            }

            if (discoveryCallback != null) {
                discoveryCallback.onBleDeviceFound(device);
            }

            blueChatDeviceCount++;
            Log.i(TAG, "    >>> ✓✓✓ BLUECHATS DEVICE #" + blueChatDeviceCount + "!");
            Log.i(TAG, "        Name: " + name);
            Log.i(TAG, "        MAC: " + addr);
            Log.i(TAG, "        Device ID: " + remoteId);

            if (statusCallback != null) {
                statusCallback.onStatusUpdate("✓ Found BlueChats device! " + name + " | " + addr);
            }

            Long lastConnected = recentlyConnected.get(addr);
            if (lastConnected != null && System.currentTimeMillis() - lastConnected < RECONNECT_COOLDOWN) {
                Log.d(TAG, "        Skipping auto-connect - device in cooldown period");
                return;
            }

            if (!connectedDevices.containsKey(addr)) {
                Log.i(TAG, "        Auto-connecting to BlueChats device: " + addr);
                connectToDevice(device);
            } else {
                Log.d(TAG, "        Already connected to " + addr);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            String error = "UNKNOWN";
            switch (errorCode) {
                case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                    error = "SCAN_FAILED_ALREADY_STARTED";
                    break;
                case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    error = "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED";
                    break;
                case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                    error = "SCAN_FAILED_INTERNAL_ERROR";
                    break;
                case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                    error = "SCAN_FAILED_FEATURE_UNSUPPORTED";
                    break;
            }
            Log.e(TAG, "✗✗✗ BLE SCAN FAILED: " + error + " (code: " + errorCode + ")");
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        device.connectGatt(context, false, new BluetoothGattCallback() {
            private volatile boolean syncComplete = false;

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                String addr = device.getAddress();
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices.put(addr, gatt);
                    routingTable.addRoute(addr, addr, 1, System.currentTimeMillis());
                    recentlyConnected.put(addr, System.currentTimeMillis());
                    gatt.discoverServices();
                    Log.i(TAG, "✓✓✓ CONNECTED TO DEVICE: " + addr + " | Total connected: " + connectedDevices.size());

                    if (discoveryCallback != null) {
                        Log.i(TAG, "    Calling discovery callback for " + addr);
                        discoveryCallback.onBleDeviceFound(device);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevices.remove(addr);
                    gatt.close();
                    Log.i(TAG, "✗✗✗ DISCONNECTED FROM DEVICE: " + addr + " | Total connected: " + connectedDevices.size());

                    if (discoveryCallback != null) {
                        discoveryCallback.onBleDeviceFound(device);
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered for " + gatt.getDevice().getAddress());
                    syncMessageQueueAndDisconnect(gatt, this);
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Message written successfully");
                }
            }

            public void markSyncComplete() {
                syncComplete = true;
            }

            public boolean isSyncComplete() {
                return syncComplete;
            }
        });
    }

    public void sendMessage(ChatMessage message) {
        Log.i(TAG, ">>> SEND MESSAGE CALLED <<<");
        Log.i(TAG, "    From: " + message.senderId);
        Log.i(TAG, "    To: " + message.destId);
        Log.i(TAG, "    Payload: " + message.encryptedPayload);

        if (message.type == ChatMessage.Type.DATA) {
            new Thread(() -> {
                MessageQueueEntity entity = new MessageQueueEntity(
                    message.msgId, message.senderId, message.destId,
                    message.encryptedPayload, message.timestamp, message.ttl, message.seenBy
                );
                queueDao.insert(entity);
                Log.i(TAG, "    ✓ Message added to queue: " + message.msgId);
            }).start();
        }

        if (message.destId == null || message.destId.isEmpty()) {
            Log.e(TAG, "✗✗✗ Message destId is null or empty");
            return;
        }

        String targetMac = deviceIdToMac.get(message.destId);
        if (targetMac == null) {
            Log.e(TAG, "✗✗✗ Cannot find MAC address for device ID: " + message.destId);
            Log.e(TAG, "    Known mappings: " + deviceIdToMac);
            if (statusCallback != null) {
                String shortId = message.destId.length() >= 8 ? message.destId.substring(0, 8) : message.destId;
                statusCallback.onStatusUpdate("✗ Unknown device: " + shortId);
            }
            return;
        }

        Log.i(TAG, "    Translated device ID to MAC: " + message.destId + " → " + targetMac);
        Log.i(TAG, "    Connected devices count: " + connectedDevices.size());
        Log.i(TAG, "    Connected devices (MACs): " + connectedDevices.keySet());

        String nextHop = routingTable.getNextHop(targetMac);
        if (nextHop == null) {
            nextHop = targetMac;
        }
        Log.i(TAG, "    Using MAC for routing: " + nextHop);

        BluetoothGatt gatt = connectedDevices.get(nextHop);
        Log.i(TAG, "    GATT object for next hop: " + (gatt != null ? "Found" : "NULL"));

        if (gatt != null) {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            Log.i(TAG, "    GATT Service: " + (service != null ? "Found" : "NULL"));

            if (service != null) {
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHAR_MESSAGE_UUID);
                Log.i(TAG, "    Message Characteristic: " + (characteristic != null ? "Found" : "NULL"));

                if (characteristic != null) {
                    String json = gson.toJson(message);
                    Log.i(TAG, "    Message JSON length: " + json.length() + " bytes");
                    characteristic.setValue(json.getBytes(StandardCharsets.UTF_8));
                    boolean success = gatt.writeCharacteristic(characteristic);
                    if (success) {
                        Log.i(TAG, "✓✓✓ MESSAGE WRITE QUEUED SUCCESSFULLY");
                    } else {
                        Log.e(TAG, "✗✗✗ MESSAGE WRITE FAILED");
                    }
                } else {
                    Log.e(TAG, "✗✗✗ Message characteristic not found on service");
                }
            } else {
                Log.e(TAG, "✗✗✗ Service UUID not found on device");
                Log.e(TAG, "    Available services: " + gatt.getServices());
            }
        } else {
            Log.e(TAG, "✗✗✗ Not connected to next hop: " + nextHop);
            Log.e(TAG, "    This device was in routing table but not in connectedDevices map");
        }
    }

    private void syncMessageQueueAndDisconnect(BluetoothGatt gatt, BluetoothGattCallback callback) {
        new Thread(() -> {
            try {
                String remoteAddr = gatt.getDevice().getAddress();
                List<MessageQueueEntity> messages = queueDao.getMessagesNotSeenBy(remoteAddr);

                Log.i(TAG, "Syncing " + messages.size() + " messages to " + remoteAddr);

                for (MessageQueueEntity entity : messages) {
                    ChatMessage msg = new ChatMessage();
                    msg.type = ChatMessage.Type.QUEUE_SYNC;
                    msg.msgId = entity.msgId;
                    msg.senderId = entity.senderId;
                    msg.destId = entity.destId;
                    msg.encryptedPayload = entity.text;
                    msg.timestamp = entity.timestamp;
                    msg.ttl = entity.ttl;
                    msg.seenBy = new ArrayList<>(entity.seenBy);

                    sendMessageDirect(gatt, msg);
                    Thread.sleep(200);
                }

                Thread.sleep(500);

                Log.i(TAG, "Sync complete, disconnecting from " + remoteAddr);
                gatt.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Failed to sync message queue", e);
                gatt.disconnect();
            }
        }).start();
    }

    private void sendMessageDirect(BluetoothGatt gatt, ChatMessage message) {
        Log.i(TAG, ">>> sendMessageDirect to " + gatt.getDevice().getAddress());

        if (statusCallback != null) {
            statusCallback.onStatusUpdate("📤 SENDING msg to " + gatt.getDevice().getAddress());
        }

        BluetoothGattService service = gatt.getService(SERVICE_UUID);
        if (service != null) {
            Log.i(TAG, "    ✓ Service found: " + SERVICE_UUID);
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHAR_MESSAGE_UUID);
            if (characteristic != null) {
                Log.i(TAG, "    ✓ Characteristic found: " + CHAR_MESSAGE_UUID);
                String json = gson.toJson(message);
                Log.i(TAG, "    Message JSON: " + json);
                Log.i(TAG, "    Sending " + json.length() + " bytes");

                int writeType = characteristic.getWriteType();
                Log.i(TAG, "    Characteristic write type: " + writeType);
                Log.i(TAG, "    Setting write type to WRITE_TYPE_DEFAULT");
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

                characteristic.setValue(json.getBytes(StandardCharsets.UTF_8));
                boolean success = gatt.writeCharacteristic(characteristic);
                Log.i(TAG, "    Write queued: " + (success ? "✓ SUCCESS" : "✗ FAILED"));

                if (statusCallback != null) {
                    if (success) {
                        statusCallback.onStatusUpdate("✓ Message write queued");
                    } else {
                        statusCallback.onStatusUpdate("✗ Message write FAILED to queue");
                    }
                }
            } else {
                Log.e(TAG, "    ✗ Characteristic NOT found: " + CHAR_MESSAGE_UUID);
                Log.e(TAG, "    Available characteristics:");
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    Log.e(TAG, "      - " + c.getUuid());
                }
                if (statusCallback != null) {
                    statusCallback.onStatusUpdate("✗ Message characteristic not found on remote!");
                }
            }
        } else {
            Log.e(TAG, "    ✗ Service NOT found: " + SERVICE_UUID);
            Log.e(TAG, "    Available services:");
            for (BluetoothGattService s : gatt.getServices()) {
                Log.e(TAG, "      - " + s.getUuid());
            }
            if (statusCallback != null) {
                statusCallback.onStatusUpdate("✗ Service UUID not found on remote device!");
            }
        }
    }

    private void handleIncomingMessage(ChatMessage message, String fromAddr) {
        if (message == null) {
            Log.e(TAG, "✗✗✗ Received null message!");
            return;
        }

        if (message.senderId == null || message.destId == null) {
            Log.e(TAG, "✗✗✗ Message has null senderId or destId!");
            Log.e(TAG, "    senderId: " + message.senderId + ", destId: " + message.destId);
            return;
        }

        Log.i(TAG, "Received message: type=" + message.type + ", from=" + message.senderId + ", to=" + message.destId + ", localId=" + localId);

        message.addSeenBy(localId);
        if (fromAddr != null) {
            message.addSeenBy(fromAddr);
        }
        if (message.senderId != null) {
            message.addSeenBy(message.senderId);
        }

        new Thread(() -> {
            MessageQueueEntity existing = queueDao.getMessageById(message.msgId);
            if (existing != null) {
                Log.i(TAG, "  Message already exists in queue, updating seenBy list");
                existing.seenBy.add(localId);
                existing.seenBy.add(fromAddr);
                existing.seenBy.add(message.senderId);
                queueDao.update(existing);
                Log.i(TAG, "  Updated seenBy: " + existing.seenBy);
            } else {
                Log.i(TAG, "  New message, inserting into queue");
                MessageQueueEntity entity = new MessageQueueEntity(
                    message.msgId, message.senderId, message.destId,
                    message.encryptedPayload, message.timestamp, message.ttl, message.seenBy
                );
                queueDao.insert(entity);
                Log.i(TAG, "  Inserted with seenBy: " + message.seenBy);
            }
        }).start();

        if (message.type == ChatMessage.Type.ACK) {
            Log.i(TAG, ">>> RECEIVED ACK MESSAGE <<<");
            Log.i(TAG, "  ACK for message ID: " + message.msgId);
            Log.i(TAG, "  From: " + message.senderId);

            new Thread(() -> {
                MessageQueueEntity queuedMsg = queueDao.getMessageById(message.msgId);
                if (queuedMsg != null) {
                    queueDao.delete(queuedMsg);
                    Log.i(TAG, "  ✓ Removed message from queue: " + message.msgId);
                    if (statusCallback != null) {
                        statusCallback.onStatusUpdate("✓ Message delivered & acknowledged!");
                    }
                } else {
                    Log.i(TAG, "  Message not in queue (already removed or never queued)");
                }
            }).start();

        } else if (message.type == ChatMessage.Type.DATA || message.type == ChatMessage.Type.QUEUE_SYNC) {
            Log.i(TAG, "Checking if message is for me:");
            Log.i(TAG, "  Message destId: '" + message.destId + "'");
            Log.i(TAG, "  My localId: '" + localId + "'");
            Log.i(TAG, "  Message from: '" + fromAddr + "'");

            boolean isForMe = message.destId.equals(localId);

            Log.i(TAG, "  destId.equals(localId): " + message.destId.equals(localId));

            if (isForMe) {
                Log.i(TAG, "✓✓✓ MESSAGE IS FOR ME!");

                new Thread(() -> {
                    MessageQueueEntity queuedMsg = queueDao.getMessageById(message.msgId);
                    if (queuedMsg != null) {
                        queueDao.delete(queuedMsg);
                        Log.i(TAG, "  ✓ Removed delivered message from queue: " + message.msgId);
                    }
                }).start();

                Log.i(TAG, "  Sending ACK back to sender...");
                ChatMessage ack = ChatMessage.createAck(message.msgId, localId, message.senderId);
                sendMessage(ack);
                Log.i(TAG, "  ✓ ACK sent for message: " + message.msgId);

                if (statusCallback != null) {
                    statusCallback.onStatusUpdate("✓ Message received & ACK sent!");
                }

                Log.i(TAG, "  Attempting to call messageCallback...");
                Log.i(TAG, "  messageCallback is: " + (messageCallback != null ? "NOT NULL" : "NULL"));

                if (messageCallback != null) {
                    Log.i(TAG, "  ✓ Calling messageCallback.onMessageReceived()...");
                    messageCallback.onMessageReceived(message, fromAddr);
                    Log.i(TAG, "  ✓ messageCallback.onMessageReceived() completed");
                } else {
                    Log.e(TAG, "✗✗✗ messageCallback is NULL! Cannot deliver to UI!");
                    if (statusCallback != null) {
                        statusCallback.onStatusUpdate("✗ ERROR: messageCallback is NULL!");
                    }
                }
            } else {
                Log.i(TAG, "Message is not for me, forwarding...");
                message.ttl--;
                if (message.ttl > 0) {
                    sendMessage(message);
                } else {
                    Log.i(TAG, "TTL expired, dropping message");
                }
            }
        }
    }

    public List<String> getConnectedPeers() {
        return new ArrayList<>(connectedDevices.keySet());
    }

    public boolean isDeviceConnected(String address) {
        return connectedDevices.containsKey(address);
    }

    public String getDeviceIdFromMac(String macAddress) {
        return macToDeviceId.get(macAddress);
    }

    public String getMacFromDeviceId(String deviceId) {
        return deviceIdToMac.get(deviceId);
    }

    public Map<String, String> getMacToDeviceIdMap() {
        return new ConcurrentHashMap<>(macToDeviceId);
    }

    public void connectForMessage(BluetoothDevice device, ChatMessage message) {
        Log.i(TAG, ">>> CONNECT FOR MESSAGE: " + device.getAddress());
        device.connectGatt(context, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                String addr = device.getAddress();
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices.put(addr, gatt);
                    routingTable.addRoute(addr, addr, 1, System.currentTimeMillis());
                    recentlyConnected.put(addr, System.currentTimeMillis());
                    gatt.discoverServices();
                    Log.i(TAG, "✓✓✓ Connected for messaging to " + addr + " - Discovering services...");

                    if (discoveryCallback != null) {
                        discoveryCallback.onBleDeviceFound(device);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevices.remove(addr);
                    gatt.close();
                    Log.i(TAG, "Disconnected from " + addr + " after message send");
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "✓ Services discovered for " + gatt.getDevice().getAddress() + " - Sending message now...");
                    sendMessageDirect(gatt, message);
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            Log.i(TAG, "Disconnecting after message send to " + gatt.getDevice().getAddress());
                            gatt.disconnect();
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Sleep interrupted", e);
                        }
                    }).start();
                } else {
                    Log.e(TAG, "✗✗✗ Service discovery failed for " + gatt.getDevice().getAddress() + " - status: " + status);
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "✓✓✓ MESSAGE DELIVERED SUCCESSFULLY to " + gatt.getDevice().getAddress());
                } else {
                    Log.e(TAG, "✗✗✗ MESSAGE DELIVERY FAILED to " + gatt.getDevice().getAddress() + " - status: " + status);
                }
            }
        });
    }

    public void shutdown() {
        isRunning = false;
        if (scanner != null) {
            scanner.stopScan(scanCallback);
        }
        if (advertiser != null) {
            advertiser.stopAdvertising(advertiseCallback);
        }
        if (gattServer != null) {
            gattServer.close();
        }
        for (BluetoothGatt gatt : connectedDevices.values()) {
            gatt.disconnect();
            gatt.close();
        }
        connectedDevices.clear();
        recentlyConnected.clear();
        Log.i(TAG, "BLE mesh manager shutdown");
    }
}
