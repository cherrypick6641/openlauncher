# OpenLauncher Mod

A customized fork of the OpenLauncher project, specifically optimized for Android car head units (tested on **Junsun V1**, designed to work on **FYT devices**). This version focuses on a cleaner user interface, enhanced navigation widgets, real-time OBD2 DPF & vehicle monitoring, and better automation for in-car use.

## 📌 Credits & Lineage
*   **Original Idea:** [dw2lam/openlauncher](https://github.com/dw2lam/openlauncher)
*   **Forked From (Base Mod):** [vickoc911/openlauncher](https://github.com/vickoc911/openlauncher)

---

## ✨ Features

### 🚗 Real-Time OBD2 Vehicle & DPF Monitoring
*   **Bluetooth Auto-Connect & Fast Reconnect:** Automatically connects to ELM327 / Vgate Vlink adapters on boot with intelligent fast retries after engine cranking.
*   **Kia / Hyundai CRDi Engine Profile (Bosch EDC17 ECU):** Native support for Mode 21 / KWP2000 diagnostic profiles (specifically calibrated for Kia Ceed JD 1.6 CRDi and compatible Hyundai/Kia diesel engines).
*   **Live Right Sidebar Telemetry Stack:**
    *   **DPF Temperature Before DPF (°C):** Live thermal readout. Includes an **Active Regeneration Alert** that highlights the gauge in your accent color when temperature exceeds **500°C**.
    *   **Soot Accumulation Mass (grams & %):** Real-time soot load tracking.
    *   **Distance Since Last Regeneration (km):** Distance traveled since the last DPF clean cycle.
    *   **Engine Coolant Temperature (°C)**
    *   **Control Module / Battery Voltage (V)**
    *   **Trip Distance Odometer (km)**
    *   **Engine Speed (RPM)**
*   **Active DPF Regen Audio Alert:** Gentle notification beep when DPF regeneration begins.
*   **Customizable Right Bar & Diagnostics:** Adjustable bar width, individual gauge toggles in Settings, manual `[RECONNECT]` button, and auto-clearing session logs (`launcher_logs.txt`).

### 🎨 UI Enhancements
*   **Decluttered Layout:** Removed the top bar entirely to maximize screen real estate.
*   **Sidebar Integration:** Essential elements from the top bar have been moved to the sidebar.
*   **Clean Look:** Removed the home button for a more streamlined aesthetic.
*   **Time & Date:** Added a dedicated date display.
*   **Custom Themes & Scaling:** Day/Night mode toggles, custom accent colors, font styling, and full UI scaling options.

### 📱 Picture-in-Picture (PIP) Widget
*   **Multi-tasking:** A brand-new PIP widget that allows you to display apps pinned to the sidebar directly within the widget area.
*   **Smart Launching:** Apps opened via the app gallery automatically launch in fullscreen.
*   **Hardware Compatibility:** Tailored for FYT devices and signed with **AOSP test keys** (*actively tested and verified on Junsun V1*).

### ⚙️ Automation & Settings
*   **App Auto-Launch:** Option to launch specified apps automatically on boot.
*   **Configurable Delay:** Set custom boot delays to ensure system stability during head unit startup sequences.

### 📦 Simplified App Gallery
*   **Unified View:** Single, clean gallery tab displaying all installed apps with a user interface.

---

