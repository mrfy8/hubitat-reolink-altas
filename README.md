# Reolink Altas 2K & Home Hub Pro - Hubitat Driver Suite
**Version:** Parent V76 / Child V79  
**Author:** mr_fy

This driver suite is specifically optimized for the **Reolink Altas 2K (Battery)** and the **Home Hub Pro**. It includes a unique "Kick-to-Wake" strategy to allow battery cameras to participate in fast Hubitat automations.

## Key Features
- **Reliable Token Handling:** Ignores "Code 1" API responses to prevent unnecessary re-logins.
- **Integer Force Fix:** Ensures spotlight brightness is sent as a strict Integer (prevents API rejection).
- **Double-Send Reliability:** Sends the 'ON' command twice with a delay to ensure battery cameras wake up.
- **High-Speed Heartbeat:** Adjustable 1–5s polling when active to catch AI "Person" detection instantly.
- **Smart Snapshot Management:** Local Hubitat storage with "Safety Floor" and "Storage Ceiling" cleanup logic.

## Installation
1. Install **Reolink Camera Child** (V79) in Hubitat 'Drivers Code'.
2. Install **Reolink Hub Parent** (V76) in Hubitat 'Drivers Code'.
3. Create a new Virtual Device using the **Reolink Hub Parent** driver.
4. Enter your Hub IP and Credentials, then click **Create Child Devices**.

## Optimized Altas 2K Strategy (Workaround)
Since battery cameras like the Altas 2K sleep to save power, use an external Zigbee/Z-Wave motion sensor to trigger the "Switch On" command on the Camera Child device. This wakes the camera, starts the high-speed heartbeat, and allows Hubitat to see the Reolink AI "Person" detection almost immediately.

---
*Disclaimer: Use at your own risk. Battery life will be impacted by high-frequency polling.*
