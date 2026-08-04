/**
 *  RIB App (Event) for Hubitat
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
 *  =============================================================================================
 *  EVENT DRIVEN VARIANT
 *  =============================================================================================
 *
 *  The original version of this app asked the board "what are your inputs doing?" once every
 *  second.  That is ~86,000 HTTP round trips a day and it was the reason the hub ran hot.
 *
 *  This version turns the relationship around.  The Dingtian board has a built in feature called
 *  "Input Link URL" (added in firmware V3.1.2776, Aug 2023) which makes the board itself fire an
 *  HTTP request at a server of your choosing the moment an input changes state, with a separate
 *  URL for the "went active" and "went inactive" edges.  We point those URLs at this app, so the
 *  board tells us about changes instead of us continually asking.
 *
 *  Two pieces make that work:
 *
 *    1. mappings{} below exposes a small endpoint on the hub.  Hubitat calls this "OAuth", but no
 *       OAuth handshake happens -- enabling it simply makes Hubitat mint a fixed access token and
 *       serve the app at http://<hubIp>/apps/api/<appId>/...  The board just does a plain GET.
 *
 *    2. provisionBoard() writes those URLs into the board's own configuration over its HTTP config
 *       API, so the user never has to hand type 16 URLs into the board's web page.
 *
 *  The old poll survives, but only as a slow reconcile sweep (5 minutes by default).  It exists so
 *  that a single dropped push -- board reboot, hub reboot, lost packet -- can never leave a door
 *  reporting the wrong state.  For something standing in for a security panel, "eventually correct"
 *  matters more than "zero background traffic".
 *
 *  What this app deliberately does NOT touch: relays.  It never sends a relay command, and when it
 *  writes configuration it round trips every non-input section of the board's config byte for byte.
 *  In particular the board side timed auto-off (relay_cgi.cgi?type=2&...&time=N), which is what
 *  guarantees sprinklers shut themselves off even if the hub dies, is left completely alone.
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

definition(
    name: "RIB App (Event)",
    namespace: "community",
    author: "ValkTech",
    description: "Relay Input Board for use with Hubitat (event driven)",
    category: "Safety & Security",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page name: "mainPage", title: "", install: true, uninstall: true
}

// Minimum firmware that has the "Input Link URL" feature this app depends on, and the version
// currently worth being on.  Kept here so the version check and the on screen text can never drift
// apart.  See the vendor's ChangeLog.txt:
//   V3.1.2776  added "input link URL", support input change activate URL call
//   V3.1.5182  fixed "input link URL" crash when "HTTPS" is checked
//   V3.1.6312  fixed webpage "Input Link URL" method(POST/PUT) "body" not save
//   V3.1.6611  fixed the crash introduced by V3.1.6553
@Field static final List MIN_FIRMWARE = [3, 1, 2776]
@Field static final String MIN_FIRMWARE_TEXT = "V3.1.2776"
@Field static final String REC_FIRMWARE_TEXT = "V3.1.6611"
@Field static final String FIRMWARE_PAGE = "https://www.dingtian-tech.com/en_us/support.html?tab=download"
@Field static final String FIRMWARE_ZIP = "http://www.dingtian-tech.com/sdk/relay_upgrade_tool.zip"

/**
 * The endpoint the board pushes to.  ":idx" is the 1 based input number as printed on the board's
 * screw terminals, ":val" is 1 for the active edge and 0 for the inactive edge.
 *
 * Full URL as seen by the board, e.g.:
 *     http://192.168.1.5/apps/api/247/i/3/1?access_token=<uuid>
 *
 * Kept deliberately short.  The whole URL has to fit in the board's on_path/off_path config field
 * and that field has no documented maximum length, so every character we save is margin.
 */
mappings {
    path("/i/:idx/:val") { action: [GET: "handleInputEvent"] }
}

def mainPage() {
    updateAppLabel()
    dynamicPage(name: "mainPage") {
        section("Relay Input Board Configuration") {
            // Anyone running more than one board needs to tell the instances apart in the Apps list,
            // where they would otherwise all read "RIB App (Event)".
            input name: "boardName", type: "text", title: "Name for this board", submitOnChange: true,
                description: "Shown in the Apps list, e.g. \"Sprinklers\" gives you RIB App (Sprinklers). " +
                             "Also used to name any new devices this app creates.",
                required: false
            // This must be the board your INPUTS are wired to. If you run more than one Dingtian
            // board, check the model shown under "Board Firmware" below to confirm you have the
            // right one before clicking Done.
            input name: "ribAddress", type: "text", title: "Relay Interface Board Address", submitOnChange: true, required: true, defaultValue: "192.168.50.30" // local name resolution does not work on hubitat hub "homerelays.local"
            // This is the safety net sweep, not the primary update path -- inputs normally update
            // the instant they change.  Offered as an enum rather than a free number because each
            // choice maps to one of Hubitat's fixed slot schedulers, which are cheaper and cannot
            // stack up the way a custom cron expression can.
            input name: "reconcileMinutes", type: "enum", title: "How often to re-sync all inputs and relays as a safety net",
                options: ["1": "Every minute", "5": "Every 5 minutes", "10": "Every 10 minutes", "15": "Every 15 minutes", "30": "Every 30 minutes"],
                defaultValue: "5", required: true
            input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: false
        }

        section("Relay Outputs") {
            if (state.relayCount) {
                paragraph "<b>${state.relayCount} ${devicePrefix()} Relay switches are available in your devices list.</b>"
            }

            input name: "relayPassword", type: "number", title: "Relay password (0 if you have not set one)",
                defaultValue: 0, required: false

            paragraph "<small>Each relay device has its own optional auto-off timer, set on the device page. " +
                      "It is worth using for anything that must never be left running, such as sprinklers " +
                      "&mdash; the countdown runs on the board rather than on the hub. The device page explains " +
                      "how it behaves.</small>"
        }
        // Setup can fail in a few quiet ways (OAuth off, firmware too old, board unreachable).
        // Surface them here rather than leaving the user to find them in the logs.
        section("Status") {
            if (state.boardOnline == false) {
                paragraph "<b style='color:red'>The board at ${settings.ribAddress} is not responding.</b> " +
                          "Inputs and relays are showing their last known state, which may be out of date. " +
                          (state.lastContact ? "Last successful contact: ${state.lastContact}. " : "") +
                          "The app keeps retrying and will pick up again by itself once the board is back."
            } else if (state.setupPending) {
                paragraph "<b style='color:red'>Setup has not finished.</b> The board has not answered yet, so no " +
                          "devices have been created. The app is still retrying."
            }
            // Inputs and relays are set up independently, so one side failing is worth stating
            // plainly rather than leaving the user to notice devices that never appeared.
            if (state.inputsUnavailable) {
                paragraph "<b>This board reported no inputs</b>, so no contact sensors were created. Its relays " +
                          "are set up and working normally. If you expected inputs here, check the board's " +
                          "\"Input Status\" page &mdash; a board with no input terminals will never report any."
            }
            if (state.relaysUnavailable && state.inputCount) {
                paragraph "<b>This board reported no relays</b>, so no switches were created. Its inputs are set " +
                          "up and working normally."
            }
            if (state.oauthError) {
                paragraph "<b style='color:red'>OAuth is not enabled for this app.</b> Go to Developer Tools &rarr; Apps Code &rarr; " +
                          "RIB App (Event), click OAuth, Enable OAuth in App, Update. Then re-open this app and click Done."
            }
            if (state.provisionError) {
                paragraph "<b style='color:red'>${state.provisionError}</b>"
            }
            if (state.lastProvisioned) {
                paragraph "Board push configured: ${state.lastProvisioned}"
            }
            // The single most useful number on this page: if it is zero, the board has never once
            // called the hub, and nothing else here matters yet.  Except on a board that cannot push
            // at all, where saying so would just be restating the firmware message above.
            if (state.pushCount) {
                paragraph "Pushes received from the board: <b>${state.pushCount}</b> &mdash; last was ${state.lastPush}"
            } else if (state.configApiAvailable != false) {
                paragraph "<b>No pushes have been received from the board yet.</b> Until this changes, inputs are " +
                          "only being updated by the reconcile sweep."
            }
            // Show the endpoint so it can be tested by hand, or pasted into the board's own
            // "Input Link URL" page if automatic provisioning will not work on your firmware.
            // Pointless on a board with no push support at all, so leave it out there.
            if (state.accessToken && state.configApiAvailable != false) {
                Map hub = hubEndpointParts()
                if (hub) {
                    paragraph "<small><b>Push endpoint.</b> The board is told to call these. Input 1 as an example " +
                              "&mdash; the number before the last slash is the input, and the last digit is 1 for " +
                              "open and 0 for closed:<br>" +
                              "Server <code>${hub.host}</code> port <code>${hub.port}</code><br>" +
                              "ON path <code>${pushPath(hub.basePath, 1, 1)}</code><br>" +
                              "OFF path <code>${pushPath(hub.basePath, 1, 0)}</code><br>" +
                              "You can test it in a browser: <code>http://${hub.host}:${hub.port}${pushPath(hub.basePath, 1, 1)}</code> " +
                              "should flip RIB Input 1 to open.</small>"
                }
            }
        }

        // Firmware is the single most common reason event driven mode won't work, so give the user
        // everything needed to judge and fix it without leaving this page: what they have, what
        // they need, and where to get it.
        section("Board Firmware") {
            if (state.boardVersion) {
                paragraph "This board reports <b>${state.boardVersion}</b>" +
                          (state.boardModel ? " (${state.boardModel})" : "") +
                          " with ${state.inputCount ?: 0} inputs."
                if (state.firmwareTooOld) {
                    paragraph "<b style='color:red'>This is older than ${MIN_FIRMWARE_TEXT}, so it has no " +
                              "\"Input Link URL\" support and cannot push events.</b> Until it is updated, inputs " +
                              "will only refresh on the reconcile sweep above."
                }
            } else if (state.configApiAvailable == false) {
                // The board is answering, it just predates the API that reports its version. Say
                // that, rather than "has not answered yet", which reads like a connection problem.
                paragraph "This board is too old to report its firmware version to the app &mdash; the API that " +
                          "does so arrived in ${MIN_FIRMWARE_TEXT}, the same release that added event push. " +
                          "Its version is on the board's own web page under Setting. " +
                          "<b>Everything works except push:</b> its inputs and relays are created and kept up to " +
                          "date by the reconcile sweep, exactly like the original polling version of this app."
            } else {
                paragraph "Board firmware version unknown &mdash; the board has not answered yet."
            }

            paragraph "Event driven mode needs firmware <b>${MIN_FIRMWARE_TEXT}</b> or later, and that is the only " +
                      "hard requirement. Later releases did fix bugs in this feature, but they cover HTTPS and " +
                      "POST bodies, neither of which this app uses &mdash; so if you are above the minimum you " +
                      "probably do not need to upgrade for this at all. If you upgrade for other reasons, " +
                      "<b>${REC_FIRMWARE_TEXT}</b> is the one to land on, since the release just before it is " +
                      "known to crash."

            paragraph "Downloads: <a href='${FIRMWARE_PAGE}' target='_blank'>Dingtian support &rarr; Download</a> " +
                      "(or straight to the <a href='${FIRMWARE_ZIP}' target='_blank'>relay upgrade tool</a>). " +
                      "The board's own upgrade page is at " +
                      "<a href='http://${settings.ribAddress}' target='_blank'>http://${settings.ribAddress}</a> " +
                      "under the Upgrade menu."

            paragraph "<small>Upgrading from older than v3.1.4897 is a two step process: flash the " +
                      "v3.1.4897 <code>.dtf</code> first, then the ${REC_FIRMWARE_TEXT} <code>.dtf2</code>. " +
                      "Use a wired connection, keep power stable, and turn off your PC firewall while the " +
                      "upgrade tool is running.</small>"
        }
    }
}


/**
 * Is this firmware new enough to push events?
 *
 * Versions look like "V3.1.3684A" -- major, minor, build, with an occasional trailing letter.  All
 * three parts matter: the V2.17.x line has higher minor numbers than V3.1.x but is older overall,
 * so this compares them as an ordered triple rather than just looking at the build number.
 *
 * Unparseable or missing version strings return true, i.e. give the board the benefit of the doubt
 * and let provisioning fail with a real error, rather than refusing to try over a formatting quirk.
 */
private boolean firmwareSupportsPush(String version) {
    List v = parseFirmware(version)
    if (!v) return true
    for (int i = 0; i < 3; i++) {
        if (v[i] != MIN_FIRMWARE[i]) return v[i] > MIN_FIRMWARE[i]
    }
    return true     // exactly the minimum version
}

/** "V3.1.3684A" -> [3, 1, 3684], or null if it doesn't look like a version at all. */
private List parseFirmware(String version) {
    if (!version) return null
    java.util.regex.Matcher m = (version =~ /(\d+)\.(\d+)\.(\d+)/)
    if (!m.find()) return null
    return [m.group(1) as int, m.group(2) as int, m.group(3) as int]
}


// =================================================================================================
// Lifecycle
// =================================================================================================

def installed() {
    log.debug "installed(): Installing RIB Parent SmartApp"
    initialize()
}

def updated() {
    log.debug "updated(): Updating RIB SmartApp"
    initialize()
}

def uninstalled() {
    unschedule()

    // Leave the board tidy.  If we just vanish, it carries on firing HTTP requests at an endpoint
    // that no longer exists, forever.  Best effort only -- if the board is unreachable right now
    // there is nothing useful we can do about it, so never let this block the uninstall.
    try {
        String raw = fetchBoardConfigRawAt(settings.ribAddress)
        List bounds = inputLinkUrlBounds(raw)
        String block = bounds ? replaceJsonValue(raw.substring(bounds[0], bounds[1]), "en", "0") : null
        if (block && writeBoardConfig(raw.substring(0, bounds[0]) + block + raw.substring(bounds[1]))) {
            log.debug "uninstalled(): disabled Input Link URL on the board"
        }
    } catch (Exception e) {
        log.warn "uninstalled(): could not disable Input Link URL: ${e.message}"
    }

    log.debug "uninstalled(): Uninstalling RIB SmartApp"
}

/**
 * Runs on install and on every Done click.  Re-running this is how the setup self heals: it
 * rewrites the push URLs, which is what picks up a changed hub IP address or a board that has been
 * factory reset.  Everything here is written to be safe to run repeatedly.
 */
def initialize() {

    updateAppLabel()

    unschedule()

    // Leftovers from earlier versions of this app: the old polling mutex, and state from a network
    // discovery feature that never worked reliably on Hubitat and has been removed.
    state.remove('working')
    ['discovering', 'discovered', 'discoveryProbed', 'discoveryStartedAt'].each { state.remove(it) }
    state.oauthError = null
    state.provisionError = null

    // Needed before we can build the push URLs, since the token is part of the path.  This throws
    // if OAuth has not been enabled on the app code, which is a one time manual step the user has
    // to do in the IDE and cannot be automated -- so catch it and say so plainly.
    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (Exception e) {
            state.oauthError = true
            log.error "initialize(): could not create an access token. Enable OAuth for this app in Apps Code. (${e.message})"
        }
    }

    // Schedule the sweep before anything that can fail. Everything below depends on the board being
    // reachable right now, and if it isn't, the app must still be left with a heartbeat -- otherwise
    // the unschedule() above would leave it permanently dead until someone clicked Done again.
    scheduleReconcile()

    if (setupFromBoard()) {
        poll()
    } else {
        state.setupPending = true
        log.warn "initialize(): the board at ${settings.ribAddress} did not respond. The app will keep trying, " +
                 "and will finish setting itself up as soon as the board is reachable."
        runIn(60, "retrySetup")
    }
}

/**
 * Everything that needs the board to be reachable: read what it is, create the devices for it, and
 * write the push configuration.  Returns false if the board could not be reached at all, so the
 * caller can arrange to try again rather than leaving the app half configured.
 */
private boolean setupFromBoard() {

    // Fetched once as text and once parsed: the parsed copy is for reading counts and versions, the
    // raw text is what actually gets edited and written back.
    String raw = fetchBoardConfigRawAt(settings.ribAddress)
    Map cfg = null
    if (raw) {
        try {
            cfg = (Map) new JsonSlurper().parseText(raw)
        } catch (Exception e) {
            log.warn "initialize(): could not parse the board's config: ${e.message}"
        }
    }

    int inputCount = 0
    int relayCount = 0
    state.configApiAvailable = (cfg != null)
    if (cfg) {
        state.boardVersion = cfg?.network?.sw_ver
        state.boardModel = cfg?.network?.model
        state.firmwareTooOld = !firmwareSupportsPush(state.boardVersion)
        inputCount = (cfg?.input_link_relay?.input_cnt ?: cfg?.input_link_url?.cnt ?: 0) as int
        relayCount = (cfg?.input_link_relay?.relay_cnt ?: cfg?.relay_task?.relay_cnt ?: 0) as int
        // Boards ship with every input wired to the matching relay (I1->R1 ...). That is invisible
        // unless you go looking at the board's web page, and it fights with using the relays for
        // anything else -- so surface it instead of letting people hunt for a mystery relay click.
        state.inputLinkRelayActive = (cfg?.input_link_relay?.input_link_relay?.toString() == "1")

        if (state.firmwareTooOld) {
            log.warn "initialize(): board firmware ${state.boardVersion} is older than ${MIN_FIRMWARE_TEXT}; " +
                     "event push is unavailable. See the app's settings page for upgrade links."
        }
    }

    // Older firmware has no /api/v2/config.cgi at all -- boards from before 2024 answer neither the
    // config API nor report their channel counts through it.  Fall back to asking the input and
    // relay endpoints directly, which have existed for as long as these boards have.  Push will not
    // be available on such a board, but the inputs, the relays and the sweep all still work.
    if (inputCount < 1) inputCount = countFromCgi("/input.cgi")
    if (relayCount < 1) relayCount = countFromCgi("/relay_cgi_load.cgi")

    state.relayCount = relayCount

    // Only give up if the board told us nothing at all. Inputs and relays are independent: a board
    // used purely for outputs, or one whose inputs cannot be read, should still get working relay
    // switches rather than nothing.
    if (inputCount < 1 && relayCount < 1) return false

    state.inputCount = inputCount

    state.inputsUnavailable = (inputCount < 1)
    state.relaysUnavailable = (relayCount < 1)

    if (inputCount > 0) {
        createChildDevices(inputCount)
    } else {
        log.warn "The board at ${settings.ribAddress} did not report any inputs, so no contact sensors were " +
                 "created. Its ${relayCount} relay(s) are set up and working normally."
    }

    if (relayCount > 0) {
        createRelayDevices(relayCount)
    } else {
        log.warn "The board at ${settings.ribAddress} did not report any relays, so no switches were created. " +
                 "Its ${inputCount} input(s) are set up and working normally."
    }

    // Push is only about inputs, so there is nothing to provision on a board with none.
    if (inputCount > 0 && raw && cfg && state.accessToken) {
        provisionBoard(raw, inputCount)
    } else if (inputCount > 0 && !cfg) {
        // The board answered input.cgi but not the config API, so it predates /api/v2/config.cgi.
        // Degrade gracefully: inputs still track, just on the sweep rather than on push.
        state.provisionError = "This board is too old for event push &mdash; that needs firmware ${MIN_FIRMWARE_TEXT} " +
                               "or later, and this one predates the configuration API entirely. Its inputs and relays " +
                               "still work, updated by the reconcile sweep instead. Nothing is broken; this board just " +
                               "behaves like the original polling version of the app."
        log.warn "initialize(): board at ${settings.ribAddress} predates the config API, so event push is unavailable"
    }

    state.setupPending = false
    return true
}

/**
 * Retry the parts of setup that need the board, after it was unreachable.
 *
 * Backs off to the reconcile interval once the first quick retry fails, so a board that stays down
 * for a week does not fill the log or hammer the network -- but it never gives up, because the
 * board coming back should not require anyone to notice and click Done.
 */
def retrySetup() {
    if (setupFromBoard()) {
        log.info "The board at ${settings.ribAddress} is responding again; setup is complete."
        poll()
    } else {
        runIn(300, "retrySetup")
    }
}

/**
 * One contact child per physical input.  The device network ID format and the "RIB Input N" naming
 * are inherited from the polling version on purpose, so an existing install keeps recognising its
 * devices and the numbering still lines up with the labels silkscreened on the board (I3 -> RIB
 * Input 3).  Devices are only ever added here, never removed -- deleting an unused one and clicking
 * Done again is how you get it back.
 */
private createChildDevices(int inputCount) {
    for (int i = 1; i <= inputCount; i++) {
        String dni = contactDni(i)
        logDebug "initialize(): adding driver = ${dni}"
        if (!getChildDevice(dni)) {
            addChildDevice("community", "RIB Contact Sensor", dni, null, [name: "${devicePrefix()} Input ${i}"])
        }
    }
}

/** Single source of truth for the child DNI format, used by both the push path and the sweep. */
private String contactDni(idx) {
    return "RIBContact-${idx}_${app.id}"
}

/**
 * Put the board's name in the Apps list, so several instances can be told apart at a glance.
 *
 * Safe to call on every page render: it only writes when the label would actually change.
 */
private updateAppLabel() {
    String name = settings.boardName?.trim()
    String desired = name ? "RIB App (${name})" : "RIB App (Event)"
    if (app.label != desired) app.updateLabel(desired)
}

/**
 * What to call newly created devices.  Falls back to "RIB" so that an install without a board name
 * keeps producing the same "RIB Input 1" names it always has.
 *
 * Only ever applied to devices at the moment they are created -- renaming existing ones would undo
 * the names people have given them, which is the whole point of being able to rename a device.
 */
private String devicePrefix() {
    return settings.boardName?.trim() ?: "RIB"
}

/**
 * Hubitat's runEveryNMinutes helpers land on fixed slots and quietly replace any previous schedule
 * for the same handler, so unlike the old schedule(..., [overwrite: false]) cron they cannot pile
 * up if initialize() runs more than once.
 */
private scheduleReconcile() {
    String every = (settings.reconcileMinutes ?: "5") as String
    switch (every) {
        case "1":  runEvery1Minute("poll");   break
        case "10": runEvery10Minutes("poll"); break
        case "15": runEvery15Minutes("poll"); break
        case "30": runEvery30Minutes("poll"); break
        default:   runEvery5Minutes("poll");  break
    }
    logDebug "Reconcile sweep scheduled every ${every} minute(s)"
}


// =================================================================================================
// Push endpoint -- the board calls this on every input edge.  This is the primary update path.
// =================================================================================================

/**
 * Handles one input transition pushed by the board.
 *
 * Note this does not decide open vs closed itself: it calls isOpen()/isClosed() on the child, and
 * the driver applies the per device Normally Open / Normally Closed preference and suppresses
 * repeats.  That keeps the polarity logic in exactly one place, shared with the sweep, so a push
 * and a sweep can never disagree about what "1" means.
 */
def handleInputEvent() {
    String idx = params?.idx
    String val = params?.val

    def dev = getChildDevice(contactDni(idx))

    // Logged at info, not debug, and always. A push arriving is the thing this whole app exists to
    // do, it only happens when something physically changed, and "did the board actually call us?"
    // is the first question worth answering when anything looks wrong.
    log.info "PUSH from board: input ${idx} = ${val}${dev ? " (${dev})" : ""}"

    state.pushCount = (state.pushCount ?: 0) + 1
    state.lastPush = "input ${idx} = ${val} at ${new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)}"

    if (dev) {
        if (val == "1") dev.isOpen() else dev.isClosed()
    } else {
        // Usually means the user deleted that input's device but the board is still configured for it.
        log.warn "handleInputEvent(): no child device for input ${idx}"
    }

    // Answer immediately and cheaply -- the board is holding the socket open waiting on us, and
    // anything slow here shows up as latency on the next input change.
    render contentType: "text/plain", data: "OK", status: 200
}


// =================================================================================================
// Board configuration API (/api/v2/config.cgi, /api/v2/config_set.cgi)
// =================================================================================================

/**
 * Fetch the board's entire configuration as a Map.  Returns null on any failure, including "this
 * firmware is too old to have the config API", which the caller treats as a soft failure.
 */
private Map fetchBoardConfig() {
    return fetchBoardConfigAt(settings.ribAddress)
}

/**
 * Same, but against an arbitrary address and with a caller supplied timeout.
 */
private Map fetchBoardConfigAt(String address, int timeoutSeconds = 15) {
    String raw = fetchBoardConfigRawAt(address, timeoutSeconds)
    if (!raw) return null
    try {
        return (Map) new JsonSlurper().parseText(raw)
    } catch (Exception e) {
        log.warn "fetchBoardConfig(): could not parse the board's config: ${e.message}"
        return null
    }
}

/**
 * The board's configuration as the exact text it sent.
 *
 * Kept as raw text on purpose. The SDK is explicit that the node order must not change, and the
 * board has been shown to accept a byte-for-byte copy of its own config -- so the safest thing we
 * can do when writing is to edit that text in place rather than rebuild the whole document from a
 * parsed map and hope every key, order and number format survives the round trip.
 */
private String fetchBoardConfigRawAt(String address, int timeoutSeconds = 15) {
    String raw = null
    try {
        // textParser keeps Hubitat from parsing the JSON for us. We specifically want the bytes the
        // board sent, because those are what it is guaranteed to accept back.
        httpGet([uri: "http://${address}", path: "/api/v2/config.cgi",
                 contentType: "text/plain", textParser: true, timeout: timeoutSeconds]) { resp ->
            if (resp.success) {
                def d = resp.data
                if (d instanceof String) {
                    raw = d
                } else if (d instanceof Map || d instanceof List) {
                    // Hubitat parsed it anyway. Re-serialising loses the byte-for-byte guarantee,
                    // so say so -- if a write ever fails, this is the first thing to suspect.
                    log.warn "fetchBoardConfig(): the platform parsed the config instead of returning text; " +
                             "falling back to re-serialising it"
                    raw = JsonOutput.toJson(d)
                } else {
                    raw = d?.getText()
                }
            }
        }
    } catch (Exception e) {
        log.warn "fetchBoardConfig(): ${e.message}"
    }
    return raw
}

/**
 * Locate the "input_link_url":{...} object inside the raw config text.
 *
 * Returns [startOfKey, indexAfterClosingBrace], or null if it isn't there. That block holds only
 * scalars and flat arrays -- no nested objects -- so the first closing brace really is its end and
 * no brace counting is needed.
 */
private List inputLinkUrlBounds(String raw) {
    if (!raw) return null
    int key = raw.indexOf('"input_link_url"')
    if (key < 0) return null
    int colon = raw.indexOf(':', key)
    if (colon < 0) return null
    int end = jsonValueEnd(raw, colon + 1)
    if (end < 0) return null
    int open = raw.indexOf('{', colon)
    if (open < 0 || open > end) return null
    return [open, end]
}

/**
 * Index just past the JSON value that starts at or after i.
 *
 * Counts brackets properly and skips over strings, so a value containing nested objects, nested
 * arrays, or braces inside a quoted string is measured correctly. The earlier version assumed the
 * first closing brace ended the object, which happens to hold for every firmware seen so far but is
 * exactly the kind of assumption a firmware update is entitled to break -- and a stored URL
 * containing a brace would break it today.
 */
private int jsonValueEnd(String s, int from) {
    int n = s.length()
    int i = from
    while (i < n && Character.isWhitespace(s.charAt(i))) i++
    if (i >= n) return -1

    char c = s.charAt(i)
    if (c == '"') return jsonStringEnd(s, i)

    if (c == '{' || c == '[') {
        char open = c
        char close = (c == '{') ? ('}' as char) : (']' as char)
        int depth = 0
        while (i < n) {
            char d = s.charAt(i)
            if (d == '"') {
                int se = jsonStringEnd(s, i)
                if (se < 0) return -1
                i = se
                continue
            }
            if (d == open) depth++
            else if (d == close && --depth == 0) return i + 1
            i++
        }
        return -1
    }

    // number, true, false or null: runs until the next separator
    while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}' && s.charAt(i) != ']') i++
    return i
}

/**
 * The keys of a JSON object, in the order the board wrote them.
 *
 * Purely diagnostic. The app never needs to know this order -- it edits values where they already
 * are -- but the order does differ between firmware and is documented nowhere, so having it in the
 * log turns "why did the write fail on this board" into a single glance.
 */
private List jsonKeyOrder(String obj) {
    List keys = []
    int i = obj.indexOf('{')
    if (i < 0) return keys
    i++
    int depth = 1
    boolean expectKey = true
    int n = obj.length()
    while (i < n && depth > 0) {
        char c = obj.charAt(i)
        if (c == '"') {
            int end = jsonStringEnd(obj, i)
            if (end < 0) break
            if (depth == 1 && expectKey) {
                keys << obj.substring(i + 1, end - 1)
                expectKey = false
                int v = obj.indexOf(':', end)
                if (v < 0) break
                int after = jsonValueEnd(obj, v + 1)
                if (after < 0) break
                i = after
                continue
            }
            i = end
            continue
        }
        if (c == '{' || c == '[') depth++
        else if (c == '}' || c == ']') depth--
        else if (c == ',' && depth == 1) expectKey = true
        i++
    }
    return keys
}

/** Index just past the JSON string whose opening quote is at i. */
private int jsonStringEnd(String s, int i) {
    int n = s.length()
    i++
    while (i < n) {
        char c = s.charAt(i)
        if (c == '\\') { i += 2; continue }
        if (c == '"') return i + 1
        i++
    }
    return -1
}

/**
 * Replace the value of one key inside a JSON object's text, leaving the rest of it byte for byte.
 *
 * This is what lets the app set the handful of fields it cares about without having to know, or
 * preserve, everything else the firmware keeps in that block. Returns null if the key isn't there.
 */
private String replaceJsonValue(String obj, String key, String newValue) {
    int k = obj.indexOf('"' + key + '"')
    if (k < 0) return null
    int colon = obj.indexOf(':', k + key.length() + 2)
    if (colon < 0) return null
    int start = colon + 1
    int end = jsonValueEnd(obj, start)
    if (end < 0) return null
    return obj.substring(0, start) + newValue + obj.substring(end)
}

/**
 * Set the push settings inside the board's existing "input_link_url" object, one field at a time.
 *
 * Deliberately edits rather than rebuilds, and that is not a stylistic preference -- rebuilding is
 * what broke this. The SDK says plainly that "the node order can't change", and the order is NOT
 * the same across firmware:
 *
 *   V3.1.4685: ... tls, auth, server, port, user, pass, on_method, on_path, on_body, off_method ...
 *   V3.1.6611: ... tls, auth, port, on_method, off_method, server, user, pass, on_path, off_path ...
 *
 * A block written out in any fixed order therefore works on one firmware and is rejected by
 * another. Editing values in place keeps whatever order the board itself used, so this works on
 * every version without knowing which one it is talking to.
 *
 * The same property handles fields appearing and disappearing over time: anything the app does not
 * recognise is left untouched, and a field it expects but does not find is simply skipped (HTTP
 * auth, for example, only arrived in V3.1.3044).
 *
 * Returns null only if the fields the feature is actually made of are absent, which means the
 * firmware is too old for it.
 */
private String applyPushSettings(String block, int n, String hubHost, int hubPort, String basePath) {
    Closure quote = { v -> '"' + v.toString().replace('\\', '\\\\').replace('"', '\\"') + '"' }
    Closure array = { List values -> '[' + values.join(',') + ']' }
    List inputs = (1..n)

    Map wanted = [
        "en"          : "1",
        "cnt"         : "${n}".toString(),
        // 0 = SelfLock: follow the input level rather than pulsing on one edge only.
        "type"        : array(inputs.collect { 0 }),
        // 1 = HIGH, so the ON url means the same thing as a "1" from input.cgi.
        "active_level": array(inputs.collect { 1 }),
        "tls"         : array(inputs.collect { 0 }),
        "auth"        : array(inputs.collect { 0 }),
        "server"      : array(inputs.collect { quote(hubHost) }),
        "port"        : array(inputs.collect { hubPort }),
        "user"        : array(inputs.collect { '""' }),
        "pass"        : array(inputs.collect { '""' }),
        "on_method"   : array(inputs.collect { 0 }),          // 0 = GET
        "on_path"     : array(inputs.collect { i -> quote(pushPath(basePath, i, 1)) }),
        "on_body"     : array(inputs.collect { '""' }),
        "off_method"  : array(inputs.collect { 0 }),
        "off_path"    : array(inputs.collect { i -> quote(pushPath(basePath, i, 0)) }),
        "off_body"    : array(inputs.collect { '""' })
    ]

    String result = block
    List missing = []
    wanted.each { key, value ->
        String updated = replaceJsonValue(result, key, value)
        if (updated == null) missing << key
        else result = updated
    }

    if (missing) {
        // en/cnt/server/on_path are the feature itself; without them there is nothing to configure.
        log.warn "provisionBoard(): the board's input_link_url has no ${missing.join(', ')} field(s)"
        if (missing.intersect(["en", "cnt", "server", "on_path", "off_path"])) return null
    }

    return result
}

/**
 * POST a complete configuration document to the board.
 *
 * Takes text, not a map. The board is documented as needing compressed JSON with unchanged node
 * order, and it has been shown to accept a byte-for-byte copy of its own config -- so callers hand
 * over the board's own text with one block spliced out and replaced, and everything else survives
 * untouched. That is also what keeps relay tasks, the relay password and power failure recovery
 * exactly as they were.
 */
private boolean writeBoardConfig(String body) {
    boolean ok = false

    // The board is known to accept a byte-for-byte copy of its own config, so if a write fails the
    // question is what we did to the JSON on the way through. Logging the size makes a truncated or
    // half-serialised body obvious at a glance -- a healthy 8 channel config is a few KB.
    log.debug "writeBoardConfig(): sending ${body.length()} bytes, starts: ${body.take(80)}"

    // Two ways of putting a pre-serialised JSON string on the wire.
    //
    // The header form is tried first and is the one that should work: setting Content-Type as a
    // plain header leaves the String body alone. Using requestContentType instead sends the body
    // through HTTPBuilder's JSON encoder, which can encode an already-encoded string a second time
    // -- the board then receives a quoted JSON *string* rather than an object, refuses it, and
    // keeps its previous configuration while still answering HTTP 200.
    //
    // The second form is kept as a fallback because platform behaviour here has changed over
    // Hubitat releases, and a config write is worth one retry before giving up.
    List attempts = [
        [label: "raw body",  params: [headers: ["Content-Type": "application/json"]]],
        [label: "encoded body", params: [requestContentType: "application/json"]]
    ]

    for (attempt in attempts) {
        Map params = [uri: "http://${settings.ribAddress}", path: "/api/v2/config_set.cgi",
                      contentType: "text/plain", body: body, timeout: 20] + attempt.params
        try {
            httpPost(params) { resp ->
                // A 200 proves nothing here. The board answers 200 and reports the real outcome in
                // the body as {"status":N}, so a non-zero status is a failure no matter what the
                // HTTP layer says.
                String answer = (resp.data instanceof String) ? resp.data : "${resp.data}"
                state.lastWriteResponse = answer
                log.debug "writeBoardConfig(): ${attempt.label} -> board replied ${answer}"

                ok = resp.success
                java.util.regex.Matcher m = (answer =~ /"status"\s*:\s*(-?\d+)/)
                if (m.find() && m.group(1) != "0") {
                    ok = false
                    log.warn "writeBoardConfig(): board rejected the config with status ${m.group(1)} (${attempt.label})"
                }
            }
        } catch (Exception e) {
            ok = false
            log.warn "writeBoardConfig(): ${attempt.label} failed: ${e.message}"
        }

        if (ok) return true
    }

    log.error "writeBoardConfig(): could not write the configuration to ${settings.ribAddress}"
    return false
}

/**
 * Point every input's ON and OFF URL at this app's endpoint, then save it to the board.
 *
 * The board stores this feature as parallel arrays -- one entry per input in each of ~15 arrays --
 * which is why this reads as a block of collect{} calls rather than a loop over input objects.
 */
private provisionBoard(String raw, int inputCount) {

    List bounds = inputLinkUrlBounds(raw)
    if (!bounds) {
        state.provisionError = "This board's firmware has no Input Link URL support (needs V3.1.2776 or later). Reported version: ${state.boardVersion}"
        log.error "provisionBoard(): ${state.provisionError}"
        return
    }

    // Keep one pristine copy of what the board looked like before we ever touched it, so a bad
    // write is recoverable without resorting to the physical factory reset button.
    if (!state.configBackup) state.configBackup = raw

    Map hub = hubEndpointParts()
    if (!hub) {
        state.provisionError = "Could not parse the hub's local API URL (${getFullLocalApiServerUrl()})"
        log.error "provisionBoard(): ${state.provisionError}"
        return
    }

    int n = inputCount

    // Edit the board's own block and splice it back, leaving every other byte of its config exactly
    // as it sent it. Rebuilding the whole document from a parsed map is what broke this before.
    String block = applyPushSettings(raw.substring(bounds[0], bounds[1]), n, hub.host, hub.port, hub.basePath)
    if (block == null) {
        state.provisionError = "This board's Input Link URL settings are not in a shape this app understands, " +
                               "so push could not be set up. Firmware ${state.boardVersion}."
        log.error "provisionBoard(): ${state.provisionError}"
        return
    }

    String body = raw.substring(0, bounds[0]) + block + raw.substring(bounds[1])

    // Always stop the board switching its own relays from its own inputs.
    //
    // Boards ship with input 1 wired to relay 1, input 2 to relay 2 and so on. This app treats
    // inputs as contact sensors, so that linkage is simply incompatible with it -- every door event
    // would click a relay, and the relays could not be used for anything else. There is no sensible
    // configuration of this app where you would want it left on, so it is not offered as a choice.
    //
    // Scope: this governs only inputs driving relays. Relay control via relay_cgi.cgi, the type=2
    // timed auto-off used for sprinklers, relay_task and everything under relay_connect are
    // untouched. Both patterns match a key followed by a number, so neither can match the
    // "input_link_relay" section header, which is followed by an opening brace.
    String beforeUnlink = body
    body = body.replaceFirst(/"input_link_relay"\s*:\s*\d+/, '"input_link_relay":0')
               .replaceFirst(/"relay_feedback_momentary_input"\s*:\s*\d+/, '"relay_feedback_momentary_input":0')
    if (beforeUnlink != body && state.inputLinkRelayActive) {
        log.warn "provisionBoard(): turning off 'Input Control Relay' and 'Relay Feedback Momentary Input' " +
                 "so inputs no longer switch relays on the board itself"
    }

    // Record the board's own key order. It is not the same on every firmware -- 4685 and 6611
    // differ -- and it is not documented anywhere, so if a future release rearranges it again this
    // is the line that says so instead of leaving another round of guesswork.
    logDebug "provisionBoard(): board's input_link_url key order: ${jsonKeyOrder(raw.substring(bounds[0], bounds[1]))}"

    if (writeBoardConfig(body)) {
        state.lastProvisioned = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        logDebug "provisionBoard(): wrote Input Link URL config for ${n} inputs"
        runIn(5, "verifyProvisioning")     // give the board a moment to commit before reading back
    } else {
        state.provisionError = "Failed to write the push configuration to the board at ${settings.ribAddress}"
        log.error "provisionBoard(): ${state.provisionError}"
        // The two things needed to work out why, without having to ask for them afterwards.
        log.error "provisionBoard(): firmware ${state.boardVersion}, board's input_link_url key order was " +
                  "${jsonKeyOrder(raw.substring(bounds[0], bounds[1]))}"
    }
}

/** The path the board will request for a given input and edge.  ~70 characters; see verifyProvisioning(). */
private String pushPath(String basePath, int input, int value) {
    return "${basePath}/i/${input}/${value}?access_token=${state.accessToken}".toString()
}

/**
 * Split getFullLocalApiServerUrl() -- "http://<hubIp>[:port]/apps/api/<appId>" -- into the pieces
 * the board wants as separate config fields (host, port and path are three different columns in
 * its Input Link URL table).
 *
 * Uses an explicit find()/group() rather than Groovy's matcher indexing, because a Matcher carries
 * position state and mixing truthiness checks with [n][m] indexing is a well known way to get
 * subtly wrong results.
 */
private Map hubEndpointParts() {
    String base = getFullLocalApiServerUrl()
    java.util.regex.Matcher m = (base =~ /^https?:\/\/([^:\/]+)(?::(\d+))?(\/.*)$/)
    if (!m.find()) return null
    return [host: m.group(1), port: ((m.group(2) ?: "80") as int), basePath: m.group(3)]
}

/**
 * Read the config back and confirm the board really stored what we sent.
 *
 * Worth doing because the board answers HTTP 200 to a write it did not apply. Without this check
 * the app would report success while the board pushed nowhere, and the only visible symptom would
 * be inputs that quietly update every few minutes instead of instantly -- which looks like a
 * network problem rather than a failed write.
 */
def verifyProvisioning() {
    Map cfg = fetchBoardConfig()
    if (!cfg?.input_link_url) return

    Map hub = hubEndpointParts()
    if (!hub) return
    String expected = pushPath(hub.basePath, 1, 1)
    String actual = cfg.input_link_url.on_path instanceof List ? cfg.input_link_url.on_path[0] : null
    def enabled = cfg.input_link_url.en

    if (actual == expected && enabled?.toString() == "1") {
        state.provisionError = null
        logDebug "verifyProvisioning(): board stored the push URLs intact"
        return
    }

    // Distinguish the two very different failures, because they need opposite fixes and guessing
    // wrong sends you down the wrong path entirely.
    if (enabled?.toString() != "1" || (actual != null && !actual.startsWith("/apps/api"))) {
        // Nothing of ours took. "/get" and "/post" are Dingtian's factory placeholder values, so
        // seeing those back means the board kept its own config and ignored the write.
        state.provisionError = "The board did not accept the push configuration &mdash; it still has " +
                               "en=${enabled} and on_path=\"${actual}\". Those are the board's own default values, " +
                               "so this is the write being rejected, not a length problem. " +
                               "Board's reply to the write: ${state.lastWriteResponse ?: 'not recorded'}"
    } else {
        // Our path went in but came back shortened: a genuine field length limit.
        state.provisionError = "The board shortened the push URL: sent ${expected.length()} characters, " +
                               "stored ${actual?.length() ?: 0} (\"${actual}\"). The on_path field is too small " +
                               "for a hub URL with an access token."
    }
    log.error "verifyProvisioning(): ${state.provisionError}"
}


// =================================================================================================
// Relay outputs
//
// The board's relay API is a plain GET:
//   http://<board>/relay_cgi.cgi?type=<t>&relay=<n>&on=<0|1>&time=<seconds>&pwd=<pw>&
//     type 0 = straight on/off, type 2 = timed (switch on, then off after <time> seconds)
//     relay is ZERO based here, so relay 1 on the silkscreen is relay=0
//
// Building these here rather than in the driver is what keeps them correct: the address lives in
// one place, so changing it fixes every relay device at once instead of leaving the user to edit
// eight hand written URLs.
// =================================================================================================

private String relayDni(idx) {
    return "RIBRelay-${idx}_${app.id}"
}

private Integer relayNumberOf(aDevice) {
    java.util.regex.Matcher m = (aDevice.deviceNetworkId =~ /^RIBRelay-(\d+)_/)
    return m.find() ? (m.group(1) as Integer) : null
}

/** The exact URL for one relay command. Also what gets displayed on the device page. */
private String relayUrl(int relayNumber, boolean turnOn, int autoOffSeconds) {
    int type    = (turnOn && autoOffSeconds > 0) ? 2 : 0
    int seconds = (turnOn && autoOffSeconds > 0) ? autoOffSeconds : 0
    int pwd     = (settings.relayPassword ?: 0) as int
    return "http://${settings.ribAddress}/relay_cgi.cgi?type=${type}&relay=${relayNumber - 1}" +
           "&on=${turnOn ? 1 : 0}&time=${seconds}&pwd=${pwd}&"
}

private createRelayDevices(int relayCount) {
    for (int i = 1; i <= relayCount; i++) {
        String dni = relayDni(i)
        if (!getChildDevice(dni)) {
            logDebug "createRelayDevices(): adding ${dni}"
            addChildDevice("community", "RIB Relay Switch", dni, null, [name: "${devicePrefix()} Relay ${i}"])
        }
    }
    refreshRelayUrls()
}

/**
 * Push the current URLs onto every relay device.  Called after setup and whenever a device's own
 * timer setting changes, so what is displayed always matches what would actually be sent.
 */
def refreshRelayUrls() {
    // Every relay device, including disabled ones -- this only refreshes what is displayed, so
    // keeping a disabled device's URLs correct means they are already right if it is re-enabled.
    for (aDevice in getAllChildDevices()) {
        Integer n = relayNumberOf(aDevice)
        if (n == null) continue

        try {
            int autoOff = (aDevice.autoOffSeconds() ?: 0) as int
            String autoOffText = autoOff > 0 ? "off automatically after ${(autoOff / 60) as int} minute(s)" : "disabled"
            aDevice.setUrls(relayUrl(n, true, autoOff), relayUrl(n, false, 0), autoOffText)
        } catch (Exception e) {
            logDebug "refreshRelayUrls(): skipped ${aDevice} (${e.message})"
        }
    }
}

/**
 * Send one relay command and confirm it actually happened.
 *
 * The board's reply is the confirmation -- it echoes back the resulting state of that relay in
 * about 30ms -- so that is what updates the device, and nothing is scheduled to check up on it
 * afterwards. One command is one request.
 *
 * If a reply is lost or wrong, the periodic sweep corrects it within the reconcile interval, the
 * same guarantee the inputs get. Devices are only ever set from what the board reports, never from
 * what we asked for, so a relay that physically failed to switch shows the truth.
 */
def relayCommand(aDevice, boolean turnOn) {
    Integer n = relayNumberOf(aDevice)
    if (n == null) {
        log.warn "relayCommand(): ${aDevice} is not a relay device"
        return
    }

    int autoOff = 0
    try {
        autoOff = (aDevice.autoOffSeconds() ?: 0) as int
    } catch (Exception ignored) { }

    String url = relayUrl(n, turnOn, turnOn ? autoOff : 0)
    logDebug "relayCommand(): ${url}"

    asynchttpGet("relayCommandHandler", [uri: url, timeout: 10],
                 [relay: n, expect: (turnOn ? "on" : "off")])

    // A timed ON switches off on its own, so schedule a read a little after the deadline to catch
    // it -- otherwise the device would sit showing "on" until the next sweep.
    // Read the relay back a little AFTER the countdown expires, never before -- checking early
    // would just confirm it is still on and leave the device wrong until the next sweep. The extra
    // seconds cover the board's own timing and any clock drift over a long timer.
    if (turnOn && autoOff > 0) runIn(autoOff + 10, "refreshRelays")
}

/**
 * The board's reply to a relay command, which echoes what it actually did:
 *
 *     &status&type&relay&on&time&
 *     &0     &0   &6    &1 &0    &     -> status ok, relay index 6, now on, no timer
 *
 * Note this describes the one relay we commanded, not all of them -- relay_cgi_load.cgi is the call
 * that returns every relay. The relay index here is zero based, matching the request.
 */
def relayCommandHandler(resp, data) {
    Integer relayNum = data?.relay as Integer
    String expect = data?.expect

    // Anything that goes wrong below is logged and left alone -- the periodic sweep will correct
    // the device state on its next pass, so there is nothing useful to schedule here. The device is
    // left showing its previous state, which is honest: the command did not demonstrably happen.
    if (resp?.hasError()) {
        log.warn "relayCommand: relay ${relayNum} command did not reach the board (${resp.getErrorMessage()})"
        noteBoardContact(false, resp.getErrorMessage())
        return
    }
    if (resp?.status != 200) {
        log.warn "relayCommand: board returned HTTP ${resp?.status} for relay ${relayNum}"
        return
    }
    noteBoardContact(true, null)

    String body = resp.data as String
    logDebug "relayCommandHandler(): ${body}"

    List fields = body?.tokenize('&')
    if (fields == null || fields.size() < 4 || toInt(fields[0]) != 0 || toInt(fields[2]) != relayNum - 1) {
        log.warn "relayCommand: unexpected reply for relay ${relayNum}: '${body}'"
        return
    }

    String actual = (fields[3] == "1") ? "on" : "off"
    getChildDevice(relayDni(relayNum))?.setRelayState(actual)

    if (actual != expect) {
        log.error "Relay ${relayNum} did not switch ${expect} -- the board reports it is ${actual}. " +
                  "Check the relay password in the app and that relay ${relayNum} exists on this board."
    }
}

/** Read every relay's real state from the board. */
def refreshRelays() {
    asynchttpGet("relayStatusHandler",
                 [uri: "http://${settings.ribAddress}/relay_cgi_load.cgi", timeout: 10])
}

def relayStatusHandler(resp, data) {
    try {
        // Reachability is normally reported by the input sweep running alongside this one, and
        // reporting it here too would double every transition message. When there are no active
        // inputs that sweep does not run, so this becomes the only thing that can notice.
        boolean reportContact = !hasActiveInputs()

        if (resp.hasError()) {
            if (reportContact) noteBoardContact(false, resp.getErrorMessage())
            return
        }
        if (resp.status != 200) {
            if (reportContact) noteBoardContact(false, "HTTP ${resp.status}")
            return
        }
        if (reportContact) noteBoardContact(true, null)

        // Same shape as input.cgi -- e.g. "&0&4&1&0&1&0&" -- so the same offset safe parsing works.
        List keys = (resp.data as String).tokenize('&')
        int offset = channelCountOffset(keys)
        if (offset < 0) {
            log.warn "relay status: could not parse '${resp.data}'"
            return
        }
        int count = toInt(keys[offset])

        for (aDevice in activeChildren("RIBRelay-")) {
            Integer n = relayNumberOf(aDevice)
            if (n == null || n < 1 || n > count) continue

            int valueIndex = offset + n
            if (valueIndex >= keys.size()) continue

            aDevice.setRelayState((keys[valueIndex] == "1") ? "on" : "off")
        }
    } catch (Exception e) {
        log.warn "relay status failed: ${e.message}"
    }
}


// =================================================================================================
// Reconcile sweep -- safety net for a dropped push, NOT the primary path.
//
// This is the descendant of the old once per second poll.  At five minute intervals it costs about
// 288 requests a day instead of 86,400, while still guaranteeing that any state we missed gets
// corrected rather than persisting indefinitely.
// =================================================================================================

def poll() {
    // Only ask about what somebody is actually listening to. Reading inputs the user has deleted or
    // disabled is pure waste -- nothing consumes the answer -- and on a board with no inputs at all
    // it would log a failed request every sweep for a board working exactly as intended.
    if (hasActiveInputs()) {
        def requestParams = [ uri: "http://" + settings.ribAddress + "/input.cgi", timeout: 10 ]
        logDebug "poll(): $requestParams"
        asynchttpGet("pollHandler", requestParams)
    }

    // Relays are swept on the same schedule. Their state can change without anyone telling us -- an
    // auto-off timer expiring, someone using the board's own web page, a lost command reply -- so
    // they get the same "eventually correct" guarantee the inputs have. This is also the only thing
    // that corrects a relay now that commands are confirmed from their own reply rather than by a
    // follow up read.
    if (hasActiveRelays()) refreshRelays()
}

/**
 * The child devices of one kind that exist and are not disabled.
 *
 * Deleting the devices you don't use, or disabling one from its device page, is how you tell the app
 * to stop caring about it -- so both are honoured here rather than only counting what the board
 * reports it has. A device that is disabled cannot raise events anyway, so polling for it would be
 * work with nowhere to go.
 */
private List activeChildren(String dniPrefix) {
    return getAllChildDevices().findAll { aDevice ->
        if (!aDevice.deviceNetworkId?.startsWith(dniPrefix)) return false
        try {
            return !aDevice.isDisabled()
        } catch (Exception ignored) {
            return true      // older platforms may not expose isDisabled(); assume it is in use
        }
    }
}

private boolean hasActiveInputs() { return !activeChildren("RIBContact-").isEmpty() }
private boolean hasActiveRelays() { return !activeChildren("RIBRelay-").isEmpty() }

def pollHandler(resp, data) {
    // Deliberately no retry and no rescheduling on failure.  An earlier version of this app tried
    // to reschedule itself when a request failed, and the retries stacked up until the app stopped
    // responding and the hub's CPU spiked.  A failed sweep is not worth chasing: the next one is
    // only a few minutes away, and pushes are the real update path anyway.
    try {
        if (resp.hasError()) {
            noteBoardContact(false, resp.getErrorMessage())
            return
        }
        if (resp.status == 200 || resp.status == 207) {
            String body = resp.data as String
            if (body?.startsWith('&')) {
                noteBoardContact(true, null)
                doPoll(body)
            } else {
                noteBoardContact(false, "unexpected response body")
            }
        } else {
            noteBoardContact(false, "HTTP ${resp.status}")
        }
    } catch (Exception e) {
        noteBoardContact(false, e.message)
    }
}

/**
 * Track whether the board is reachable, and say something only when that changes.
 *
 * A board that is switched off would otherwise log an identical warning on every sweep, forever.
 * Logging the transitions instead means the log records when it went away and when it came back,
 * which is the part worth knowing, and stays quiet in between.
 *
 * Note what this deliberately does not do: it never changes a contact or switch state. If the board
 * is unreachable we simply do not know what the doors are doing, and inventing a value -- closing
 * everything, or forcing it open -- would be worse than showing the last known truth. The state is
 * stale, and the app says so plainly rather than guessing.
 */
private noteBoardContact(boolean reachable, String detail) {
    boolean was = (state.boardOnline != false)      // unknown counts as online, so the first failure is reported

    if (reachable) {
        state.boardOnline = true
        state.lastContact = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        if (!was) {
            log.info "Relay board at ${settings.ribAddress} is reachable again."
            // While it was away it may have rebooted, been factory reset, or had its configuration
            // replaced -- in which case the push URLs are gone and events would silently never
            // resume. Re-check now, or finish setup if it never completed in the first place.
            runIn(2, (state.setupPending ? "retrySetup" : "verifyProvisioning"))
        }
    } else {
        state.boardOnline = false
        if (was) {
            log.warn "Relay board at ${settings.ribAddress} is not responding (${detail}). Inputs and relays " +
                     "will keep showing their last known state until it returns. Not logging this again until " +
                     "something changes."
        } else {
            logDebug "board still unreachable (${detail})"
        }
    }
}

/**
 * Parse an input.cgi response and push the current level of every input onto its child device.
 *
 * The response is an ampersand delimited list, but there are two formats in the wild:
 *
 *     new firmware: &0&0&8&1&1&1&1&1&1&1&1&    (result, start, count, values...)
 *     old firmware: &0&8&1&1&1&1&1&1&1&1&      (result, count, values...)
 *
 * so a fixed offset is only correct for one of them, and only for single digit channel counts --
 * a 16 or 32 channel board shifts everything by another character.  Instead we locate the count
 * field the way the vendor's own reference parser does (see get_ch_offset in the SDK's
 * http_format/input_cgi_parse.php): scan from the end for the first field greater than 1, since
 * every input value is only ever 0 or 1.  Values then follow immediately after it.
 */
def doPoll(response) {

    logDebug "doPoll(): Response = $response"

    List keys = response.toString().tokenize('&')     // tokenize also drops the leading/trailing empties
    int offset = channelCountOffset(keys)
    if (offset < 0) {
        log.warn "doPoll(): could not locate the channel count in '${response}'"
        return
    }

    int count = toInt(keys[offset])

    for (aDevice in activeChildren("RIBContact-")) {
        Integer inputNum = inputNumberOf(aDevice)

        // Skip any input the board doesn't actually have -- e.g. a device left behind after moving
        // to a board with fewer channels.
        if (inputNum == null || inputNum < 1 || inputNum > count) continue

        int valueIndex = offset + inputNum            // input 1 sits immediately after the count field
        if (valueIndex >= keys.size()) continue       // truncated or malformed response; leave state alone

        String inputState = keys[valueIndex]

        if (inputState == "1") aDevice.isOpen() else aDevice.isClosed()

        logDebug "doPoll(): Device = $aDevice, " + inputState
    }
}

/** Index of the channel count field, or -1 if the response doesn't look like input.cgi output. */
private int channelCountOffset(List keys) {
    if (keys == null || keys.size() < 3) return -1
    for (int i = keys.size() - 1; i >= 0; i--) {
        if (toInt(keys[i]) > 1) return i
    }
    return -1
}

/**
 * Recover the input number from a child's DNI ("RIBContact-3_247" -> 3).  Uses a regex rather than
 * a fixed character position, which is what previously limited this to inputs 1 through 9.
 */
private Integer inputNumberOf(aDevice) {
    java.util.regex.Matcher m = (aDevice.deviceNetworkId =~ /^RIBContact-(\d+)_/)
    return m.find() ? (m.group(1) as Integer) : null
}

/** Lenient int parse -- returns -1 for anything non numeric so callers can just compare. */
private int toInt(value) {
    try {
        return (value as String).trim() as int
    } catch (Exception ignored) {
        return -1
    }
}

/**
 * Legacy input count probe, used only when the board is too old to answer /api/v2/config.cgi.
 * Shares the same offset safe parsing as the sweep.
 */
/**
 * How many channels the board has, asked directly of the board.
 *
 * Works against "/input.cgi" for inputs and "/relay_cgi_load.cgi" for relays -- both answer with the
 * same ampersand delimited shape, so the same offset safe parsing finds the count in either.  This
 * is the fallback for boards too old to have the config API, which is the only way to learn their
 * channel counts at all.
 *
 * Returns 0 if the board did not answer or the response made no sense.
 */
private int countFromCgi(String path) {
    int count = 0
    try {
        httpGet("http://" + settings.ribAddress + path) { resp ->
            if (resp.success) {
                logDebug "countFromCgi(${path}): ${resp.data}"
                List keys = (resp.data as String).tokenize('&')
                int offset = channelCountOffset(keys)
                if (offset >= 0) count = toInt(keys[offset])
            } else if (resp.data) {
                logDebug "countFromCgi(${path}): failed, ${resp.data}"
            }
        }
    } catch (Exception e) {
        log.warn "countFromCgi(${path}): ${e.message}"
    }
    return count
}

// Note: the original checked "|| settings?.debugOutput == null", which meant debug logging was on
// by default until the toggle was explicitly saved once.  Respect the declared default of false.
private logDebug(msg) {
  if (settings?.debugOutput) {
    log.debug "$msg"
  }
}
