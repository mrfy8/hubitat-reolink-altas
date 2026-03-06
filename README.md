# Reolink Altas 2K & Home Hub Pro - Hubitat Driver Suite
**Author:** mr_fy

This driver suite is specifically optimized for the **Reolink Altas 2K (Battery)** and the **Home Hub Pro**. It includes a unique "Kick-to-Wake" strategy to allow battery cameras to participate in fast Hubitat automations.

## Optimized Altas 2K Strategy (Workaround)
Since battery cameras like the Altas 2K sleep to save power, use an external Zigbee/Z-Wave motion sensor to trigger the "Switch On" command on the Camera Child device. This wakes the camera, starts the heartbeat, and allows Hubitat to see the Reolink AI "Person" detection almost immediately.
Automate taking a burst of photos for when the Altas 2K fails to initiate recording video because the PIR didnt detect motion.

---
*Disclaimer: Use at your own risk. Battery life will be impacted by high-frequency polling.*
