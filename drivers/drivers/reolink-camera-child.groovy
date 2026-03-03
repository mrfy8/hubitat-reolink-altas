/**
 * Reolink Camera Child (V79)
 */

metadata {
    definition(name: "Reolink Camera Child", namespace: "mr_fy_hubitat", author: "mr_fy") {
        capability "MotionSensor"
        capability "Refresh"
        capability "Switch"

        command "NotificationsOn"
        command "NotificationsOff"
        command "takeBurst"
        command "saveSnapshotToHub"
        command "cleanupOldSnaps", [[name:"Force Cleanup Old Snaps"]]

        attribute "personCurrentlyDetected", "string"
        attribute "lastDetectionType", "string"
        attribute "lastDetectionTime", "string"
        attribute "notificationStatus", "string"
        attribute "switch", "string"
        attribute "activeLightMode", "string"
        attribute "motionHeartbeat", "string"
        attribute "lightHeartbeat", "string"
        attribute "motion", "string"
        attribute "lastSnapshotURL", "string"
        attribute "fileCount", "number"
        attribute "lastPurgeReason", "string"
    }

    preferences {
        input "lightMode", "enum", title: "Which light should the Switch control?",
            options: ["Spotlight", "Status LED"], defaultValue: "Spotlight", required: true

        input "pulseRate", "enum", title: "Heartbeat Refresh Rate (Seconds)",
            options: ["1", "2", "3", "4", "5"], defaultValue: "2", required: true

        input "spotlightBrightness", "number", title: "Spotlight Brightness (0-100)",
            defaultValue: 100, range: "0..100", required: true

        input "retentionDays", "number", title: "Snapshot Retention (Days)",
            defaultValue: 30, range: "1..365", required: true

        input "maxFiles", "number", title: "Minimum files to always keep (Safety Floor)",
            defaultValue: 20, range: "1..500", required: true

        input "hardLimit", "number", title: "Maximum files to keep (Storage Ceiling)",
            defaultValue: 200, range: "21..2000", required: true
    }
}

// --- HEARTBEAT ---
def updateMotionHeartbeat() {
    def timeNow = new Date().format("HH:mm:ss", location.timeZone)
    sendEvent(name: "motionHeartbeat", value: timeNow, descriptionText: "Motion polling active")
}

def updateLightHeartbeat() {
    def timeNow = new Date().format("HH:mm:ss", location.timeZone)
    sendEvent(name: "lightHeartbeat", value: timeNow, descriptionText: "Light pulse active")
}

// --- AI & MOTION ---
def updateMotion(isActive, type) {
    updateMotionHeartbeat()
    def timeNow = new Date().format("HH:mm:ss dd/MM", location.timeZone)
    sendEvent(name: "motion", value: (isActive ? "active" : "inactive"))
    if (isActive) {
        sendEvent(name: "lastDetectionType", value: type)
        sendEvent(name: "lastDetectionTime", value: timeNow)
        sendEvent(name: "personCurrentlyDetected", value: (type == "Person" ? "true" : "false"))
    } else {
        sendEvent(name: "personCurrentlyDetected", value: "false")
    }
}

// Handle Boolean from Parent Driver
def updatePushState(Boolean isOn) {
    def status = isOn ? "Notifications On" : "Notifications Off"
    sendEvent(name: "notificationStatus", value: status)
}

def refresh() {
    updateMotionHeartbeat()
    parent.refresh()
}

// --- LIGHT LOGIC ---
def on() {
    log.info "${device.label} - ON triggered"
    unschedule()

    sendEvent(name: "switch", value: "on", isStateChange: true)
    sendEvent(name: "activeLightMode", value: (lightMode ?: "Spotlight"))
    updateLightHeartbeat()

    // original reliability trick: send twice
    executeLightCommand("On")
    pauseExecution(100)
    executeLightCommand("On")

    runIn(1800, autoOffSafety)
    def rate = (pulseRate ?: "2").toInteger()
    runIn(rate, pulseHeartbeat)
}

def off() {
    log.info "${device.label} - OFF triggered"
    unschedule()

    sendEvent(name: "switch", value: "off", isStateChange: true)
    sendEvent(name: "lightHeartbeat", value: "Inactive")
    executeLightCommand("Off")
}

def executeLightCommand(state) {
    def mode = lightMode ?: "Spotlight"
    def channel = getDataValue("channel")
    if (mode == "Spotlight") {
        parent.childSetSpotlight(channel, (state == "On" ? 1 : 0), (spotlightBrightness ?: 100), (state == "On" ? 1 : 0))
    } else {
        parent.childSetStatusLed(channel, (state == "On" ? "On" : "Off"))
    }
}

def pulseHeartbeat() {
    if (device.currentValue("switch") == "on") {
        updateLightHeartbeat()
        executeLightCommand("On")
        def rate = (pulseRate ?: "2").toInteger()
        runIn(rate, pulseHeartbeat)
    }
}

def autoOffSafety() { off() }

// --- SNAPSHOT & BURST ---
def takeBurst() {
    log.info "${device.label} - Starting 5-snapshot burst."
    updateMotionHeartbeat()
    executeSingleSnap(false)
    runIn(3, "burst2")
    runIn(6, "burst3")
    runIn(9, "burst4")
    runIn(12, "burst5")
}

def burst2() { executeSingleSnap(false) }
def burst3() { executeSingleSnap(false) }
def burst4() { executeSingleSnap(false) }
def burst5() { executeSingleSnap(true) }

def saveSnapshotToHub() { executeSingleSnap(true) }

def executeSingleSnap(doCleanup = true) {
    def ip = parent.getIPAddress()
    def token = parent.getTokenValue()
    def ch = getDataValue("channel")
    if (!ip || !token || ch == null) return

    def ts = new Date().format("yyyyMMdd_HHmmss_SSS", location.timeZone)
    def fileName = "reolink_snaps_cam${ch}_${ts}.jpg"

    try {
        httpGet([uri: "http://${ip}/cgi-bin/api.cgi?cmd=Snap&channel=${ch}&token=${token}", contentType: "image/jpeg", timeout: 20]) { resp ->
            if (resp && resp.status == 200) {
                byte[] imageBytes = resp.data.bytes
                if (imageBytes && imageBytes.length > 0) {
                    uploadHubFile(fileName, imageBytes)
                    log.info "SUCCESS: Uploaded ${fileName}"
                    sendEvent(name: "lastSnapshotURL", value: "/local/${fileName}")
                    if (doCleanup) runIn(30, "cleanupOldSnaps")
                }
            }
        }
    } catch (e) {
        log.error "Snapshot error: ${e}"
    }
}

// --- SNAPSHOT CLEAN-UP ---
def cleanupOldSnaps() {
    def prefix = "reolink_snaps_"
    long d = (retentionDays ?: 30) as Long
    int floor = (maxFiles ?: 20) as Integer
    int ceiling = (hardLimit ?: 200) as Integer
    long cutoff = now() - (d * 86400000L)

    try {
        httpGet([uri: "http://127.0.0.1:8080/hub/fileManager/json", timeout: 15]) { resp ->
            if (resp && resp.data && resp.data.files) {
                def myFiles = resp.data.files.findAll { it?.name?.startsWith(prefix) }
                myFiles.sort { it.name } // ordered oldest->newest due to timestamp naming

                int initialCount = myFiles.size()
                sendEvent(name: "fileCount", value: initialCount)

                int deleteCount = 0
                String reason = "N/A - Clean"

                // 1) Ceiling purge (oldest first)
                if (myFiles.size() > ceiling) {
                    int overage = myFiles.size() - ceiling
                    reason = "Storage Limit Reached"
                    for (int i = 0; i < overage; i++) {
                        try { deleteHubFile(myFiles[i].name) } catch (ignored) { }
                        deleteCount++
                    }
                    myFiles = myFiles.drop(overage)
                }

                // 2) Age purge (only if still above floor)
                if (myFiles.size() > floor) {
                    int ageLimit = myFiles.size() - floor
                    int agePurgeCount = 0
                    for (int i = 0; i < ageLimit; i++) {
                        def file = myFiles[i]
                        if (file?.modified == null) continue

                        long fMod = file.modified as Long
                        long fileTime = (fMod < 2000000000L) ? (fMod * 1000L) : fMod // seconds vs ms

                        if (fileTime < cutoff) {
                            try { deleteHubFile(file.name) } catch (ignored) { }
                            deleteCount++
                            agePurgeCount++
                        }
                    }
                    if (agePurgeCount > 0) {
                        reason = (reason == "Storage Limit Reached") ? "Limit + Expired" : "Expired (Age)"
                    }
                }

                int remaining = Math.max(0, initialCount - deleteCount)
                sendEvent(name: "fileCount", value: remaining)
                sendEvent(name: "lastPurgeReason", value: reason)

                if (deleteCount > 0) {
                    log.info "Audit: Purge Complete. Deleted ${deleteCount} files. Reason: ${reason}"
                }
            }
        }
    } catch (e) {
        log.error "Cleanup error: ${e}"
    }
}

// --- NOTIFICATIONS ---
def NotificationsOn() { parent.childSetPush(getDataValue("channel"), 1) }
def NotificationsOff() { parent.childSetPush(getDataValue("channel"), 0) }
