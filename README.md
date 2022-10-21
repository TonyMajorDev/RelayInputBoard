# Relay Input Board App for Hubitat

This is a Hubitat App and Driver set for the Ding-Tian Relay/Input Board.  I now use the relays for some low voltage lights and the inputs for my security system sensors, replacing an old traditional security system with it's ancient control panels.  I had another solution that had failed twice, requiring a few weeks of my system not working.  After that, I decided that I would see if I could use the inputs on the Ding-Tian Relay/Input board and after a few days, I got it to work with my Hubitat Elevation smart home hub.  I selected the Ding-Tian board because it has outstanding connectivity, available with wifi and ethernet!  The board has an integrated web server to configure everything.  The network settings are fantastic, including local name resolution!  Too bad Hubitat can use it.  

Technical documentation about the board can be found here: http://www.dingtian-tech.com/sdk/relay_sdk.zip
The hardware manufacturer is here: https://www.dingtian-tech.com/en_us/index.html

This can serve as a complete replacement for a traditional wired security system.  You can have up to 8 contact sensors.  But, you can also pool some, so you might make all the windows in a room a single alert. 

I will eventually record a video of how to set everything up.  

Until then, here is basically what you need to do.  

1. Buy the board on ebay.  It can take a few weeks to months to arrive!  
2. Plug in to your network and log in.  
3. Go to the Input Link Relay page and Set "Input Control Relay" to No, and set "Relay Feedback Momentary Input" to No.
4. I like to setup the board to use DHCP, so the router assigns an IP address.  I also like to set the "hostname" in the setting page, so I can browse to this admin console from the browser using http://hostname.local  Then reboot the board.  
5. Go to your router and manually assign the IP so that it NEVER changes.  Write down that IP. 
6. Go to http://hubitat.local and expand the Developer Tools.  Click on Apps Code, New App button, Import button, paste this url: https://raw.githubusercontent.com/TonyMajorDev/RelayInputBoard/main/RelayBoard-app.groovy
7. Click import button, Yes, overwrite, Click the Save button, Click "<< Apps code" to go back.  
8. Now we do the same for the driver.  Click on "Drivers code", Click New Driver button, Import button, paste this url: https://raw.githubusercontent.com/TonyMajorDev/RelayInputBoard/main/RelayBoard-contact-sensor-driver.groovy
9. Click the Import button, Yes, overwrite, Click the Save button, Click "<< Drivers code"
10. Now let's setup.  At this point, you should have your sensors wired into the input terminals on the Relay/Input board (RIB).  Now, above the develtoper tools, Click on "Apps" (Not "Apps code").  On the top right, click "Add User App".  Find and select "RIB App"
11. Now, we are about done.  Remember that IP Address you wrote down from step #5?  Type that into the "Relay Interface Board Address".  It is probably starts with "192.168."  
12. Click "Done" button! 
13. Now if everything worked, it communicated with the RIB and asked for the number of inputs and created new RIB Input devices.  Go check your Devices and see if you now have RIB Inputs.  You can select an input you have connected and see if the state changes from contact: open to closed.  The Input number in the device name matches the input numbers printed next to the screw terminals on the board.  So, "I3 printed" on the board is "RIB Input 3".  
14. If Open and Closed are reversed, you can choose to reverse that in the Device settings.  
15. Also, in the Device settings you can and should change the Device Name from "RIB Input 1" to "Front Door".  Also, if you end up not using all 8, you can just delete the unused RIB Input devices.  If you ever want them back, just go back to the RIB App, don't change anything, and click "Done" and the missing RIB Inputs will be restored.  
16.  Now, you can go to your Amazon Alexa App and add these inputs and update.  Then Ask Alexa to discover new devices.  Then you can create routines to do speech announcements when the door is open.  Also, you can use the Notifications App in Hubitat to give you phone notifications whenever a door is opened.  Or turn lights on when you enter.  Or whatever...  

