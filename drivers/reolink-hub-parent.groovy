/**
 * Reolink Hub Parent (V76 - full)
 */

metadata {
    definition(name: "Reolink Hub Parent", namespace: "mr_fy_hubitat", author: "mr_fy") {
        capability "Refresh"
        capability "Initialize"
        capability "Actuator"

        command "createChildDevices"
        command "clearOldStates"
        command "NotificationsOn"
        command "NotificationsOff"

        attribute "notificationStatus", "string"
        attribute "connectionStatus", "string"
        attribute "lastLoginTime", "string"
    }

    preferences {
        input "ipAddress", "text", title: "Hub IP Address", required: true
        input "username", "text", title: "Username", defaultValue: "admin"
        input "password", "password", title: "Password", required: true

        input "channelCount", "enum",
            title: "Number of channels to manage (children 0..N-1)",
            options: ["1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16"],
            defaultValue: "2",
            required: true

        input "pruneExtraChildren", "bool",
            title: "Remove child devices above channel count (manual create only)",
            defaultValue: false

        input "pollInterval", "enum",
            title: "Polling Interval",
            options: [
                "1":"Every 1 second",
                "2":"Every 2 seconds",
                "3":"Every 3 seconds",
                "4":"Every 4 seconds",
                "5":"Every 5 seconds",
                "6":"Every 6 seconds",
                "7":"Every 7 seconds",
                "8":"Every 8 seconds",
                "9":"Every 9 seconds",
                "10":"Every 10 seconds",
                "15":"Every 15 seconds",
                "30":"Every 30 seconds",
                "60":"Every 1 minute",
                "120":"Every 2 minutes",
                "300":"Every 5 minutes"
            ],
            defaultValue: "30",
            required: true

        input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
    }
}

private void dlog(String msg) { if (debugLogging) log.debug msg }

// --- Snapshot Helpers for Child ---
def getIPAddress() { return ipAddress }
def getTokenValue() { return state.token }

// --- LIFECYCLE ---
def installed() {
    initialize()
    schedulePolling()
}

def updated() {
    initialize()
    schedulePolling()
}

def initialize() {
    state.token = null
    getToken()
}

def refresh() {
    pollAll()
}

def clearOldStates() {
    log.info "Reolink Hub: Clearing states..."
    state.clear()
    initialize()
    schedulePolling()
}

// --- POLLING SCHEDULE ---
def schedulePolling() {
    unschedule("pollAll")
    unschedule("dynamicPollLoop")

    Integer seconds = (pollInterval ?: "30") as Integer

    if (seconds == 10) {
        runEvery10Seconds("pollAll")
    } else if (seconds == 30) {
        runEvery30Seconds("pollAll")
    } else if (seconds == 60) {
        runEvery1Minute("pollAll")
    } else if (seconds == 120) {
        runEvery2Minutes("pollAll")
    } else if (seconds == 300) {
        runEvery5Minutes("pollAll")
    } else {
        runIn(seconds, "dynamicPollLoop") // 1-9 and 15
    }

    log.info "Reolink Hub: Polling every ${seconds} seconds"
}

def dynamicPollLoop() {
    pollAll()
    Integer seconds = (pollInterval ?: "30") as Integer
    runIn(seconds, "dynamicPollLoop")
}

// --- LOGIN / TOKEN ---
def getToken() {
    if (!ipAddress || !username || !password) {
        sendEvent(name: "connectionStatus", value: "Config Missing")
        log.warn "Reolink Hub: Missing config"
        state.token = null
        return
    }

    def body = """[{"cmd":"Login","param":{"User":{"userName":"${username}","password":"${password}"}}}]"""
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=Login", body: body]) { resp ->
            if (resp?.data?.getAt(0)?.code == 0) {
                state.token = resp.data[0].value.Token.name
                def t = new Date().format("HH:mm:ss dd/MM", location.timeZone)
                state.lastLoginTime = t
                sendEvent(name: "lastLoginTime", value: t)
                sendEvent(name: "connectionStatus", value: "Connected")
                dlog "Reolink Hub: Login OK"
            } else {
                state.token = null
                sendEvent(name: "connectionStatus", value: "Login Failed")
                dlog "Login response: ${resp?.data}"
            }
        }
    } catch (e) {
        state.token = null
        sendEvent(name: "connectionStatus", value: "Error")
        log.error "Login error: ${e}"
    }
}

boolean ensureToken() {
    if (state.token) return true
    getToken()
    return (state.token != null)
}

// Treat only these as auth/token issues. Do NOT treat code=1 as token invalid.
private boolean isAuthTokenError(def code) {
    return (code == -6 || code == -5 || code == -7)
}

// --- NOTIFICATIONS (bulk) ---
def NotificationsOn() {
    log.info "Reolink Hub: Turning Push Notifications ON for all channels"
    getChildDevices().each { child ->
        def ch = child.getDataValue("channel")
        if (ch != null) childSetPush(ch, 1)
    }
    sendEvent(name: "notificationStatus", value: "All On")
}

def NotificationsOff() {
    log.info "Reolink Hub: Turning Push Notifications OFF for all channels"
    getChildDevices().each { child ->
        def ch = child.getDataValue("channel")
        if (ch != null) childSetPush(ch, 0)
    }
    sendEvent(name: "notificationStatus", value: "All Off")
}

// --- CHILD CONTROL ---
def childSetSpotlight(ch, stateVal, brightVal, modeVal) {
    if (!ensureToken()) return

    int channel = ch.toString().toInteger()

    // force integer brightness (avoid 100.0)
    int b
    try { b = (brightVal == null ? 100 : brightVal.toBigDecimal().intValue()) }
    catch (ignored) { b = 100 }
    b = Math.max(0, Math.min(100, b))

    int s = (stateVal as Integer)
    int m = (modeVal as Integer)

    def body = """[{"cmd":"SetWhiteLed","param":{"WhiteLed":{"bright":${b},"channel":${channel},"mode":${m},"state":${s}}}}]"""
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { resp ->
            def code = resp?.data?.getAt(0)?.code
            if (isAuthTokenError(code)) {
                dlog "SetWhiteLed auth/token error (code=${code}) -> relogin+retry"
                state.token = null
                if (ensureToken()) {
                    httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { }
                }
            } else if (debugLogging && code != 0) {
                dlog "SetWhiteLed code=${code} resp=${resp?.data}"
            }
        }
    } catch (e) {
        log.error "Spotlight Error: ${e}"
    }
}

def childSetStatusLed(ch, stateStr) {
    if (!ensureToken()) return
    int channel = ch.toString().toInteger()

    def body = """[{"cmd":"SetPowerLed","param":{"PowerLed":{"channel":${channel},"state":"${stateStr}"}}}]"""
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { resp ->
            def code = resp?.data?.getAt(0)?.code
            if (isAuthTokenError(code)) {
                dlog "SetPowerLed auth/token error (code=${code}) -> relogin+retry"
                state.token = null
                if (ensureToken()) {
                    httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { }
                }
            } else if (debugLogging && code != 0) {
                dlog "SetPowerLed code=${code} resp=${resp?.data}"
            }
        }
    } catch (e) {
        log.error "LED Error: ${e}"
    }
}

def childSetPush(ch, val) {
    if (!ensureToken()) return
    int channel = ch.toString().toInteger()

    def full = "1" * 168
    def body = """[{"cmd":"SetPushV20","param":{"Push":{"enable":${val},"scheduleEnable":${val},"schedule":{"channel":${channel},"table":{"AI_PEOPLE":"${full}","MD":"${full}"}}}}}]"""
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { resp ->
            def code = resp?.data?.getAt(0)?.code
            if (isAuthTokenError(code)) {
                dlog "SetPushV20 auth/token error (code=${code}) -> relogin+retry"
                state.token = null
                if (ensureToken()) {
                    httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body]) { }
                }
            }
        }
    } catch (e) {
        log.error "Push Error: ${e}"
    }
}

// --- POLLING ---
def pollAll() {
    if (!ensureToken()) return

    // prevent overlap at low polling intervals
    if (state._polling == true) return
    state._polling = true

    try {
        getChildDevices().each { child ->
            def chStr = child.getDataValue("channel")
            if (chStr != null) {
                int ch = chStr.toInteger()
                checkAI(ch, child)
                getPushStatus(ch, child)
            }
        }
    } finally {
        state._polling = false
    }
}

def checkAI(int ch, child) {
    try {
        httpGet([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=GetAiState&token=${state.token}&channel=${ch}", contentType: "application/json"]) { resp ->
            def data = resp?.data?.getAt(0)?.value
            if (data) {
                def p = data.people?.alarm_state ?: 0
                child.updateMotion((p == 1), (p ? "Person" : "None"))
            } else if (debugLogging) {
                def code = resp?.data?.getAt(0)?.code
                if (code != 0) dlog "GetAiState ch${ch} code=${code} resp=${resp?.data}"
            }
        }
    } catch (e) {
        if (debugLogging) dlog "GetAiState failed ch${ch}: ${e.message}"
    }
}

def getPushStatus(int ch, child) {
    try {
        httpGet([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=GetPushV20&token=${state.token}&channel=${ch}", contentType: "application/json"]) { resp ->
            def val = resp?.data?.getAt(0)?.value?.Push
            child.updatePushState(val?.enable == 1)
        }
    } catch (e) {
        if (debugLogging) dlog "GetPushV20 failed ch${ch}: ${e.message}"
    }
}

// --- NON-DESTRUCTIVE CHILD CREATION ---
def createChildDevices() {
    int count = (channelCount ?: "2").toInteger()
    count = Math.max(1, Math.min(16, count))
    int maxCh = count - 1

    log.info "Reolink Hub: Ensuring children exist for channels 0-${maxCh} (non-destructive)"

    Map<Integer, com.hubitat.app.DeviceWrapper> existing = [:]
    getChildDevices().each { cd ->
        def chStr = cd.getDataValue("channel")
        if (chStr?.isInteger()) existing[chStr.toInteger()] = cd
    }

    (0..maxCh).each { ch ->
        if (!existing.containsKey(ch)) {
            def dni = "${device.id}-ch${ch}"
            try {
                def child = addChildDevice(
                    "mr_fy_hubitat",
                    "Reolink Camera Child",
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
        getChildDevices().each { cd ->
            def chStr = cd.getDataValue("channel")
            if (chStr?.isInteger() && chStr.toInteger() > maxCh) {
                try { deleteChildDevice(cd.deviceNetworkId) } catch (ignored) { }
            }
        }
    }
}
