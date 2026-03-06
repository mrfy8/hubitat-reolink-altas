/**
 * Reolink Home Hub Pro - Parent
 *
 * PARENT_DRIVER_VERSION "20260305-REOLINK-HOMEHUBPRO-PARENT-v2"
 *
 *  - Enable and Disable Reolink Push Notifications from Parent or single child.
 *      NOTE: the change in Notifications is made through API and the change is not reflected in the Reolink App.
 *            but it is applied globally and overrides what is shown in the app.
 *  - To clear old states and variables during troubleshooting - Uncomment the 3 lines in the metadata
 */

metadata {
    definition(name: "Reolink Hub Parent", namespace: "mr_fy_hubitat", author: "mr_fy") {
        capability "Refresh"
        capability "Initialize"
        capability "Actuator"

        command "createChildDevices"
        command "ReolinkPushNotificationsOn"
        command "ReolinkPushNotificationsOff"

        // Troubleshooting - Uncomment the next 3 lines
        // command "Clear Current States Values"
        // command "Clear State Variables"
        // command "Clear Orphaned States"

        attribute "Reolink Push Notification Status", "string"
        attribute "Connection Status", "string"
        attribute "Last Login Time", "string"
        attribute "Parent Driver Version", "string"
        attribute "Polling Lock", "string"
    }

    preferences {
        input "ipAddress", "text",
            title: "Home Hub Pro IP Address",
            description: "Enter the local IP address of the Reolink Home Hub Pro (IPv4 only), e.g. 192.168.1.50 — do not include http://, ports, or a trailing slash.",
            required: true
                
        input "username", "text",
            title: "Home Hub Pro Username",
            defaultValue: "admin",
        	description: "Enter the username of the local Reolink account on the Home Hub Pro used for API access (e.g. admin).",
        	required: true
        
        input "password", "password",
            title: "Home Hub Pro Password",
            description: "Enter the password for the Reolink account on the Home Hub Pro used for API authentication.",
            required: true

        input "channelCount", "enum",
            title: "Number of Channels",
            description: "Set the number of Channels (Cameras) to manage on the Home Hub Pro (Max 16). This value is used when creating child devices",
            options: ["1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16"],
            defaultValue: "2",
            required: true

        input "pruneExtraChildren", "bool",
            title: "Remove child devices above channel count",
            description: "When enabled, runs when you press Create Child Devices.",
            defaultValue: false

        input "pollInterval", "enum",
            title: "Home Hub Pro Polling Interval",
            description: "How often the driver polls the Reolink Home Hub Pro for camera status updates (motion, AI detection, battery, and notifications).",
            options: [
                "1":"Every 1 second",
                "2":"Every 2 seconds",
                "3":"Every 3 seconds",
                "4":"Every 4 seconds",
                "5":"Every 5 seconds",
                "10":"Every 10 seconds",
                "15":"Every 15 seconds",
                "30":"Every 30 seconds",
                "60":"Every 1 minute",
                "120":"Every 2 minutes",
                "300":"Every 5 minutes"
            ],
            defaultValue: "5",
            required: true

        input "debugLogging", "bool",
            title: "Enable Debug Logging",
            description: "Automatically turns off after 2 hours.",
            defaultValue: false
    }
}

def PARENT_DRIVER_VERSION() { "20260305-REOLINK-HOMEHUBPRO-PARENT-v2" }
def dlog(msg) { if (debugLogging) log.debug msg }

// --- Helpers for Child ---
def getIPAddress() { return ipAddress }
def getTokenValue() { return state.token }

// --- Lifecycle ---
def installed() { initialize() }
def updated() {
    initialize()
	if (debugLogging) runIn(7200, "disableDebugLogging", [overwrite: true])
}

def initialize() {
    sendEvent(name: "Parent Driver Version", value: PARENT_DRIVER_VERSION(), isStateChange: true)

    if (device.currentValue("Reolink Push Notification Status") == null) sendEvent(name: "Reolink Push Notification Status", value: "Unknown", isStateChange: true)
    if (device.currentValue("Connection Status") == null) sendEvent(name: "Connection Status", value: "Unknown", isStateChange: true)
    if (device.currentValue("Polling Lock") == null) sendEvent(name: "Polling Lock", value: "false", isStateChange: true)

    state.token = null
    state.pollingLock = false

    getToken()
    schedulePolling()
}

def refresh() { pollAll() }

def disableDebugLogging() {
    log.info "${device.label ?: device.name}: Auto-disabling debug logging after 2 hours"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

// --- Commands (Parent maintenance) ---
def "Clear Current States Values"() {
    int cleared = 0
    try {
        def protect = ["DeviceWatch-DeviceStatus", "healthStatus"] as Set
        device.currentStates?.each { st ->
            String n = st?.name
            if (!n) return
            if (protect.contains(n)) return
            device.deleteCurrentState(n)
            cleared++
        }
        log.info "Reolink Hub: Cleared ${cleared} current state values"
    } catch (e) {
        log.error "Clear Current States Values error: ${e}"
    }
}

def "Clear State Variables"() {
    log.warn "Reolink Hub: Clear State Variables requested"
    try { unschedule() } catch (ignored) { }
    try { state.clear() } catch (e) { log.warn "state.clear() failed: ${e}" }
    initialize()
}

def "Clear Orphaned States"() {
    try {
        Set supported = (device.supportedAttributes?.collect { it.name } ?: []) as Set
        Set protect = ["DeviceWatch-DeviceStatus", "healthStatus"] as Set
        int removed = 0
        device.currentStates?.each { st ->
            String n = st?.name
            if (!n) return
            if (protect.contains(n)) return
            if (!supported.contains(n)) {
                device.deleteCurrentState(n)
                removed++
            }
        }
        log.info "Reolink Hub: Cleared ${removed} orphaned currentStates"
    } catch (e) {
        log.error "Clear Orphaned States error: ${e}"
    }
}

// --- Polling schedule ---
def schedulePolling() {
    unschedule("pollAll")
    unschedule("dynamicPollLoop")

    Integer seconds = safeInt(pollInterval, 5)

    if (seconds == 10) runEvery10Seconds("pollAll")
    else if (seconds == 30) runEvery30Seconds("pollAll")
    else if (seconds == 60) runEvery1Minute("pollAll")
    else if (seconds == 120) runEvery2Minutes("pollAll")
    else if (seconds == 300) runEvery5Minutes("pollAll")
    else runIn(seconds, "dynamicPollLoop", [overwrite: true])

    log.info "Reolink Hub: Polling every ${seconds}s"
}

def dynamicPollLoop() {
    pollAll()
    Integer seconds = safeInt(pollInterval, 5)
    runIn(seconds, "dynamicPollLoop", [overwrite: true])
}

// --- Login / Token ---
def getToken() {
    if (!ipAddress || !username || !password) {
        sendEvent(name: "Connection Status", value: "Config Missing", isStateChange: true)
        log.warn "Reolink Hub: Missing config"
        state.token = null
        return
    }

    def body = [[cmd: "Login", param: [User: [userName: username, password: password]]]]
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=Login", body: body]) { resp ->
            if (resp?.data?.getAt(0)?.code == 0) {
                state.token = resp.data[0].value.Token.name
                def t = new Date().format("HH:mm:ss dd/MM", location.timeZone)
                sendEvent(name: "Last Login Time", value: t, isStateChange: true)
                sendEvent(name: "Connection Status", value: "Connected", isStateChange: true)
                log.info "Reolink Hub: Login success"
                dlog "Reolink Hub: token=${state.token}"
            } else {
                state.token = null
                sendEvent(name: "Connection Status", value: "Login Failed", isStateChange: true)
                dlog "Login response: ${resp?.data}"
            }
        }
    } catch (e) {
        state.token = null
        sendEvent(name: "Connection Status", value: "Error", isStateChange: true)
        log.error "Login error: ${e}"
    }
}

def ensureToken() {
    if (state.token) return true
    getToken()
    return (state.token != null)
}

def isAuthTokenError(code) { return (code == -6 || code == -5 || code == -7) }

// --- API helper ---
def apiPost(String cmd, Map param, Closure handler) {
    if (!ensureToken()) return
    def payload = [[cmd: cmd, action: 0, param: param]]
    def uri = "http://${ipAddress}/cgi-bin/api.cgi?cmd=${cmd}&token=${state.token}"
    try {
        httpPostJson([uri: uri, body: payload]) { resp ->
            def code = resp?.data?.getAt(0)?.code
            if (isAuthTokenError(code)) {
                dlog "Reolink Hub: ${cmd} token error (code=${code}) -> relogin+retry"
                state.token = null
                if (ensureToken()) {
                    def uri2 = "http://${ipAddress}/cgi-bin/api.cgi?cmd=${cmd}&token=${state.token}"
                    httpPostJson([uri: uri2, body: payload]) { resp2 -> handler?.call(resp2) }
                }
                return
            }
            handler?.call(resp)
        }
    } catch (e) {
        dlog "${cmd} failed: ${e.message}"
    }
}

// --- Child call (NO SPAM) ---
def safeChildCall(child, String method, List args = []) {
    try {
        child."${method}"(*args)
        return true
    } catch (MissingMethodException ignored) {
        // do nothing (no spam)
        return false
    } catch (e) {
        // only warn on real errors
        log.warn "Child call failed ${child?.displayName}.${method}(${args}): ${e.message}"
        return false
    }
}

// --- Child creation ---
def createChildDevices() {
    int count = safeInt(channelCount, 2)
    count = Math.max(1, Math.min(16, count))
    int maxCh = count - 1

    log.info "Reolink Hub: Ensuring children exist for channels 0-${maxCh}."

    Map existing = [:]
    getChildDevices().each { cd ->
        Integer ch = safeIntOrNull(cd.getDataValue("channel"))
        if (ch != null) existing[ch] = cd
    }

    (0..maxCh).each { ch ->
        if (!existing.containsKey(ch)) {
            def dni = "${device.id}-ch${ch}"
            try {
                def child = addChildDevice(
                    "mr_fy_hubitat",
                    "Reolink Altas 2K Camera Child",
                    dni,
                    [name: "Camera Ch${ch}", label: "${device.displayName} Ch${ch}"]
                )
                child.updateDataValue("channel", ch.toString())
                log.info "Reolink Hub: Created child for channel ${ch}"
            } catch (e) {
                log.error "Reolink Hub: Failed creating child for channel ${ch}: ${e.message}"
            }
        }
    }

    if (pruneExtraChildren) {
        int pruned = 0
        getChildDevices().each { cd ->
            Integer ch = safeIntOrNull(cd.getDataValue("channel"))
            if (ch != null && ch > maxCh) {
                try {
                    deleteChildDevice(cd.deviceNetworkId)
                    pruned++
                    log.info "Reolink Hub: Pruned child for channel ${ch}"
                } catch (e) {
                    log.warn "Reolink Hub: Failed to prune child for channel ${ch}: ${e.message}"
                }
            }
        }
        if (pruned > 0) log.info "Reolink Hub: Pruned ${pruned} extra children"
    }
}

// --- Reolink Push Notifications (bulk) ---
def ReolinkPushNotificationsOn() {
    log.info "Reolink Hub: Turning Reolink Push Notifications ON for all channels"
    getChildDevices().each { child ->
        Integer ch = safeIntOrNull(child.getDataValue("channel"))
        if (ch != null) {
            childSetPush(ch, 1)
            child.sendEvent(name: "Reolink Push Notification Status", value: "Reolink Push Notifications On", isStateChange: true)
            safeChildCall(child, "updatePushState", [true])
        }
    }
    sendEvent(name: "Reolink Push Notification Status", value: "All On", isStateChange: true)
}

def ReolinkPushNotificationsOff() {
    log.info "Reolink Hub: Turning Reolink Push Notifications OFF for all channels"
    getChildDevices().each { child ->
        Integer ch = safeIntOrNull(child.getDataValue("channel"))
        if (ch != null) {
            childSetPush(ch, 0)
            child.sendEvent(name: "Reolink Push Notification Status", value: "Reolink Push Notifications Off", isStateChange: true)
            safeChildCall(child, "updatePushState", [false])
        }
    }
    sendEvent(name: "Reolink Push Notification Status", value: "All Off", isStateChange: true)
}

def childSetPush(ch, val) {
    if (!ensureToken()) return
    int channel = safeInt(ch, 0)

    def full = "1" * 168
    def body = [[cmd: "SetPushV20", param: [Push: [
        enable: val,
        scheduleEnable: val,
        schedule: [channel: channel, table: [
            AI_PEOPLE : full,
            AI_VEHICLE: full,
            AI_DOG_CAT: full,
            AI_OTHER  : full
        ]]
    ]]]]

    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { resp ->
            def code = resp?.data?.getAt(0)?.code
            if (isAuthTokenError(code)) {
                state.token = null
                if (ensureToken()) httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { }
            }
        }
    } catch (e) {
        log.error "Push Error: ${e}"
    }
}

// --- Light control (child calls) ---
def childSetSpotlight(ch, stateVal, modeVal) {
    if (!ensureToken()) return
    int channel = safeInt(ch, 0)
    int s = safeInt(stateVal, 0)
    int m = safeInt(modeVal, 0)

    def body = [[cmd: "SetWhiteLed", param: [WhiteLed: [channel: channel, mode: m, state: s]]]]
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { resp ->
            def code = resp?.data?.getAt(0)?.code
            if (isAuthTokenError(code)) {
                state.token = null
                if (ensureToken()) httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { }
            }
        }
    } catch (e) {
        log.error "Spotlight Error: ${e}"
    }
}

def childSetStatusLed(ch, stateStr) {
    if (!ensureToken()) return
    int channel = safeInt(ch, 0)

    def body = [[cmd: "SetPowerLed", param: [PowerLed: [channel: channel, state: stateStr]]]]
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { resp ->
            def code = resp?.data?.getAt(0)?.code
            if (isAuthTokenError(code)) {
                state.token = null
                if (ensureToken()) httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { }
            }
        }
    } catch (e) {
        log.error "LED Error: ${e}"
    }
}

// --- Polling lock ---
def acquirePollingLock() {
    if (state.pollingLock == true) return false
    state.pollingLock = true
    sendEvent(name: "Polling Lock", value: "true", isStateChange: true)
    return true
}

def releasePollingLock() {
    state.pollingLock = false
    sendEvent(name: "Polling Lock", value: "false", isStateChange: true)
}

// --- Polling (AI + Push + Battery) ---
def pollAll() {
    if (!ensureToken()) return
    if (!acquirePollingLock()) return

    try {
        getChildDevices().each { child ->
            Integer ch = safeIntOrNull(child.getDataValue("channel"))
            if (ch == null) return

            pollAI_viaGetEvents(ch, child)
            pollPushStatus(ch, child)
            pollBattery(ch, child)
        }
    } finally {
        releasePollingLock()
    }
}

// --- AI via GetEvents (summary) ---
def pollAI_viaGetEvents(int ch, child) {
    long end = (now() / 1000L) as long
    long start = end - 90L

    apiPost("GetEvents", [channel: ch, startTime: start, endTime: end, num: 20]) { resp ->
        def d0 = resp?.data?.getAt(0)
        if (d0?.code != 0) return
        def v = d0?.value
        def ai = v?.ai

        Integer people = safeIntOrNull(ai?.people?.alarm_state)
        Integer vehicle = safeIntOrNull(ai?.vehicle?.alarm_state)
        Integer animal = safeIntOrNull(ai?.dog_cat?.alarm_state)
        Integer other  = safeIntOrNull(ai?.other?.alarm_state)

        boolean active = ((people == 1) || (vehicle == 1) || (animal == 1) || (other == 1))

        // IMPORTANT: only meaningful when active; child will ignore type/time when inactive anyway.
        String type = "Animal"
        if (people == 1) type = "Person"
        else if (vehicle == 1) type = "Vehicle"
        else if ((animal == 1) || (other == 1)) type = "Animal"

        String ts = new Date().format("HH:mm:ss dd/MM", location.timeZone)

        safeChildCall(child, "updateAI", [active, type, ts])
        if (!child.hasAttribute("AI Motion")) {
            // fallback if user assigns wrong child driver
            child.sendEvent(name: "AI Motion", value: (active ? "active" : "inactive"), isStateChange: true)
        }
    }
}

// --- Push status via GetPushV20 ---
def pollPushStatus(int ch, child) {
    try {
        httpGet([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=GetPushV20&token=${state.token}&channel=${ch}", contentType: "application/json"]) { resp ->
            def d0 = resp?.data?.getAt(0)
            def v = d0?.value
            def candidates = [ v?.Push, v?.push, v ]
            Integer enable = null
            for (def c : candidates) {
                def en = c?.enable
                if (en != null) { enable = safeIntOrNull(en); break }
            }
            if (enable == null) return
            boolean isOn = (enable == 1)

            child.sendEvent(name: "Reolink Push Notification Status", value: (isOn ? "Reolink Push Notifications On" : "Reolink Push Notifications Off"), isStateChange: true)
            safeChildCall(child, "updatePushState", [isOn])
        }
    } catch (e) {
        dlog "GetPushV20 failed ch${ch}: ${e.message}"
    }
}

// --- Battery ---
def pollBattery(int ch, child) {
    def cmds = ["GetBatteryInfo", "GetBattery", "GetPowerStatus", "GetDevInfo"]
    for (String cmd : cmds) {
        boolean handled = false
        apiPost(cmd, [channel: ch]) { resp ->
            Integer pct = extractBatteryPercent(resp)
            if (pct != null) {
                safeChildCall(child, "updateBatteryPercent", [pct])
                handled = true
            }
        }
        if (handled) return
    }
}

def extractBatteryPercent(resp) {
    try {
        def d0 = resp?.data?.getAt(0)
        if (d0?.code != 0) return null
        def v = d0?.value
        def candidates = [ v?.battery, v?.Battery, v?.Power, v?.power, v ]
        for (def c : candidates) {
            def p = c?.percent
            if (p == null) p = c?.batteryPercent
            if (p == null) p = c?.battery_level
            if (p == null) p = c?.batteryLevel
            if (p != null) {
                Integer x = safeIntOrNull(p)
                if (x != null && x >= 0 && x <= 100) return x
            }
        }
    } catch (ignored) { }
    return null
}

// --- Utils ---
def safeInt(def v, int dflt) {
    try { if (v == null) return dflt; return (v as Integer) } catch (ignored) { }
    try { return v.toString().toInteger() } catch (ignored2) { }
    return dflt
}

def safeIntOrNull(def v) {
    try { if (v == null) return null; return (v as Integer) } catch (ignored) { }
    try { return v.toString().toInteger() } catch (ignored2) { }
    return null
}
