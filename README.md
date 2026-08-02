# Relay Input Board App for Hubitat — Event Driven
[![License](https://img.shields.io/github/license/explosivo22/rinnaicontrolr-ha?style=for-the-badge)](https://opensource.org/licenses/Apache-2.0)

> **This is the `event-driven` branch.** The original version on `main` asks the board for input
> status once every second, which puts a constant load on the hub. This version instead has the
> board *push* to Hubitat the instant an input changes, using the Dingtian **Input Link URL**
> feature. A slow reconcile sweep (every 5 minutes by default) remains as a safety net so a dropped
> push can never leave a door reading the wrong state.
>
> Requires board firmware **V3.1.2776 or later** (V3.1.6611 recommended). See "Firmware" below.
> Everything else — the contact devices, their names, the Normally Open / Normally Closed setting —
> behaves exactly as it did before.

This is a Hubitat App and Driver set for the Ding-Tian Relay/Input Board.  I now use the relays for some low voltage lights and the inputs for my security system sensors, replacing an old traditional security system with it's ancient control panels.  I had another solution that had failed twice, requiring a few weeks of my system not working.  After that, I decided that I would see if I could use the inputs on the Ding-Tian Relay/Input board and after a few days, I got it to work with my Hubitat Elevation smart home hub.  I selected the Ding-Tian board because it has outstanding connectivity, available with wifi and ethernet!  The board has an integrated web server to configure everything.  The network settings are fantastic, including local name resolution!  Too bad Hubitat can use it.  

![DingTian RIB](https://user-images.githubusercontent.com/39914475/197233866-b245dfa0-b4ff-4789-b191-5ef039694b1d.jpg)

Technical documentation about the board can be found here: http://www.dingtian-tech.com/sdk/relay_sdk.zip
The hardware manufacturer is here: https://www.dingtian-tech.com/en_us/index.html

This can serve as a complete replacement for a traditional wired security system.  You can have up to 8 contact sensors.  But, you can also pool some, so you might make all the windows in a room a single alert. 

![RIBApp](https://user-images.githubusercontent.com/39914475/197240796-6e1ca5c8-efab-413c-903d-77e13c0825a0.jpg)

I will eventually record a video of how to set everything up.  

## Getting Setup  

1. Buy the board on ebay.  It can take a few weeks to months to arrive!  
2. Connect the board to your network and log in to the admin console using your browser.  
3. There is nothing to configure on the board by hand. The app fills in the "Input Link URL" page itself, and sets "Input Control Relay" to No for you — see [Inputs no longer switch relays on the board](#inputs-no-longer-switch-relays-on-the-board) for why.  
4. I like to setup the board to use DHCP, so the router assigns an IP address.  I also like to set the "hostname" in the setting page, so I can browse to this admin console from the browser using http://hostname.local  Then save and reboot the board.  
5. Go to your router and manually assign the IP so that it NEVER changes.  Write down that IP. 
6. Go to http://hubitat.local and expand the Developer Tools.  Click on Apps Code, New App button, Import button, paste this url: https://raw.githubusercontent.com/TonyMajorDev/RelayInputBoard/event-driven/RelayBoard-app.groovy
7. Click import button, Yes, overwrite, Click the Save button.
8. **Enable OAuth.** Still in the app code editor, click the "OAuth" button at the top right, click "Enable OAuth in App", then Update. This is what lets the board send events to the hub. It is a one-time step, and the app will tell you on its settings page if you forget. (The board itself does not do OAuth — enabling this simply makes Hubitat mint a token that gets included in the URL the board calls.) Then click "<< Apps code" to go back.
9. Now the drivers — there are two. Click on "Drivers code", Click New Driver button, Import button, paste this url: https://raw.githubusercontent.com/TonyMajorDev/RelayInputBoard/event-driven/RelayBoard-contact-sensor-driver.groovy — then Import, Yes overwrite, Save. Now click New Driver again and do the same with the relay switch driver: https://raw.githubusercontent.com/TonyMajorDev/RelayInputBoard/event-driven/RelayBoard-relay-switch-driver.groovy
10. Click "<< Drivers code" to go back. Both drivers are required — the app creates a device for every input and every relay on the board.
11. Now let's setup.  At this point, you should have your sensors wired into the input terminals on the Relay/Input board (RIB).  Now, above the develtoper tools, Click on "Apps" (Not "Apps code").  On the top right, click "Add User App".  Find and select "RIB App (Event)"
12. Now, we are about done.  Remember that IP Address you wrote down from step #5?  Type that into the "Relay Interface Board Address".  It probably starts with "192.168."  
13. Click "Done" button! 
14. Now if everything worked, it communicated with the RIB, asked how many inputs and relays it has, created a **RIB Input** device per input and a **RIB Relay** device per relay, **and told the board to push future changes straight to the hub**.  Go check your Devices.  You can select an input you have connected and see if the state changes from contact: open to closed — it should now react instantly rather than up to a second later.  The numbers in the device names match the numbers printed next to the screw terminals on the board.  So, "I3" printed on the board is "RIB Input 3", and "R3" is "RIB Relay 3".  
15. Check the app's settings page for a red error message before you trust any of it. That is where OAuth problems, old firmware and failed board writes are reported.  
16. If Open and Closed are reversed, you can choose to reverse that in the Device settings.  
17. Also, in the Device settings you can and should change the Device Name from "RIB Input 1" to "Front Door".  Also, if you end up not using all 8, you can just delete the unused RIB Input and RIB Relay devices.  If you ever want them back, just go back to the RIB App, don't change anything, and click "Done" and the missing devices will be restored.  
18.  Now, you can go to your Amazon Alexa App and add these inputs and update.  Then Ask Alexa to discover new devices.  Then you can create routines to do speech announcements when the door is open.  Also, you can use the Notifications App in Hubitat to give you phone notifications whenever a door is opened.  Or turn lights on when you enter.  Or whatever...  

**If your hub's IP address ever changes**, open the app and click Done again — that re-writes the push URLs on the board with the new address.

## Firmware

"Input Link URL" is what makes the push work, and it arrived in firmware **V3.1.2776 (Aug 2023)**.
That is the only hard requirement. If your board is newer than that, you very likely do not need to
upgrade anything.

Later releases did fix bugs in this same feature, but none of them affect how this app uses it:

| Fix | Version | Relevant here? |
|---|---|---|
| Crash when HTTPS is enabled | V3.1.5182 | No — the app talks plain HTTP to the hub |
| POST/PUT `body` not saving | V3.1.6312 | No — the app uses GET with no body |
| 10 KB body, input link queue | V3.1.6553 / 6611 | No — nothing here needs a body |

So upgrade only if you want fixes for something else. If you do, **V3.1.6611** is the one to land on
— V3.1.6553 immediately before it is known to crash. Two fixes worth knowing about if you use the
relays: V3.1.2178 and V3.1.5312 both address "Power Failure Recovery Relay" being set to Yes causing
the board to lose its config, and V3.1.5128 fixes board-side `relay_task` schedules firing an hour
late around daylight saving.

The app shows you your board's current version on its settings page, along with links to the downloads, and warns you in red if the board is too old to push events. You can also check it yourself with:

```bash
curl -s http://<BOARD_IP>/api/v2/config.cgi
```

Look for `"sw_ver"`. Downloads are at [Dingtian support → Download](https://www.dingtian-tech.com/en_us/support.html?tab=download), or directly: <http://www.dingtian-tech.com/sdk/relay_upgrade_tool.zip>. The upgrade path depends on where you are starting from:

- Already on **v3.1.4897 or later** → flash `ESP32_<n>ch_v3_1_6611.dtf2` directly
- Older than **v3.1.4897** → flash `ESP32_<n>ch_v3_1_4897.dtf` **first**, then the `.dtf2`

Match `<n>` to your board's channel count — an 8-relay board takes the `8ch` files, a 4-relay board
the `4ch` files. The model is printed on the board and shown on the app's settings page.

Use `ota_tool_v4_1_1.exe` from Dingtian's upgrade tool. Turn off your PC firewall while doing it, use a wired connection, and make sure power is stable.

## Troubleshooting

- **Inputs only update every few minutes.** The push isn't arriving, so you're seeing the reconcile sweep doing its job. Check the app's settings page for a red error message. The most likely causes are OAuth not being enabled (step 8) or firmware older than V3.1.2776.
- **"The board did not accept the push configuration".** The board kept its own settings and ignored the write. Turn on debug logging and click Done — the log will show `writeBoardConfig(): sending N bytes` and the board's own reply. A reply of `{"status":0}` means the board accepted it, anything else means it refused.
- **"The board shortened the push URL".** A genuine field-length limit in that firmware. Worth reporting, with your `sw_ver` and the length it stored.
- **A relay clicks whenever a door opens or closes.** The board is linking inputs to relays. Turn on "Stop inputs from switching relays on the board itself" and click Done.
- **A relay device shows the wrong state.** The app sets devices from what the board actually reports, so this usually means the command didn't reach the board. Check the log for `Relay N did not switch` and verify the relay password in the app matches the board's.
- **Nothing works after a board factory reset.** Open the app and click Done to re-provision it.

## If you run more than one board

It's common to have a second Dingtian board doing something unrelated — sprinklers, low voltage
lighting — with nothing wired to its inputs. **Point this app only at the board your contact sensors
are actually wired to.** The app's settings page shows the model it found (e.g. `Dingtian DT-R008`)
next to the firmware version, so you can confirm you're talking to the right one before clicking
Done. A board used purely for outputs needs nothing from this app and should be left alone.

Note that one board can happily do both — door sensors on its inputs and lights on its relays. The
app only writes input-related settings, so the relay side of that board keeps working exactly as it
did.

### Inputs no longer switch relays on the board

Boards leave the factory with input 1 wired to relay 1, input 2 to relay 2, and so on, so the board
switches its own relays whenever an input changes. With contact sensors on the inputs that means
every door event clicks a relay — baffling to track down, and it makes those relays unusable for
anything else.

The app turns this off for you (it sets *Input Control Relay* and *Relay Feedback Momentary Input*
to No). There is no setting for it, because there is no way to use this app that wants it left on.
It does not affect anything else: controlling relays from Hubitat, relay tasks scheduled on the
board, and the auto-off timer all behave exactly the same.

## Controlling Relays from Hubitat

The app creates a **RIB Relay Switch** device for every relay on the board. There is nothing to
configure — it knows the board's address, builds the control URLs itself, and rewrites them on every
relay device if that address ever changes. An 8-relay board gives you 8 relay switches in your
devices list, ready to use in rules, dashboards and Alexa exactly like any other switch.

The ON and OFF URLs are shown on each device page under Current States as `onUrl` and `offUrl`, so
you can see exactly what is being sent. They are read-only on purpose — the app owns them, and
letting them be edited is how they would drift out of sync with the board's address.

### The auto-off timer

Each relay switch has an optional **"Use the board's built-in auto-off timer"** setting with a time
in minutes. When enabled, ON sends `type=2` with a `time=` value, which asks the board to start a
countdown and switch that relay off by itself when it expires.

**The countdown runs on the relay board, not on Hubitat.** That is the whole point: the relay
switches off on schedule even if the hub reboots, this app crashes, the network drops, or the OFF
command is never sent. A Hubitat-side timer would fail in exactly those situations. Use it for
anything that must never be left running — sprinklers, a heater, a pump.

- Sending OFF early cancels the countdown normally.
- Sending ON again restarts the countdown from the beginning.
- Maximum is 1092 minutes (about 18 hours), because the board stores the value in a 16-bit field.
- The one gap: the timer lives in the board's memory, so the board *losing power* mid-countdown
  cancels it. Check the board's "Power Failure Recovery Relay" setting if that matters for a
  particular circuit.

### How relay state is kept accurate

The app never assumes a command worked, but it doesn't poll to find out either. The board's reply to
a relay command echoes back what it actually did — `&status&type&relay&on&time&` — and it arrives in
around 30 milliseconds, so that reply is what updates the device. Switching a relay shows up in
Hubitat essentially instantly.

A follow-up read is scheduled as a backstop for the case where that reply is lost or disagrees, and
it cancels itself as soon as the reply confirms. If a relay never reaches the requested state, an
error is logged naming it.

Devices are only ever set from what the board reports, never from what was requested — so a relay
that physically failed to switch shows the truth rather than a comforting lie.

Relay states are also refreshed on the same reconcile sweep as the inputs, which is what catches an
auto-off timer expiring or someone switching a relay from the board's own web page.

### The URLs, for reference

You don't need these — the app builds and sends them for you — but this is what goes on the wire,
and it's still useful for a second board that this app doesn't manage.

A plain light on relay 7:

```
On:  http://192.168.50.30/relay_cgi.cgi?type=0&relay=6&on=1&time=0&pwd=0&
Off: http://192.168.50.30/relay_cgi.cgi?type=0&relay=6&on=0&time=0&pwd=0&
```

A sprinkler station on relay 1 of a separate board, with a 30 minute auto-off:

```
On:  http://192.168.50.101/relay_cgi.cgi?type=2&relay=0&on=1&time=1800&pwd=0&
Off: http://192.168.50.101/relay_cgi.cgi?type=0&relay=0&on=0&time=0&pwd=0&
```

Two things to notice. `relay=` is **zero based**, so `relay=6` is the relay marked R7 on the board.
And the ON url for the sprinkler uses `type=2` with `time=1800` — the same board-side timer the
relay device's auto-off setting uses, and the reason the sprinklers can never accidentally stay on.

## A note on where timers should live

The board-side auto-off is the single most important safety property of this whole setup, and it is
worth being explicit about why. `type=2` with a `time=` value runs the countdown **on the relay
board**, not on the hub. Once the board has accepted that command the relay will shut off on time
even if the hub reboots, this app crashes, the network drops, or the OFF command never arrives.

A Hubitat-side `runIn()` timer would be strictly worse, because it fails in precisely the situations
you most need it to work. The same reasoning is why a physical switch wired straight to a relay is
sometimes the right answer for a light: the fewer things that must be alive for a circuit to behave
safely, the better.
