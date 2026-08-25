# 🔥 HotShareFile

**Easily share and transfer files bidirectionally between your Android phone and any device (Android, iOS, laptop, etc.) over a hotspot or local network.**

---

## ✨ Features

- 🔄 **Two-Way Transfer:** Upload and download files seamlessly from *both* the Android app and the web interface.
- 📊 **Progress Tracking:** Real-time progress bars for monitoring both upload and download transfers.
- 🌐 **Dynamic IP Detection:** The app automatically detects and displays your correct local network IP address (no hardcoded IPs).
- 📱 **Interactive QR Code:** Tap on the displayed dynamic URL to instantly convert it into a scannable QR code.
- 🚀 **System-Wide Sharing:** Appears in the Android "Share" menu, allowing you to send files directly from Gallery, File Manager, or other apps without opening HotShareFile first.
- 📂 **Multi-File Support:** Select and share multiple files at once for efficient bulk transfer. *(Remove this line if your app only supports single file selection)*
- 🔒 **Private & Offline:** Direct device-to-device communication via hotspot or local network. No internet connection or external servers are required.
- ⚡ **Simple & Fast:** No cables, Bluetooth, or additional apps needed on the receiving device.

## 🚀 Quick Start

### Prerequisites

- An Android device running **Android 5.0 (API 21) or higher**.
- The receiving device must have a web browser.

### Installation & Usage

1. **Install the App:**
   - Download the latest APK from the [Releases](https://github.com/mmd600700/HotShareFile/releases) section.
   - Or, clone and build the project using Android Studio.

2. **How to Use:**
   - **On the Sender Device (Android):**
     1. Open the **HotShareFile** app, or simply select a file in any app (like Gallery) and tap **Share > HotShareFile**.
     2. Turn on your device's hotspot (or connect to a shared local Wi-Fi network).
     3. The app will automatically detect and display your local IP address. **Tap the displayed URL** (e.g., `http://<Your-Local-IP>:8888`) to instantly toggle it into a QR code for easy sharing.
     4. You can select files to share from the app, or prepare to receive files from other devices.
   - **On the Receiver Device (Any Device with a Browser):**
     1. Connect to the sender's hotspot or the same local Wi-Fi network.
     2. Open a web browser and enter the **exact dynamic address** shown in the app, or scan the QR code.
        > *Note: The IP address is dynamically detected based on your specific network configuration. Do not use generic examples like `192.168.43.1`; always use the address displayed by the app.*
     3. You can now **download** files shared from the Android device, or **upload** files from your device directly to the Android phone. A real-time **progress bar** will display the transfer status on both the web page and the Android app.

## 🤝 Contributing

We welcome contributions! Here's how you can help:

1. Fork the repository.
2. Create a new branch for your feature (`git checkout -b feature/AmazingFeature`).
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`).
4. Push to the branch (`git push origin feature/AmazingFeature`).
5. Open a Pull Request.

---

**⭐️ If you find this project useful, please give it a star to help others discover it!**
