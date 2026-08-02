/**
 *  RIB Relay Switch
 *
 *  Copyright 2026 ValkyrieTech LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  ---------------------------------------------------------------------------------------------
 *  One switch per relay on the board.  This replaces hand building an httpGetSwitch device and
 *  pasting URLs into it: the parent app knows the board's address, so it builds the URLs and keeps
 *  them correct if the address ever changes.
 *
 *  The URLs are shown on the device page as read only state, so you can see exactly what is being
 *  sent without being able to knock it out of sync by editing it.
 *
 *  This driver holds no HTTP logic of its own.  Commands are handed to the parent app, which sends
 *  them and then confirms the result by reading the board's actual relay status back.
 */
metadata {
    definition (name: "RIB Relay Switch", namespace: "community", author: "ValkTech") {
        capability "Switch"
        capability "Actuator"
        capability "Refresh"

        // Read only, for visibility.  Attributes rather than preferences precisely because they
        // cannot be edited -- the parent owns these and rewrites them whenever anything changes.
        attribute "onUrl", "string"
        attribute "offUrl", "string"
        attribute "autoOff", "string"
    }

    preferences {
        input name: "useAutoOff", type: "bool",
            title: "Use the board's built-in auto-off timer",
            description: "Recommended for anything that must never be left running, such as sprinklers.",
            defaultValue: false

        input name: "autoOffMinutes", type: "number",
            title: "Turn off automatically after (minutes)",
            description: "1 to 1092 minutes (about 18 hours).",
            defaultValue: 30, range: "1..1092"

        input name: "txtEnable", type: "bool", title: "Enable descriptive text logging", defaultValue: true
    }
}

def installed() {
    refresh()
}

def updated() {
    // The timer setting is part of the ON url, so the displayed URLs have to be rebuilt whenever
    // the user changes it.
    parent?.refreshRelayUrls()
    if (txtEnable) log.info "${device}: auto-off ${autoOffSeconds() ? "after ${settings.autoOffMinutes} minute(s)" : "disabled"}"
    refresh()
}

def on() {
    parent?.relayCommand(device, true)
}

def off() {
    parent?.relayCommand(device, false)
}

def refresh() {
    parent?.refreshRelays()
}

/**
 * How long the board should wait before switching this relay off by itself, in seconds.
 * 0 means "stay on until told otherwise".  Read by the parent when it builds the ON url.
 */
Integer autoOffSeconds() {
    if (!settings.useAutoOff) return 0
    Integer minutes = (settings.autoOffMinutes ?: 30) as Integer
    if (minutes < 1) return 0
    // The board stores this in a 16 bit field, so 65535 seconds is the ceiling.
    return Math.min(minutes * 60, 65535)
}

/** Called by the parent after it has read the board's real relay status. */
def setRelayState(String value) {
    if (device.currentValue("switch") != value) {
        sendEvent(name: "switch", value: value, descriptionText: "${device} is ${value}")
        if (txtEnable) log.info "${device} is ${value}"
    }
}

/** Called by the parent whenever the URLs change (board address, password, or timer edited). */
def setUrls(String onUrl, String offUrl, String autoOffText) {
    sendEvent(name: "onUrl", value: onUrl)
    sendEvent(name: "offUrl", value: offUrl)
    sendEvent(name: "autoOff", value: autoOffText)
}
