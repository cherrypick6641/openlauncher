# OpenLauncher Mod

A customized fork of the OpenLauncher project, specifically optimized for Android car head units (tested on **Junsun V1**, designed to work on **FYT devices**). This version focuses on a cleaner user interface, enhanced navigation widgets, and better automation for in-car use.

## 📌 Credits & Lineage
*   **Original Idea:** [dw2lam/openlauncher](https://github.com/dw2lam/openlauncher)
*   **Forked From (Base Mod):** [vickoc911/openlauncher](https://github.com/vickoc911/openlauncher)

---

## ✨ Features

### 🎨 UI Enhancements
*   **Decluttered Layout:** Removed the top bar entirely to maximize screen real estate.
*   **Sidebar Integration:** Essential elements from the top bar have been moved to the sidebar.
*   **Clean Look:** Removed the home button for a more streamlined aesthetic.
*   **Time & Date:** Added a dedicated date display.

### 🗺️ Enhanced Map Widget
*   **Google API Provider Extensions:** Added extra overlay layers to the native map widget.
*   **View Toggles:** Easily switch between **Satellite** or **Terrain** views.
*   **Real-time Data:** Integrated Google **Traffic info** overlay.

### 📱 Picture-in-Picture (PIP) Widget
*   **Multi-tasking:** A brand-new PIP widget that allows you to display apps pinned to the sidebar directly within the widget area.
*   **Smart Launching:** Apps opened via the app gallery automatically launch in fullscreen.
*   **Hardware Compatibility:** The implementation is tailored for FYT devices. The APK is signed with **AOSP test keys** (*actively tested and verified on Junsun V1 only*).

### ⚙️ Automation & Settings
*   **App Auto-Launch:** Added an option to launch specified apps automatically on boot.
*   **Configurable Delay:** Set a custom delay before apps launch on startup to ensure system stability during the boot sequence.

### 📦 Simplified App Gallery
*   **Unified View:** Removed confusing tabs. The app gallery now features a single, clean tab containing all installed applications that utilize a user interface.
