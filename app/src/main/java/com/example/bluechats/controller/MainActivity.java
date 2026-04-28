package com.example.bluechats.controller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.snackbar.Snackbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.bluechats.R;
import com.example.bluechats.database.AppDatabase;
import com.example.bluechats.database.MessageQueueDao;
import com.example.bluechats.database.MessageQueueEntity;
import com.example.bluechats.database.MyChatDao;
import com.example.bluechats.database.MyChatEntity;
import com.example.bluechats.model.BleMeshManager;
import com.example.bluechats.model.ChatMessage;
import com.example.bluechats.model.CryptoManager;
import com.example.bluechats.model.Node;
import com.example.bluechats.util.QRUtils;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 100;

    private BleMeshManager meshManager;
    private CryptoManager cryptoManager;
    private String localId;
    private MessageQueueDao queueDao;
    private MyChatDao myChatDao;

    private TextView txtMyId;
    private TextView txtStatus;
    private ListView deviceList;
    private ListView chatList;
    private ListView debugList;
    private EditText messageInput;
    private Button sendButton;
    private ImageView qrView;

    private ArrayAdapter<String> deviceAdapter;
    private ArrayAdapter<String> chatAdapter;
    private ArrayAdapter<String> debugAdapter;
    private List<String> devices = new ArrayList<>();
    private List<String> messages = new ArrayList<>();
    private List<String> debugLogs = new ArrayList<>();

    private Map<String, Node> contactDirectory = new HashMap<>();
    private Map<String, String> macToIdMapping = new HashMap<>();
    private Map<String, BluetoothDevice> discoveredDevices = new HashMap<>();
    private String selectedDeviceId = null;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();

        gestureDetector = new GestureDetector(this, new SwipeGestureListener());

        if (!checkAllPermissions()) {
            Snackbar.make(findViewById(android.R.id.content),
                "Requesting Bluetooth permissions...",
                Snackbar.LENGTH_LONG).show();
            requestPermissions();
            return;
        }

        initializeApp();
    }

    private void initializeViews() {
        txtMyId = findViewById(R.id.txtMyId);
        txtStatus = findViewById(R.id.txtStatus);
        deviceList = findViewById(R.id.deviceList);
        chatList = findViewById(R.id.chatList);
        debugList = findViewById(R.id.debugList);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        qrView = findViewById(R.id.qrView);

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, devices);
        deviceList.setAdapter(deviceAdapter);

        chatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, messages);
        chatList.setAdapter(chatAdapter);

        debugAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, debugLogs);
        debugList.setAdapter(debugAdapter);

        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            try {
                String item = devices.get(position);
                if (item == null || item.isEmpty()) {
                    Log.w(TAG, "Selected item is null or empty");
                    return;
                }

                String[] parts = item.split(" ");
                if (parts.length == 0) {
                    Log.w(TAG, "Could not parse device item: " + item);
                    return;
                }

                String displayId = parts[0];
                if (displayId == null || displayId.isEmpty()) {
                    Log.w(TAG, "Display ID is null or empty");
                    return;
                }

                BluetoothDevice device = discoveredDevices.get(displayId);
                if (device != null) {
                    selectedDeviceId = displayId;
                    addDebugLog("✓ Selected device ID: " + selectedDeviceId);
                } else {
                    selectedDeviceId = displayId;
                    addDebugLog("Selected device: " + displayId);
                }

                Log.i(TAG, "Selected device ID for messaging: " + selectedDeviceId);
                String shortId = selectedDeviceId.length() >= 8 ? selectedDeviceId.substring(0, 8) : selectedDeviceId;
                Toast.makeText(this, "Selected: " + shortId, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error selecting device", e);
                Toast.makeText(this, "Error selecting device", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void autoStartBlueMesh() {
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                meshManager.start();
                runOnUiThread(() -> {
                    updateStatus("BlueChats mesh active");
                    Snackbar.make(findViewById(android.R.id.content),
                        "BlueChats started - Advertising & Scanning for devices...",
                        Snackbar.LENGTH_LONG).show();
                });

                startQueueMonitor();

                Thread.sleep(8000);
                runOnUiThread(() -> {
                    int deviceCount = devices.size();
                    if (deviceCount == 0) {
                        Snackbar.make(findViewById(android.R.id.content),
                            "No BlueChats devices found yet. Check logs for details.",
                            Snackbar.LENGTH_LONG).show();
                        Log.w(TAG, "=== NO DEVICES FOUND AFTER 8 SECONDS ===");
                        Log.w(TAG, "Check if:");
                        Log.w(TAG, "  - Other device has app open");
                        Log.w(TAG, "  - Both devices granted all permissions");
                        Log.w(TAG, "  - Bluetooth is ON on both devices");
                        Log.w(TAG, "  - Devices are close to each other");
                    } else {
                        Snackbar.make(findViewById(android.R.id.content),
                            "Found " + deviceCount + " BlueChats device(s)!",
                            Snackbar.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to start BLE mesh", e);
                runOnUiThread(() -> {
                    Snackbar.make(findViewById(android.R.id.content),
                        "Failed to start BLE: " + e.getMessage(),
                        Snackbar.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void setupButtonListeners() {
        Button btnStartServer = findViewById(R.id.btnStartServer);
        btnStartServer.setVisibility(View.GONE);

        Button btnDiscover = findViewById(R.id.btnDiscover);
        btnDiscover.setVisibility(View.GONE);

        Button btnShareQr = findViewById(R.id.btnShareQr);
        btnShareQr.setOnClickListener(v -> shareQrCode());

        Button btnScanQr = findViewById(R.id.btnScanQr);
        btnScanQr.setOnClickListener(v -> scanQrCode());

        sendButton.setOnClickListener(v -> sendMessage());
    }

    private void setupBluetoothReceiver() {
    }

    private void startQueueMonitor() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30000);

                    int queueCount = queueDao.getQueueCount();
                    if (queueCount > 0) {
                        runOnUiThread(() -> {
                            addDebugLog("📦 Queue status: " + queueCount + " message(s) waiting for delivery");
                        });
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in queue monitor", e);
                }
            }
        }).start();
    }

    private void shareQrCode() {
        try {
            String pubKey = cryptoManager.getPublicKeyBase64();
            String signKey = cryptoManager.getSigningPublicKeyBase64();
            String deviceName = getSharedPreferences("bluechats", MODE_PRIVATE).getString("deviceName", "User");

            Bitmap qrBitmap = QRUtils.generateContactQr(localId, pubKey, signKey);

            qrView.setImageBitmap(qrBitmap);
            qrView.setVisibility(View.VISIBLE);

            addDebugLog("📱 QR Code displayed");
            addDebugLog("  Device ID: " + localId);
            addDebugLog("  Name: " + deviceName);
            updateStatus("QR Code displayed - Share with others!");

            Toast.makeText(this, "Show this QR code to add contact", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate QR code", e);
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show();
        }
    }

    private void scanQrCode() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Scan contact QR code");
        integrator.setCameraId(0);
        integrator.setBeepEnabled(true);
        integrator.setBarcodeImageEnabled(false);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            String qrContent = result.getContents();
            QRUtils.ContactInfo contact = QRUtils.parseQrContent(qrContent);
            if (contact != null) {
                Node node = new Node(contact.id, "Contact", contact.pk, contact.sp, 0);
                contactDirectory.put(contact.id, node);

                qrView.setVisibility(View.GONE);

                addDebugLog("✓ Contact added via QR!");
                addDebugLog("  Device ID: " + contact.id);
                addDebugLog("  Creating conversation entry...");

                new Thread(() -> {
                    try {
                        MyChatEntity existingChat = myChatDao.getAllChats().stream()
                                .filter(c -> c.senderId.equals(contact.id) || c.destId.equals(contact.id))
                                .findFirst()
                                .orElse(null);

                        if (existingChat == null) {
                            MyChatEntity placeholderChat = new MyChatEntity(
                                "qr_" + contact.id + "_" + System.currentTimeMillis(),
                                localId,
                                contact.id,
                                "",
                                System.currentTimeMillis(),
                                true,
                                false
                            );
                            myChatDao.insert(placeholderChat);
                            Log.i(TAG, "✓ Created empty conversation for contact: " + contact.id);

                            runOnUiThread(() -> {
                                addDebugLog("✓ Conversation created - ready to chat!");
                            });
                        } else {
                            Log.i(TAG, "Conversation already exists with: " + contact.id);
                            runOnUiThread(() -> {
                                addDebugLog("Contact already in conversations");
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to create conversation", e);
                    }
                }).start();

                updateStatus("Contact added: " + contact.id.substring(0, Math.min(8, contact.id.length())));
                Toast.makeText(this, "✓ Contact added! Swipe left to see conversations.", Toast.LENGTH_LONG).show();

                Log.i(TAG, "Added contact - ID: " + contact.id);
            } else {
                Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void sendMessage() {
        String messageText = messageInput.getText().toString().trim();
        if (messageText.isEmpty()) {
            Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedDeviceId == null) {
            Toast.makeText(this, "Please select a recipient", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            addDebugLog("Creating message FROM: " + localId + " TO: " + selectedDeviceId);
            Log.i(TAG, ">>> SENDING MESSAGE <<<");
            Log.i(TAG, "    From (localId): " + localId);
            Log.i(TAG, "    To (selectedDeviceId): " + selectedDeviceId);
            Log.i(TAG, "    Text: " + messageText);

            ChatMessage message = ChatMessage.createDataMessage(localId, selectedDeviceId, messageText, 10);
            message.addSeenBy(localId);

            Log.i(TAG, "    Message created:");
            Log.i(TAG, "      msgId: " + message.msgId);
            Log.i(TAG, "      type: " + message.type);
            Log.i(TAG, "      senderId: " + message.senderId);
            Log.i(TAG, "      destId: " + message.destId);
            Log.i(TAG, "      encryptedPayload: " + message.encryptedPayload);
            Log.i(TAG, "      ttl: " + message.ttl);
            Log.i(TAG, "      timestamp: " + message.timestamp);
            Log.i(TAG, "      seenBy: " + message.seenBy);

            addDebugLog("📤 SENDING: " + messageText);
            addDebugLog("  MsgId: " + message.msgId);
            addDebugLog("  Queued - waiting for ACK...");

            new Thread(() -> {
                MessageQueueEntity queueEntity = new MessageQueueEntity(
                    message.msgId, message.senderId, message.destId, messageText,
                    message.timestamp, message.ttl, message.seenBy
                );
                queueDao.insert(queueEntity);
                Log.i(TAG, "    ✓ Message saved to queue database");

                MyChatEntity chatEntity = new MyChatEntity(
                    message.msgId, message.senderId, message.destId, messageText,
                    message.timestamp, true, false
                );
                myChatDao.insert(chatEntity);
                Log.i(TAG, "    ✓ Message saved to chat database");
            }).start();

            addDebugLog("Sending msg TO: " + selectedDeviceId + " FROM: " + localId);

            boolean isConnected = meshManager.isDeviceConnected(selectedDeviceId);
            Log.i(TAG, "Sending to " + selectedDeviceId + " - isConnected: " + isConnected);

            if (isConnected) {
                Log.i(TAG, "Device already connected, sending message directly...");
                addDebugLog("✓ Device connected, sending now...");
                meshManager.sendMessage(message);
            } else {
                BluetoothDevice targetDevice = discoveredDevices.get(selectedDeviceId);
                if (targetDevice != null) {
                    Log.i(TAG, "Device not connected yet, connecting to send message...");
                    addDebugLog("Connecting to " + selectedDeviceId.substring(0, 8) + " to send...");
                    updateStatus("Connecting to " + selectedDeviceId.substring(0, Math.min(8, selectedDeviceId.length())) + "...");
                    meshManager.connectForMessage(targetDevice, message);
                } else {
                    Log.e(TAG, "Device not found in discovered devices!");
                    addDebugLog("✗ Device " + selectedDeviceId.substring(0, 8) + " not in discovered list!");
                }
            }

            String displayMsg = formatMessage("Me", messageText);
            messages.add(displayMsg);
            chatAdapter.notifyDataSetChanged();
            messageInput.setText("");
            updateStatus("Message queued for " + selectedDeviceId.substring(0, Math.min(8, selectedDeviceId.length())));
        } catch (Exception e) {
            Log.e(TAG, "Failed to send message", e);
            Toast.makeText(this, "Failed to send message: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onBlueChatDeviceFound(BluetoothDevice device) {
        runOnUiThread(() -> {
            String addr = device.getAddress();
            Log.i(TAG, ">>> onBlueChatDeviceFound called for: " + addr);

            String deviceId = meshManager.getDeviceIdFromMac(addr);
            if (deviceId != null) {
                discoveredDevices.put(deviceId, device);
                macToIdMapping.put(addr, deviceId);
                Log.i(TAG, "    Stored device with ID: " + deviceId + " (MAC: " + addr + ")");
            } else {
                discoveredDevices.put(addr, device);
                Log.w(TAG, "    Device ID not found for MAC: " + addr);
            }

            boolean isConnected = meshManager.isDeviceConnected(addr);
            String status = isConnected ? "[Connected]" : "[Available]";

            String displayName = device.getName() != null ? device.getName() : "Unknown";
            String displayId = deviceId != null ? deviceId.substring(0, Math.min(12, deviceId.length())) : addr;

            String deviceInfo = displayId + " " + status + " - " + displayName;

            boolean found = false;
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).startsWith(displayId + " ")) {
                    devices.set(i, deviceInfo);
                    found = true;
                    Log.i(TAG, "    Updated existing device in list at position " + i);
                    break;
                }
            }

            if (!found) {
                devices.add(deviceInfo);
                Log.i(TAG, "    Added NEW device to list. Total devices: " + devices.size());
                updateStatus("Found device: " + deviceId);
                addDebugLog("Found device: " + displayName + " | ID: " + deviceId);
            }

            deviceAdapter.notifyDataSetChanged();
            Log.i(TAG, "    Device list updated and adapter notified");
        });
    }

    private void onMessageReceived(ChatMessage message, String fromAddress) {
        Log.i(TAG, ">>> onMessageReceived CALLED in MainActivity!");
        Log.i(TAG, "    From: " + fromAddress);
        Log.i(TAG, "    Message type: " + message.type);
        Log.i(TAG, "    Payload: " + message.encryptedPayload);
        Log.i(TAG, "    SenderId: " + message.senderId);
        Log.i(TAG, "    DestId: " + message.destId);
        Log.i(TAG, "    MsgId: " + message.msgId);

        runOnUiThread(() -> {
            try {
                String plainText = message.encryptedPayload;

                addDebugLog("💬 RECEIVED from " + fromAddress + ": " + plainText);

                new Thread(() -> {
                    try {
                        MyChatEntity existing = myChatDao.getAllChats().stream()
                                .filter(c -> c.msgId.equals(message.msgId))
                                .findFirst()
                                .orElse(null);

                        if (existing == null) {
                            MyChatEntity chatEntity = new MyChatEntity(
                                message.msgId, message.senderId, message.destId, plainText,
                                message.timestamp, false, true
                            );
                            myChatDao.insert(chatEntity);
                            Log.i(TAG, "    ✓ Saved to database: " + message.msgId);
                        } else {
                            Log.i(TAG, "    Message already in database: " + message.msgId);
                        }

                        Intent broadcastIntent = new Intent("com.example.bluechats.MESSAGE_RECEIVED");
                        broadcastIntent.putExtra("senderId", message.senderId);
                        broadcastIntent.putExtra("text", plainText);
                        sendBroadcast(broadcastIntent);
                        Log.i(TAG, "    ✓ Broadcast sent to ChatActivity");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to save to database", e);
                    }
                }).start();

                String displayMsg = formatMessage(message.senderId, plainText);
                Log.i(TAG, "    Adding to messages list: " + displayMsg);

                if (!messages.contains(displayMsg)) {
                    messages.add(displayMsg);
                    chatAdapter.notifyDataSetChanged();
                    chatList.smoothScrollToPosition(messages.size() - 1);
                    Log.i(TAG, "    ✓ Message added to UI. Total messages: " + messages.size());
                } else {
                    Log.i(TAG, "    Message already in UI list, skipping");
                }

                updateStatus("Message received from " + fromAddress.substring(0, Math.min(8, fromAddress.length())));

                Snackbar.make(findViewById(android.R.id.content),
                    "New message: " + plainText.substring(0, Math.min(30, plainText.length())),
                    Snackbar.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to process message", e);
                e.printStackTrace();
                updateStatus("Failed to process message");
                addDebugLog("✗ Failed to process message: " + e.getMessage());
            }
        });
    }

    private String formatMessage(String sender, String text) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String time = sdf.format(new Date());
        return String.format("[%s] %s: %s", time, sender, text);
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> txtStatus.setText("Status: " + status));
    }

    private void initializeApp() {
        addDebugLog("=== STARTING DIAGNOSTICS ===");
        addDebugLog("Android SDK: " + Build.VERSION.SDK_INT);
        boolean allGood = true;

        // 1. Check all permissions with detailed status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int scanPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN);
            int connectPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT);
            int advPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE);

            addDebugLog("BLUETOOTH_SCAN: " + (scanPerm == PackageManager.PERMISSION_GRANTED ? "✓ GRANTED" : "✗ DENIED"));
            addDebugLog("BLUETOOTH_CONNECT: " + (connectPerm == PackageManager.PERMISSION_GRANTED ? "✓ GRANTED" : "✗ DENIED"));
            addDebugLog("BLUETOOTH_ADVERTISE: " + (advPerm == PackageManager.PERMISSION_GRANTED ? "✓ GRANTED" : "✗ DENIED"));

            if (scanPerm != PackageManager.PERMISSION_GRANTED ||
                connectPerm != PackageManager.PERMISSION_GRANTED ||
                advPerm != PackageManager.PERMISSION_GRANTED) {
                allGood = false;
            }
        } else {
            int btPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH);
            int btAdminPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN);

            addDebugLog("BLUETOOTH: " + (btPerm == PackageManager.PERMISSION_GRANTED ? "✓ GRANTED" : "✗ DENIED"));
            addDebugLog("BLUETOOTH_ADMIN: " + (btAdminPerm == PackageManager.PERMISSION_GRANTED ? "✓ GRANTED" : "✗ DENIED"));

            if (btPerm != PackageManager.PERMISSION_GRANTED || btAdminPerm != PackageManager.PERMISSION_GRANTED) {
                allGood = false;
            }
        }

        int locPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        int coarseLocPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION);

        addDebugLog("ACCESS_FINE_LOCATION: " + (locPerm == PackageManager.PERMISSION_GRANTED ? "✓ GRANTED" : "✗ DENIED"));
        addDebugLog("ACCESS_COARSE_LOCATION: " + (coarseLocPerm == PackageManager.PERMISSION_GRANTED ? "✓ GRANTED" : "✗ DENIED"));

        if (locPerm != PackageManager.PERMISSION_GRANTED) {
            allGood = false;
        }

        if (!allGood) {
            addDebugLog("✗ SOME PERMISSIONS MISSING!");
            Snackbar.make(findViewById(android.R.id.content),
                "⚠️ Missing permissions! Check Debug Log, then tap Fix",
                Snackbar.LENGTH_INDEFINITE)
                .setAction("Fix", v -> {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .show();
            return;
        } else {
            addDebugLog("✓ All permissions granted");
        }

        // 2. Check Location is ON
        android.location.LocationManager locationManager = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean isLocationEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);

        if (!isLocationEnabled) {
            addDebugLog("✗ LOCATION IS OFF");
            allGood = false;
            Snackbar.make(findViewById(android.R.id.content),
                "⚠️ LOCATION IS OFF! BLE needs Location ON!",
                Snackbar.LENGTH_INDEFINITE)
                .setAction("Turn ON", v -> {
                    startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                })
                .show();
            return;
        } else {
            addDebugLog("✓ Location is ON");
        }

        // 3. Check Bluetooth is ON
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (adapter == null) {
            addDebugLog("✗ BLUETOOTH NOT SUPPORTED");
            allGood = false;
            Snackbar.make(findViewById(android.R.id.content),
                "⚠️ Bluetooth not supported on this device!",
                Snackbar.LENGTH_INDEFINITE).show();
            return;
        } else if (!adapter.isEnabled()) {
            addDebugLog("✗ BLUETOOTH IS OFF");
            allGood = false;
            Snackbar.make(findViewById(android.R.id.content),
                "⚠️ BLUETOOTH IS OFF! Turn it ON!",
                Snackbar.LENGTH_INDEFINITE)
                .setAction("Turn ON", v -> {
                    startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));
                })
                .show();
            return;
        } else {
            addDebugLog("✓ Bluetooth is ON");
        }

        // 4. Check Battery Optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            boolean ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(packageName);

            if (!ignoringBatteryOptimizations) {
                addDebugLog("⚠️ Battery optimization enabled - may block BLE scanning!");
                addDebugLog("⚠️ Go to: Settings > Apps > BlueChats > Battery > Unrestricted");
            } else {
                addDebugLog("✓ Battery optimization disabled (good for BLE)");
            }

            if (pm.isPowerSaveMode()) {
                addDebugLog("⚠️ POWER SAVING MODE IS ON - will block BLE scanning!");
                addDebugLog("⚠️ Disable power saving mode in device settings");
            } else {
                addDebugLog("✓ Power saving mode is OFF");
            }
        }

        // 5. Check BLE Advertiser support
        if (adapter.getBluetoothLeAdvertiser() == null) {
            addDebugLog("✗ BLE ADVERTISING NOT SUPPORTED");
            allGood = false;
        } else {
            addDebugLog("✓ BLE Advertising supported");
        }

        // 6. Check BLE Scanner support
        if (adapter.getBluetoothLeScanner() == null) {
            addDebugLog("✗ BLE SCANNING NOT SUPPORTED");
            allGood = false;
        } else {
            addDebugLog("✓ BLE Scanning supported");
        }

        if (allGood) {
            addDebugLog("✓✓✓ ALL CHECKS PASSED - READY TO START");
        }

        localId = com.example.bluechats.util.DeviceIdManager.getDeviceId(this);
        String deviceName = com.example.bluechats.util.DeviceIdManager.getDeviceName(this);

        txtMyId.setText("📱 My ID: " + localId);
        addDebugLog("✓ Using persistent device ID: " + localId);
        addDebugLog("✓ Device name: " + deviceName);
        addDebugLog("✓ This ID is unique and persistent across app restarts");

        getSharedPreferences("bluechats", MODE_PRIVATE).edit()
                .putString("localId", localId)
                .putString("deviceName", deviceName)
                .apply();

        addDebugLog("📡 BLE Advertising: BC_" + localId.substring(0, 8));
        addDebugLog("💬 Messages use Device ID for sender/receiver");
        addDebugLog("🔗 MAC addresses only used for BLE routing");

        try {
            cryptoManager = new CryptoManager();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize CryptoManager", e);
            Toast.makeText(this, "Crypto initialization failed", Toast.LENGTH_SHORT).show();
            return;
        }

        AppDatabase db = AppDatabase.getInstance(this);
        queueDao = db.messageQueueDao();
        myChatDao = db.myChatDao();

        meshManager = BleMeshManager.getInstance(this);
        meshManager.setMessageCallback(this::onMessageReceived);
        meshManager.setDeviceDiscoveryCallback(this::onBlueChatDeviceFound);
        meshManager.setStatusCallback(this::onStatusUpdate);

        setupBluetoothReceiver();
        setupButtonListeners();

        autoStartBlueMesh();
    }

    private void onStatusUpdate(String message) {
        runOnUiThread(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String logEntry = "[" + timestamp + "] " + message;
            debugLogs.add(logEntry);
            debugAdapter.notifyDataSetChanged();
            debugList.smoothScrollToPosition(debugLogs.size() - 1);
        });
    }

    private void addDebugLog(String message) {
        runOnUiThread(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String logEntry = "[" + timestamp + "] " + message;
            debugLogs.add(logEntry);
            debugAdapter.notifyDataSetChanged();
            debugList.smoothScrollToPosition(debugLogs.size() - 1);
        });
    }

    private boolean checkAllPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        return permissionsNeeded.isEmpty();
    }

    private void requestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        if (!permissionsNeeded.isEmpty()) {
            Log.i(TAG, "Requesting permissions: " + permissionsNeeded);
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    REQUEST_PERMISSIONS);
        } else {
            Log.i(TAG, "All Bluetooth permissions already granted");
            initializeApp();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                }
            }
            if (allGranted) {
                Log.i(TAG, "All permissions granted! Starting app...");
                Snackbar.make(findViewById(android.R.id.content),
                    "✓ Permissions granted! Starting BlueChats...",
                    Snackbar.LENGTH_LONG).show();
                initializeApp();
            } else {
                Snackbar.make(findViewById(android.R.id.content),
                    "⚠️ Permissions denied! Go to Settings > Apps > BlueChats > Permissions and enable all",
                    Snackbar.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (meshManager != null) {
            meshManager.shutdown();
        }
    }

    private class SwipeGestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float diffX = e2.getX() - e1.getX();
            if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffX < 0) {
                    Intent intent = new Intent(MainActivity.this, ConversationsActivity.class);
                    startActivity(intent);
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    return true;
                }
            }
            return false;
        }
    }
}
