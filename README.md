# BlueChats

[![Android](https://img.shields.io/badge/platform-android-green.svg)](https://www.android.com/)
[![API](https://img.shields.io/badge/API-24%2B-blue.svg)](https://developer.android.com/about/dashboards)
[![License](https://img.shields.io/badge/license-MIT-yellow.svg)](LICENSE)

> **Offline mesh messaging for adventurers, travelers, and anyone off the grid.**

BlueChats is a peer-to-peer messaging application that uses Bluetooth Low Energy (BLE) to create a decentralized mesh network. Communicate with others without cellular, WiFi, or internet connectivity — perfect for airplanes, remote hiking trails, mountain treks, concerts, festivals, and disaster scenarios.

---

## Features

| Feature | Description |
|---------|-------------|
| **Mesh Networking** | Messages hop through nearby devices to reach distant recipients |
| **End-to-End Encryption** | BouncyCastle-powered encryption keeps messages private |
| **Offline Messaging** | No internet, cellular, or WiFi required |
| **QR Code Pairing** | Quickly connect with others by scanning QR codes |
| **Persistent Storage** | Room database stores messages and contact history |
| **Message Queue** | Store-and-forward for when recipients are out of range |
| **Smart Routing** | Dynamic routing tables optimize message delivery paths |

---

## Use Cases

- **Air Travel** – Chat with passengers on the same flight without paid WiFi
- **Mountain Treks** – Stay connected with your group in valleys and ridges
- **Remote Hiking** – Coordinate on trails with no cell coverage
- **Music Festivals** – Message friends in crowds where networks are overloaded
- **Emergency Preparedness** – Communicate during natural disasters when infrastructure fails
- **Underground/Subway** – Pass messages through a chain of commuters

---

## How It Works

```
┌─────────┐      BLE       ┌─────────┐      BLE       ┌─────────┐
│ Alice   │◄──────────────►│  Bob    │◄──────────────►│ Carol   │
│ (Sender)│                │(Relay)  │                │(Receiver│
└─────────┘                └─────────┘                └─────────┘
      │                                                    ▲
      │         (No direct connection possible)            │
      │                                                    │
      └────────────────── Mesh Route ──────────────────────┘
```

1. **Discovery** – Devices scan for nearby BlueChats peers via BLE advertising
2. **Pairing** – Exchange identity via QR codes or automatic discovery
3. **Routing** – Messages traverse the mesh using dynamic routing tables
4. **Delivery** – Store-and-forward ensures eventual delivery when paths exist

---

## Technical Stack

| Component | Technology |
|-----------|------------|
| Platform | Android (API 24+) |
| Language | Java |
| Database | Room Persistence Library |
| Serialization | Gson |
| Encryption | BouncyCastle |
| QR Scanning | ZXing Embedded |
| UI | Material Design, ViewPager2 |

---

## Architecture

```
app/
├── controller/          # Activities (UI layer)
│   ├── MainActivity.java
│   ├── ChatActivity.java
│   └── ConversationsActivity.java
├── model/               # Business logic
│   ├── BleMeshManager.java        # BLE mesh operations
│   ├── BluetoothMeshManager.java  # Bluetooth classic fallback
│   ├── RoutingTable.java          # Mesh routing logic
│   └── CryptoManager.java         # Encryption/decryption
├── database/            # Data persistence
│   ├── AppDatabase.java
│   ├── MessageEntity.java
│   ├── ContactEntity.java
│   └── MessageQueueEntity.java
├── adapter/             # RecyclerView adapters
└── util/                # Utilities
    ├── QRUtils.java
    └── DeviceIdManager.java
```

---

## Getting Started

### Prerequisites

- Android device with BLE support (Android 7.0+, API 24+)
- Location permission enabled (required for BLE scanning)
- Nearby BlueChats users to mesh with

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/bluechats.git
   cd bluechats
   ```

2. Open in Android Studio and sync Gradle

3. Build and run on your device

### Permissions

The app requires the following permissions:

- `BLUETOOTH` / `BLUETOOTH_ADMIN` – Core BLE operations
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` – Android 12+ compatibility
- `ACCESS_FINE_LOCATION` – BLE scanning requirement on Android
- `ACCESS_COARSE_LOCATION` – Fallback location permission

---

## Security

- **Device Identity** – Unique device IDs generated on first launch
- **Public Key Exchange** – QR codes facilitate secure key sharing
- **Encrypted Payloads** – All messages encrypted with BouncyCastle
- **No Central Server** – Pure P2P, no data leaves the mesh

---

## Limitations

| Limitation | Details |
|------------|---------|
| Range | ~100m between hops (BLE Classic/LE range) |
| Bandwidth | Suitable for text, not media/files |
| Latency | Increases with hop count and network size |
| Battery | BLE scanning impacts battery life |
| Compatibility | Android only (iOS support planned) |

---

## Roadmap

- [ ] iOS cross-platform compatibility
- [ ] File/image transfer support
- [ ] Group chat functionality
- [ ] Message acknowledgment/receipts
- [ ] Offline map integration
- [ ] Emergency SOS beacon mode

---

## Contributing

Contributions welcome! Areas of interest:

- Improving routing algorithms
- Extending range via Bluetooth 5 features
- Battery optimization
- iOS port

---

## License

MIT License – see [LICENSE](LICENSE) for details.

---

## Acknowledgments

Built for explorers, by explorers. Stay connected, even when you're off the grid.

---

<div align="center">

**Made with** 🔷 **for offline communication**

</div>
