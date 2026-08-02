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
    page name: "discoveryPage", title: "", install: false, uninstall: false
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
    dynamicPage(name: "mainPage") {
        section("Relay Input Board Configuration") {
            // This must be the board your INPUTS are wired to. If you run more than one Dingtian
            // board, check the model shown under "Board Firmware" below to confirm you have the
            // right one before clicking Done.
            input name: "ribAddress", type: "text", title: "Relay Interface Board Address", submitOnChange: true, required: true, defaultValue: "192.168.50.30" // local name resolution does not work on hubitat hub "homerelays.local"
            href name: "toDiscovery", page: "discoveryPage", title: "Search the network for relay boards",
                 description: "Optional. You can always just type the IP address above."
            // This is the safety net sweep, not the primary update path -- inputs normally update
            // the instant they change.  Offered as an enum rather than a free number because each
            // choice maps to one of Hubitat's fixed slot schedulers, which are cheaper and cannot
            // stack up the way a custom cron expression can.
            input name: "reconcileMinutes", type: "enum", title: "How often to re-sync all inputs as a safety net",
                options: ["1": "Every minute", "5": "Every 5 minutes", "10": "Every 10 minutes", "15": "Every 15 minutes", "30": "Every 30 minutes"],
                defaultValue: "5", required: true
            // On by default. Boards ship with every input wired to the matching relay, which is
            // almost never what someone monitoring door sensors wants -- a door closing then clicks
            // a relay for no apparent reason, and it fights any independent use of the relays.
            //
            // The one case for turning this off is a physical switch wired to an input driving a
            // relay directly, so that light keeps working while the hub is down. That is a genuine
            // failsafe and worth preserving, which is why this stays a choice rather than something
            // the app just does. It has no bearing on event push either way.
            input name: "disableInputRelayLink", type: "bool",
                title: "Stop inputs from switching relays on the board itself",
                description: "Recommended. Boards are shipped with input 1 wired to relay 1, input 2 to relay 2, " +
                             "and so on, which will fight any other use of the relays. Only turn this off if you " +
                             "have a physical switch on an input that you want to keep working even when the hub is down.",
                defaultValue: true

            if (state.inputLinkRelayActive && settings.disableInputRelayLink == false) {
                paragraph "<b>Heads up:</b> this board currently lets its inputs switch its own relays directly " +
                          "&mdash; by default input 1 drives relay 1, input 2 drives relay 2, and so on. With door " +
                          "sensors on the inputs, opening or closing a door will click the matching relay. Leave " +
                          "this turned off only if that is deliberate."
            }
            input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: false
        }

        section("Relay Outputs") {
            input name: "createRelayDevices", type: "bool", submitOnChange: true,
                title: "Create a switch device for each relay",
                description: "Builds the control URLs for you and keeps them correct if the board's address changes.",
                defaultValue: true

            if (settings.createRelayDevices != false) {
                input name: "relayPassword", type: "number", title: "Relay password (0 if you have not set one)",
                    defaultValue: 0, required: false

                paragraph "<b>About the auto-off timer.</b> Each relay switch has an optional " +
                          "\"turn off automatically after N minutes\" setting. When it is on, the ON command asks " +
                          "the board to start a countdown and switch that relay off by itself once the time is up. " +
                          "<b>The countdown runs on the relay board, not on Hubitat</b> &mdash; so the relay still " +
                          "switches off on time even if the hub reboots, this app crashes, your network drops, or " +
                          "the OFF command never arrives. That makes it the right choice for anything that must " +
                          "never be left running, such as sprinklers or a heater."

                paragraph "<small>Sending OFF early cancels the countdown normally. Sending ON again restarts it " +
                          "from the beginning. The one case it does not cover is the board itself losing power " +
                          "mid-countdown, since the timer lives in the board's memory &mdash; check the board's " +
                          "\"Power Failure Recovery Relay\" setting if that matters to you. The maximum is 1092 " +
                          "minutes (about 18 hours).</small>"
            }
        }
        // Setup can fail in a few quiet ways (OAuth off, firmware too old, board unreachable).
        // Surface them here rather than leaving the user to find them in the logs.
        section("Status") {
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
            } else {
                paragraph "Board firmware version unknown &mdash; the board has not answered yet."
            }

            paragraph "Event driven mode needs firmware <b>${MIN_FIRMWARE_TEXT}</b> or later. " +
                      "<b>${REC_FIRMWARE_TEXT}</b> is recommended, since later releases fixed real bugs in this " +
                      "exact feature and the release immediately before it is known to crash."

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


// =================================================================================================
// Board discovery
//
// The vendor's own IP finder works by sending a two byte probe (0x05 0xAA) to the multicast group
// 224.0.2.11 on port 60000; boards on the LAN answer, which reveals their addresses.  See
// linux_find_relay_ip.txt in the SDK.
//
// Caveat worth knowing: Hubitat is restrictive about handing unsolicited LAN traffic to an app, so
// this may legitimately find nothing on some hubs even when boards are present.  It is offered as a
// convenience only -- typing the IP address by hand is fully supported and always works.  Anything
// the probe turns up is verified by actually asking it for its config before being offered, so a
// stray reply from some other device on the network can't end up in the list.
// =================================================================================================

def discoveryPage() {
    // refreshInterval re-renders the page while replies trickle in, so results appear as they land.
    dynamicPage(name: "discoveryPage", title: "Search for Relay Boards", refreshInterval: 5) {

        // The page refreshes every few seconds so results can appear as they arrive, which means
        // this method runs repeatedly.  Only kick off a genuinely new search when the user asked
        // for one, when arriving here for the first time, or when the last search has gone stale --
        // otherwise every refresh would fire another probe and the scan would never end.
        boolean stale = !state.discoveryStartedAt || (now() - (state.discoveryStartedAt as Long)) > 120000
        if (params?.restart || stale) startDiscovery()

        Map found = (state.discovered ?: [:])

        section {
            if (found) {
                paragraph "Found ${found.size()} board${found.size() == 1 ? '' : 's'}."
                input name: "discoveredBoard", type: "enum", title: "Use this board", submitOnChange: true,
                      required: false, options: found.collectEntries { ip, info ->
                          [(ip): "${ip}  --  ${info.model ?: 'Dingtian'} ${info.sw_ver ?: ''} (${info.inputs ?: '?'} inputs)"]
                      }
                if (settings.discoveredBoard) {
                    app.updateSetting("ribAddress", [value: settings.discoveredBoard, type: "text"])
                    paragraph "<b>Board address set to ${settings.discoveredBoard}.</b> Go back and click Done."
                }
            } else if (state.discovering) {
                paragraph "Searching... boards usually answer within a few seconds."
            } else {
                paragraph "<b>No boards found.</b>"
                paragraph "<small>This does not necessarily mean anything is wrong with your board. Hubitat is " +
                          "restrictive about passing this kind of network traffic to an app, so discovery simply " +
                          "will not work on some hubs. Go back and type the IP address in directly &mdash; you can " +
                          "find it in your router's client list, or with Dingtian's own IP finder tool from " +
                          "<a href='${FIRMWARE_PAGE}' target='_blank'>the download page</a>.</small>"
            }
        }

        section {
            href name: "rescan", page: "discoveryPage", title: "Search again", description: "",
                 params: [restart: true]
            href name: "backToMain", page: "mainPage", title: "Back", description: ""
        }
    }
}

/** Fire the multicast probe and listen briefly for replies. */
private startDiscovery() {
    state.discovering = true
    state.discovered = [:]
    state.discoveryProbed = []
    state.discoveryStartedAt = now()

    // Every step here can legitimately fail -- most often when the app has not been installed yet,
    // since discovery is reachable from the setup page before the first Done.  None of it is worth
    // breaking the page over, because typing the address by hand is always available.
    try {
        // Listen to raw LAN traffic for the duration of the search only.  Leaving this subscription
        // in place would hand this app every LAN message the hub sees, which is exactly the kind of
        // constant background work this rewrite exists to remove -- so it is always torn down.
        subscribe(location, null, "lanDiscoveryHandler", [filterEvents: false])

        String probe = hubitat.helper.HexUtils.byteArrayToHexString([0x05, 0xAA] as byte[])
        sendHubCommand(new hubitat.device.HubAction(probe,
            hubitat.device.Protocol.LAN,
            [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
             destinationAddress: "224.0.2.11:60000",     // port 60000, per the SDK's linux_find_relay_ip.txt
             encoding: hubitat.device.HubAction.Encoding.HEX_STRING]))

        logDebug "startDiscovery(): sent probe to 224.0.2.11:60000"
        runIn(20, "stopDiscovery")
    } catch (Exception e) {
        state.discovering = false
        log.warn "startDiscovery(): discovery is unavailable on this hub (${e.message}). Enter the board address manually."
    }
}

def stopDiscovery() {
    state.discovering = false
    try {
        // Safe to drop everything: this app holds no other subscriptions -- it is driven by the
        // board pushing to its endpoint and by a scheduled sweep, neither of which uses subscribe().
        unsubscribe()
    } catch (Exception e) {
        log.warn "stopDiscovery(): ${e.message}"
    }
    logDebug "stopDiscovery(): found ${(state.discovered ?: [:]).size()} board(s)"
}

/**
 * Any LAN message that arrives while a search is running.  We only care about the sender's address;
 * the reply payload format isn't documented, so rather than trying to interpret it we just ask the
 * sender whether it is in fact a Dingtian board.
 */
def lanDiscoveryHandler(evt) {
    if (!state.discovering) return

    try {
        Map msg = parseLanMessage(evt.description)
        String ip = hexToIp(msg?.ip)
        if (!ip || state.discovered?.containsKey(ip)) return

        // Verifying costs a real HTTP request, and on a busy network this handler can see a lot of
        // unrelated traffic.  Only try each address once, and stop after a sensible number, so a
        // chatty LAN can't turn a 20 second search into a long stall.
        List probed = (state.discoveryProbed ?: [])
        if (probed.contains(ip) || probed.size() >= 30) return
        probed << ip
        state.discoveryProbed = probed

        // A board answers /api/v2/config.cgi with a config blob; anything else on the network
        // won't, so this filters out unrelated chatter for free.
        Map cfg = fetchBoardConfigAt(ip, 5)
        if (cfg?.input_link_relay != null || cfg?.network?.model) {
            state.discovered[ip] = [
                model:   cfg?.network?.model,
                sw_ver:  cfg?.network?.sw_ver,
                inputs:  cfg?.input_link_relay?.input_cnt
            ]
            log.info "Discovered relay board at ${ip}: ${cfg?.network?.model} ${cfg?.network?.sw_ver}"
        }
    } catch (Exception e) {
        logDebug "lanDiscoveryHandler(): ignored a message (${e.message})"
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

/** "C0A80164" -> "192.168.1.100" */
private String hexToIp(String hex) {
    if (!hex || hex.length() != 8) return null
    return [0, 2, 4, 6].collect { Integer.parseInt(hex.substring(it, it + 2), 16) }.join(".")
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
        Map cfg = fetchBoardConfig()
        if (cfg?.input_link_url != null) {
            cfg.input_link_url.en = 0
            writeBoardConfig(cfg)
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

    unschedule()

    // If the user clicked Done while a discovery scan was still running, the unschedule() above
    // just cancelled its teardown -- so tear it down here instead. Otherwise the app would keep a
    // subscription to all LAN traffic alive indefinitely.
    unsubscribe()
    state.discovering = false

    state.remove('working')     // leftover from the old polling mutex; harmless, but don't keep it around
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

    Map cfg = fetchBoardConfig()

    int inputCount = 0
    if (cfg) {
        state.boardVersion = cfg?.network?.sw_ver
        state.boardModel = cfg?.network?.model
        state.firmwareTooOld = !firmwareSupportsPush(state.boardVersion)
        inputCount = (cfg?.input_link_relay?.input_cnt ?: cfg?.input_link_url?.cnt ?: 0) as int
        state.relayCount = (cfg?.input_link_relay?.relay_cnt ?: cfg?.relay_task?.relay_cnt ?: 0) as int
        // Boards ship with every input wired to the matching relay (I1->R1 ...). That is invisible
        // unless you go looking at the board's web page, and it fights with using the relays for
        // anything else -- so surface it instead of letting people hunt for a mystery relay click.
        state.inputLinkRelayActive = (cfg?.input_link_relay?.input_link_relay?.toString() == "1")

        if (state.firmwareTooOld) {
            log.warn "initialize(): board firmware ${state.boardVersion} is older than ${MIN_FIRMWARE_TEXT}; " +
                     "event push is unavailable. See the app's settings page for upgrade links."
        }
    }

    // Older firmware has no /api/v2/config.cgi at all, so fall back to counting inputs the way the
    // original app did.  Push will not be available on such a board, but everything else still works.
    if (inputCount < 1) inputCount = inputCountFromInputCgi()

    state.inputCount = inputCount

    if (inputCount < 1) {
        log.warn "initialize(): could not determine the input count from the board -- check the address"
        return
    }

    createChildDevices(inputCount)

    if ((state.relayCount ?: 0) > 0) createRelayDevices(state.relayCount as int)

    if (cfg && state.accessToken) {
        provisionBoard(cfg, inputCount)
    } else if (!cfg) {
        // The board answered input.cgi but not the config API, so it predates /api/v2/config.cgi.
        // Degrade gracefully: inputs still track, just on the sweep rather than on push.
        state.provisionError = "The board did not answer /api/v2/config.cgi, so push could not be set up. " +
                               "Firmware V3.1.2776 or later is required. Inputs will still update on the reconcile sweep."
        log.warn "initialize(): ${state.provisionError}"
    }

    scheduleReconcile()

    // Seed current state now rather than leaving every contact showing whatever it last showed
    // until either the first sweep or the first physical edge.
    poll()
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
            addChildDevice("community", "RIB Contact Sensor", dni, null, [name: "RIB Input ${i}"])
        }
    }
}

/** Single source of truth for the child DNI format, used by both the push path and the sweep. */
private String contactDni(idx) {
    return "RIBContact-${idx}_${app.id}"
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

    logDebug "handleInputEvent(): input ${idx} = ${val}"

    def dev = getChildDevice(contactDni(idx))
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
 * Same, but against an arbitrary address -- used while verifying discovery results, where a short
 * timeout matters because most addresses tried will not be relay boards at all.
 */
private Map fetchBoardConfigAt(String address, int timeoutSeconds = 15) {
    Map cfg = null
    try {
        httpGet([uri: "http://${address}", path: "/api/v2/config.cgi", contentType: "text/plain", timeout: timeoutSeconds]) { resp ->
            if (resp.success) {
                def d = resp.data
                if (d instanceof Map) {
                    // Hubitat parsed it for us based on the board's content type.  Still order
                    // preserving, since Hubitat parses with JsonSlurper and that is LinkedHashMap backed.
                    cfg = (Map) d
                } else {
                    String raw = (d instanceof String) ? d : d.getText()
                    cfg = (Map) new JsonSlurper().parseText(raw)
                }
            }
        }
    } catch (Exception e) {
        log.warn "fetchBoardConfig(): ${e.message}"
    }
    return cfg
}

/**
 * Write the whole configuration back to the board.
 *
 * Two constraints from the vendor SDK, and both are easy to violate by accident:
 *
 *   "only support compressed json, must remove all formatting characters(\r\n\t\space\...),
 *    notice:the node order can't change"
 *
 * JsonOutput.toJson emits no whitespace, which satisfies the first.  The second is why this app
 * always mutates the Map it got back from fetchBoardConfig() in place and hands the same object
 * here.  Never rebuild this map from scratch, never sort its keys, and never pretty print it.
 *
 * Because this writes everything, it is also what preserves the settings we care about not
 * breaking -- relay tasks, relay password, power failure recovery -- untouched.
 */
private boolean writeBoardConfig(Map cfg) {
    String body = JsonOutput.toJson(cfg)
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
private provisionBoard(Map cfg, int inputCount) {

    if (cfg.input_link_url == null) {
        state.provisionError = "This board's firmware has no Input Link URL support (needs V3.1.2776 or later). Reported version: ${state.boardVersion}"
        log.error "provisionBoard(): ${state.provisionError}"
        return
    }

    // Keep one pristine copy of what the board looked like before we ever touched it, so a bad
    // write is recoverable without resorting to the physical factory reset button.
    if (!state.configBackup) state.configBackup = JsonOutput.toJson(cfg)

    Map hub = hubEndpointParts()
    if (!hub) {
        state.provisionError = "Could not parse the hub's local API URL (${getFullLocalApiServerUrl()})"
        log.error "provisionBoard(): ${state.provisionError}"
        return
    }
    String hubHost = hub.host
    int hubPort = hub.port
    String basePath = hub.basePath

    int n = inputCount

    def ilu = cfg.input_link_url
    ilu.en           = 1
    ilu.cnt          = n
    // SelfLock (0), not Momentary (1).  Momentary fires a pulse on one edge only, which would let
    // the contact state drift out of sync; SelfLock follows the level and gives us both edges.
    ilu.type         = (1..n).collect { 0 }
    // Treat HIGH as the "on" edge so that the ON URL means the same thing as a "1" in the
    // input.cgi response, keeping push and sweep consistent.  If open/closed come out backwards
    // for a given sensor, that is what the driver's Normally Open / Normally Closed setting fixes.
    ilu.active_level = (1..n).collect { 1 }
    ilu.tls          = (1..n).collect { 0 }             // plain HTTP on the LAN; HTTPS to the hub buys nothing here
    ilu.auth         = (1..n).collect { 0 }             // no Basic/Digest -- our token rides in the path instead
    ilu.server       = (1..n).collect { hubHost }
    ilu.port         = (1..n).collect { hubPort }
    ilu.user         = (1..n).collect { "" }
    ilu.pass         = (1..n).collect { "" }
    ilu.on_method    = (1..n).collect { 0 }             // 0 = GET
    ilu.on_path      = (1..n).collect { i -> pushPath(basePath, i, 1) }
    ilu.on_body      = (1..n).collect { "" }
    ilu.off_method   = (1..n).collect { 0 }
    ilu.off_path     = (1..n).collect { i -> pushPath(basePath, i, 0) }
    ilu.off_body     = (1..n).collect { "" }

    // Opt in only.  Event push works whether or not the board also drives its own relays from its
    // inputs, and on a board that runs lights as well as sensors that linkage may well be wired
    // deliberately -- so switching it off without being asked could silently break a light switch.
    //
    // Scope note either way: this governs only inputs driving relays. Relay control via
    // relay_cgi.cgi, the type=2 timed auto-off used for sprinklers, relay_task and everything under
    // relay_connect are untouched and round trip verbatim.
    if (settings.disableInputRelayLink && cfg.input_link_relay != null) {
        if (cfg.input_link_relay.input_link_relay != 0 || cfg.input_link_relay.relay_feedback_momentary_input != 0) {
            log.warn "provisionBoard(): turning off 'Input Control Relay' and 'Relay Feedback Momentary Input' " +
                     "as requested, so inputs no longer switch relays on the board itself"
        }
        cfg.input_link_relay.input_link_relay = 0
        cfg.input_link_relay.relay_feedback_momentary_input = 0
    }

    if (writeBoardConfig(cfg)) {
        state.lastProvisioned = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        logDebug "provisionBoard(): wrote Input Link URL config for ${n} inputs"
        runIn(5, "verifyProvisioning")     // give the board a moment to commit before reading back
    } else {
        state.provisionError = "Failed to write the push configuration to the board at ${settings.ribAddress}"
        log.error "provisionBoard(): ${state.provisionError}"
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
 * Read the config back and confirm the board stored our URLs verbatim.
 *
 * Why bother: the vendor SDK documents maximum lengths for the MQTT fields but says nothing at all
 * about on_path, and our URL is around 70 characters because the access token alone is a 36
 * character UUID.  If some firmware silently truncates it, everything appears to succeed -- the
 * write returns OK -- while the board pushes to a URL that 404s forever and the user just sees
 * inputs that only update every five minutes.  Failing loudly here turns a baffling symptom into a
 * clear message.
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
    if (settings.createRelayDevices == false) return
    for (int i = 1; i <= relayCount; i++) {
        String dni = relayDni(i)
        if (!getChildDevice(dni)) {
            logDebug "createRelayDevices(): adding ${dni}"
            addChildDevice("community", "RIB Relay Switch", dni, null, [name: "RIB Relay ${i}"])
        }
    }
    refreshRelayUrls()
}

/**
 * Push the current URLs onto every relay device.  Called after setup and whenever a device's own
 * timer setting changes, so what is displayed always matches what would actually be sent.
 */
def refreshRelayUrls() {
    for (aDevice in getAllChildDevices()) {
        Integer n = relayNumberOf(aDevice)
        if (n == null) continue

        int autoOff = 0
        try {
            autoOff = (aDevice.autoOffSeconds() ?: 0) as int
        } catch (Exception ignored) { }

        String autoOffText = autoOff > 0 ? "off automatically after ${(autoOff / 60) as int} minute(s)" : "disabled"
        aDevice.setUrls(relayUrl(n, true, autoOff), relayUrl(n, false, 0), autoOffText)
    }
}

/**
 * Send one relay command, then confirm it actually happened.
 *
 * We do not assume success: the board is asked for its real relay status a second later, and again
 * at five seconds if it still doesn't match. Devices are always set to whatever the board reports,
 * never to what we hoped for, so a relay that physically failed to switch shows the truth rather
 * than a comforting lie.
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

    Map expected = (state.relayExpected ?: [:])
    expected[n as String] = turnOn ? "on" : "off"
    state.relayExpected = expected

    asynchttpGet("relayCommandHandler", [uri: url, timeout: 10])

    // Confirm shortly after, then once more a few seconds later if it hasn't caught up yet.
    runIn(1, "verifyRelays")
    runIn(5, "verifyRelaysFinal")

    // A timed ON switches off on its own, so schedule a read a little after the deadline to catch
    // it -- otherwise the device would sit showing "on" until the next sweep.
    if (turnOn && autoOff > 0) runIn(autoOff + 5, "refreshRelays")
}

def relayCommandHandler(resp, data) {
    if (resp?.status != 200) {
        log.warn "relayCommand: board returned HTTP ${resp?.status}"
    } else {
        logDebug "relayCommandHandler(): ${resp.data}"
    }
}

/** Read every relay's real state from the board. */
def refreshRelays(Boolean finalCheck = false) {
    asynchttpGet("relayStatusHandler",
                 [uri: "http://${settings.ribAddress}/relay_cgi_load.cgi", timeout: 10],
                 [finalCheck: (finalCheck == true)])
}

def verifyRelays()      { refreshRelays(false) }
def verifyRelaysFinal() { refreshRelays(true) }

def relayStatusHandler(resp, data) {
    try {
        if (resp.status != 200) {
            log.warn "relay status: HTTP ${resp.status} from ${settings.ribAddress}"
            return
        }

        // Same shape as input.cgi -- e.g. "&0&4&1&0&1&0&" -- so the same offset safe parsing works.
        List keys = (resp.data as String).tokenize('&')
        int offset = channelCountOffset(keys)
        if (offset < 0) {
            log.warn "relay status: could not parse '${resp.data}'"
            return
        }
        int count = toInt(keys[offset])

        boolean isFinal = (data?.finalCheck == true)
        Map expected = (state.relayExpected ?: [:])

        for (aDevice in getAllChildDevices()) {
            Integer n = relayNumberOf(aDevice)
            if (n == null || n < 1 || n > count) continue

            int valueIndex = offset + n
            if (valueIndex >= keys.size()) continue

            String actual = (keys[valueIndex] == "1") ? "on" : "off"
            aDevice.setRelayState(actual)

            String want = expected[n as String]
            if (want && actual == want) {
                expected.remove(n as String)          // settled, stop watching it
            } else if (want && isFinal) {
                log.error "Relay ${n} (${aDevice}) did not switch ${want}. The board still reports ${actual} " +
                          "five seconds after the command. Check the relay password and that relay ${n} exists on this board."
                expected.remove(n as String)
            }
        }

        state.relayExpected = expected
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
    def requestParams = [ uri: "http://" + settings.ribAddress + "/input.cgi", timeout: 10 ]
    logDebug "poll(): $requestParams"
    asynchttpGet("pollHandler", requestParams)

    // Relays get swept too. Their state can change without us being told -- an auto-off timer
    // expiring, the board's own web page, a physical switch wired to an input -- so the same
    // "eventually correct" guarantee should cover them.
    if (settings.createRelayDevices != false && (state.relayCount ?: 0) > 0) refreshRelays()
}

def pollHandler(resp, data) {
    // Deliberately no retry and no rescheduling on failure.  An earlier version of this app tried
    // to reschedule itself when a request failed, and the retries stacked up until the app stopped
    // responding and the hub's CPU spiked.  A failed sweep is not worth chasing: the next one is
    // only a few minutes away, and pushes are the real update path anyway.
    try {
        if (resp.status == 200 || resp.status == 207) {
            String body = resp.data as String
            if (body?.startsWith('&')) {
                doPoll(body)
            } else {
                log.warn "RIB reconcile: unexpected body from ${settings.ribAddress}"
            }
        } else {
            log.warn "RIB reconcile: HTTP ${resp.status} from ${settings.ribAddress}"
        }
    } catch (Exception e) {
        log.warn "RIB reconcile failed: ${e.message}"
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

    for (aDevice in getAllChildDevices()) {
        Integer inputNum = inputNumberOf(aDevice)

        // Skip anything we can't place, and any input the board doesn't actually have -- e.g. a
        // device left behind after moving to a board with fewer channels.
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
private int inputCountFromInputCgi() {
    int inputCount = 0
    try {
        httpGet("http://" + settings.ribAddress + "/input.cgi") { resp ->
            if (resp.success) {
                logDebug "initialize(): Response = " + resp.data
                List keys = (resp.data as String).tokenize('&')
                int offset = channelCountOffset(keys)
                if (offset >= 0) inputCount = toInt(keys[offset])
            } else {
                if (resp.data) logDebug "initialize(): Failed to get Input Count ${resp.data}"
            }
        }
    } catch (Exception e) {
        log.warn "initialize(): Call failed: ${e.message}"
    }
    return inputCount
}

// Note: the original checked "|| settings?.debugOutput == null", which meant debug logging was on
// by default until the toggle was explicitly saved once.  Respect the declared default of false.
private logDebug(msg) {
  if (settings?.debugOutput) {
    log.debug "$msg"
  }
}
