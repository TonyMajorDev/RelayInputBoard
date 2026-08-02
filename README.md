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
3. You no longer need to touch the "Input Link URL" page — the app fills that in itself. It also sets "Input Control Relay" to No for you, which is almost always what you want: boards ship with input 1 wired to relay 1, input 2 to relay 2 and so on, so a door closing will click the matching relay and fight any other use you have for the relays. There is a toggle to leave that alone, and exactly one good reason to use it — see below.  
4. I like to setup the board to use DHCP, so the router assigns an IP address.  I also like to set the "hostname" in the setting page, so I can browse to this admin console from the browser using http://hostname.local  Then save and reboot the board.  
5. Go to your router and manually assign the IP so that it NEVER changes.  Write down that IP. 
6. Go to http://hubitat.local and expand the Developer Tools.  Click on Apps Code, New App button, Import button, paste this url: https://raw.githubusercontent.com/TonyMajorDev/RelayInputBoard/event-driven/RelayBoard-app.groovy
7. Click import button, Yes, overwrite, Click the Save button.
8. **Enable OAuth.** Still in the app code editor, click the "OAuth" button at the top right, click "Enable OAuth in App", then Update. This is what lets the board send events to the hub. It is a one-time step, and the app will tell you on its settings page if you forget. (The board itself does not do OAuth — enabling this simply makes Hubitat mint a token that gets included in the URL the board calls.) Then click "<< Apps code" to go back.
9. Now we do the same for the driver.  Click on "Drivers code", Click New Driver button, Import button, paste this url: https://raw.githubusercontent.com/TonyMajorDev/RelayInputBoard/event-driven/RelayBoard-contact-sensor-driver.groovy
10. Click the Import button, Yes, overwrite, Click the Save button, Click "<< Drivers code"
11. Now let's setup.  At this point, you should have your sensors wired into the input terminals on the Relay/Input board (RIB).  Now, above the develtoper tools, Click on "Apps" (Not "Apps code").  On the top right, click "Add User App".  Find and select "RIB App (Event)"
12. Now, we are about done.  Remember that IP Address you wrote down from step #5?  Type that into the "Relay Interface Board Address".  It is probably starts with "192.168."  There is also a "Search the network for relay boards" link that will try to find boards for you and let you pick one from a list — but Hubitat is fussy about letting apps see that kind of network traffic, so if it comes up empty just type the address in. Typing it in always works.  
13. Click "Done" button! 
14. Now if everything worked, it communicated with the RIB, asked for the number of inputs, created new RIB Input devices, **and told the board to push future changes straight to the hub**.  Go check your Devices and see if you now have RIB Inputs.  You can select an input you have connected and see if the state changes from contact: open to closed — it should now react instantly rather than up to a second later.  The Input number in the device name matches the input numbers printed next to the screw terminals on the board.  So, "I3 printed" on the board is "RIB Input 3".  
15. If Open and Closed are reversed, you can choose to reverse that in the Device settings.  
16. Also, in the Device settings you can and should change the Device Name from "RIB Input 1" to "Front Door".  Also, if you end up not using all 8, you can just delete the unused RIB Input devices.  If you ever want them back, just go back to the RIB App, don't change anything, and click "Done" and the missing RIB Inputs will be restored.  
17.  Now, you can go to your Amazon Alexa App and add these inputs and update.  Then Ask Alexa to discover new devices.  Then you can create routines to do speech announcements when the door is open.  Also, you can use the Notifications App in Hubitat to give you phone notifications whenever a door is opened.  Or turn lights on when you enter.  Or whatever...  

**If your hub's IP address ever changes**, open the app and click Done again — that re-writes the push URLs on the board with the new address.

## Firmware

"Input Link URL" is what makes the push work, and it arrived in firmware **V3.1.2776 (Aug 2023)**. **V3.1.6611** is recommended, because later releases fixed real bugs in this exact feature (POST/PUT bodies not saving in V3.1.6312, a crash with HTTPS enabled in V3.1.5182), and because V3.1.6553 immediately before it is known to crash.

The app shows you your board's current version on its settings page, along with links to the downloads, and warns you in red if the board is too old to push events. You can also check it yourself with:

```bash
curl -s http://<BOARD_IP>/api/v2/config.cgi
```

Look for `"sw_ver"`. Downloads are at [Dingtian support → Download](https://www.dingtian-tech.com/en_us/support.html?tab=download), or directly: <http://www.dingtian-tech.com/sdk/relay_upgrade_tool.zip>. The upgrade path depends on where you are starting from:

- Already on **v3.1.4897 or later** → flash `ESP32_8ch_v3_1_6611.dtf2` directly
- Older than **v3.1.4897** → flash `ESP32_8ch_v3_1_4897.dtf` **first**, then the `.dtf2`

Use `ota_tool_v4_1_1.exe` from Dingtian's upgrade tool. Turn off your PC firewall while doing it, use a wired connection, and make sure power is stable.

## Troubleshooting

- **Inputs only update every few minutes.** The push isn't arriving, and you're seeing the reconcile sweep. Check the app's settings page for a red error message. The most likely causes are OAuth not being enabled (step 8) or firmware older than V3.1.2776.
- **The app reports the board didn't store the push URL intact.** The URL the hub needs is about 70 characters and some firmware may truncate it. This is worth reporting — note your `sw_ver` and the length the app says it got back.
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

### Should inputs be allowed to switch relays on the board?

Almost always no, and the app turns it off by default. Boards ship with input 1 wired to relay 1,
input 2 to relay 2, and so on. With door sensors on the inputs that means every door event clicks a
relay, which is baffling to debug and makes the relays unusable for anything else.

The one case where you *should* leave it on is a **physical switch wired to an input, driving a
relay directly**. That light then keeps working while the hub is rebooting, updating, or dead —
the same reasoning behind using the board's own timer for sprinklers rather than a Hubitat timer.
A wall switch that stops working when a computer is down is a bad wall switch.

So: leave the toggle on unless you deliberately wired a switch to an input.

## Controlling Relays from Hubitat

The app now creates a **RIB Relay Switch** device for each relay on the board automatically. You no
longer need to add an `httpGetSwitch` device and paste URLs into it by hand — the app knows the
board's address, builds the URLs itself, and rewrites them on every relay device if the address ever
changes. Import the driver alongside the contact sensor one:

```
https://raw.githubusercontent.com/TonyMajorDev/RelayInputBoard/event-driven/RelayBoard-relay-switch-driver.groovy
```

The ON and OFF URLs are shown on each device page under Current States as `onUrl` and `offUrl`, so
you can see exactly what is being sent. They are read-only on purpose — the app owns them, and
letting them be edited is how they would drift out of sync with the board's address.

If you'd rather keep your existing hand-built switch devices, turn off "Create a switch device for
each relay" in the app and nothing will be created.

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

The app never assumes a command worked. After sending one it reads the board's real relay status
back after 1 second, and again at 5 seconds if it still doesn't match. Devices are always set to
what the board actually reports, never to what was requested — so a relay that failed to switch
shows the truth. If it still hasn't changed after 5 seconds, an error is logged naming the relay.

Relay states are also refreshed on the same reconcile sweep as the inputs, which is what catches an
auto-off timer expiring or someone switching a relay from the board's own web page.

Here is an example of how I control a light (same board as the door sensors, `.30`): 

On URI: "http://192.168.50.30/relay_cgi.cgi?type=0&relay=6&on=1&time=0&pwd=0&"

Off URI: "http://192.168.50.30/relay_cgi.cgi?type=0&relay=6&on=0&time=0&pwd=0&"

Here is an example of a Sprinkler station control* (a second, outputs-only board at `.101`):

On URI: "http://192.168.50.101/relay_cgi.cgi?type=2&relay=0&on=1&time=1800&pwd=0&"

Off URI: "http://192.168.50.101/relay_cgi.cgi?type=0&relay=0&on=0&time=0&pwd=0&"

* Notice that the On URI has an added time parameter of 1800 (or 30 minutes).  This makes sure that if the off command is not received, the sprinklers will never accidentally stay on! 

This is worth spelling out, because it is the single most important safety property of the whole setup: `type=2` with a `time=` value runs the timer **on the relay board itself**, not on the hub. Once the board has accepted that command, the relay will shut off after 1800 seconds even if the hub reboots, the app crashes, the network drops, or the "off" command never arrives. Any future relay support added to this app must preserve that — a Hubitat-side `runIn()` timer would be strictly worse, because it fails in exactly the situations you most need it to work.
