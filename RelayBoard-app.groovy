/**
 *  RIB App for Hubitat
 *
 *  Copyright 2023 ValkyrieTech LLC
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
 */

definition(
    name: "RIB App",
    namespace: "community",
    author: "ValkTech",
    description: "Relay Input Board for use with Hubitat",
    category: "Safety & Security",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page name: "mainPage", title: "", install: true, uninstall: true
}

def mainPage() {
    dynamicPage(name: "mainPage") {
    section("Relay Input Board Configuration") {
        input name: "ribAddress", type: "text", title: "Relay Interface Board Address", submitOnChange: true, required: true, defaultValue: "192.168.50.100" // local name resolution does not work on hubitat hub "homerelays.local" 
        input name: "pollFrequency", type: "number", title: "How often to check the sensors for a change (in seconds)", defaultValue: 1
        input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: false
    }
    }
}

def installed() {
    log.debug "installed(): Installing RIB Parent SmartApp"
    
    initialize()
    
    //updated()
}

def updated() {
    log.debug "updated(): Updating RIB SmartApp"
    //unschedule(pollStatus)
    initialize()

}

def uninstalled() {
    unschedule()
    state.remove('working')
    log.debug "uninstalled(): Uninstalling RIB SmartApp"
}

def discoverBoards() {
    byte[] rawBytes = [0x05, 0xAA]
    String stringBytes = hubitat.helper.HexUtils.byteArrayToHexString(rawBytes)
    def myHubAction = new hubitat.device.HubAction(stringBytes, 
                            hubitat.device.Protocol.LAN, 
                            [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, 
                                destinationAddress: "224.0.2.11:6000",
                                encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
    def response = sendHubCommand(myHubAction)
    log.debug "response from sendHubCommand ${response}"
}

def initialize() {

    //discoverBoards()

    // Example Response: &0&0&8&1&1&1&1&1&1&1&1&

    unschedule()

    state.working = 0
    
    int inputCount = 0

    try {
        httpGet("http://" + settings.ribAddress + "/input.cgi") { resp ->
            if (resp.success) {
                logDebug "initialize(): Response = " + resp.data
                logDebug "initialize(): Response[5] = " + (resp.data as String)[5]
                inputCount = (resp.data as String)[5] as int  //TODO: this won't work for a 2 digit number of inputs so the 16 and 32 boards won't work -- fix it
            }
            else {
                if (resp.data) logDebug "initialize(): Failed to get Input Count ${resp.data}"
            }
        }
    } catch (Exception e) {
        log.warn "initialize(): Call failed: ${e.message}"
    }

    // loop through the input contacts and create a contact device for each
    for(int i = 1; i<=inputCount; i++) {
        def contactName = "RIBContact-" + Integer.toString(i) + "_${app.id}"
	    logDebug "initialize(): adding driver = " + contactName
        
        def contactDev = getChildDevice(contactName)
	    if(!contactDev) contactDev = addChildDevice("community", "RIB Contact Sensor", contactName, null, [name: "RIB Input " + Integer.toString(i), inputNumber: thisName])
    }

    state.working = 0
    
    //schedule("0/" + pollFrequency + " * * ? * *"   "*/6 * * * * *", poll) // once a second
    //runIn(pollFrequency, poll)
    
    //        def t = refreshInterval == 1 ? '*' : new Date().getSeconds() % refreshInterval
        //unschedule(poll)
        schedule("*/${pollFrequency} * * ? * * *", poll, [overwrite: false])  // usually about once a second
        //state.refreshInterval = refreshInterval
        logDebug "Setting update frequency to every ${pollFrequency} second(s)"
    
}



def poll() {



    if (state.working > 0) {
        state.working = state.working - 1
    } else if (state.working < 0) {
        state.working = 0
    } else {
        state.working = 5        

        def requestParams = [ uri: "http://" + settings.ribAddress + "/input.cgi" ]
    
        logDebug "poll(): $requestParams"
	    
        asynchttpGet("pollHandler", requestParams)
    }


}


def pollHandler(resp, data) {
	if ((resp.getStatus() == 200 || resp.getStatus() == 207) && resp.data[0] == '&') {
		doPoll(resp.data)
     
    } else {
		log.error "RIB did not return data: $resp"
        
        //unschedule()
        
        // take a break, then restart...   
        
        //def startSeconds = new Date().getSeconds() + 10  // restart in 10 seconds
        
        //schedule("${startSeconds}/${pollFrequency} * * ? * * *", poll, [overwrite: false])  // usually about once a second
	}
    
    state.working = 0
}

def doPoll(response) {
    
    // Example Response: &0&0&8&1&1&1&1&1&1&1&1&
    // This is 8 relays and all are High/Open

    //TODO: check the response length before proceeding! 

    logDebug "doPoll(): Response = $response"

    def devices = getAllChildDevices()

    for (aDevice in devices)
    {
        int inputNum = (aDevice.deviceNetworkId as String)[11] as int

        def inputState = response[5+(2*inputNum)]
        
        if (inputState == "1") 
            aDevice.isOpen()
        else
            aDevice.isClosed()

        logDebug "doPoll(): Device = $aDevice, " + inputState
        
    }
}

private logDebug(msg) {
  if (settings?.debugOutput || settings?.debugOutput == null) {
    log.debug "$msg"
  }
}





