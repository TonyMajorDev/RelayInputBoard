/**
 *  RIB Contact Sensor
 *
 *  Copyright 2022 ValkyrieTech LLC
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
metadata {
  definition (name: "RIB Contact Sensor", namespace: "community", author: "ValkTech", vid: "generic-contact") {
    capability "Contact Sensor"
    capability "Sensor"
  }

  preferences {
    input name: "normalState", type: "enum", title: "Normal State",
      options: ["Normally Closed", "Normally Open"],
      defaultValue: "Normally Open",
      description: "Most door & window sensors are Normally Open (NO), meaning that the circuit closes when the door/window is closed. To reverse this logic, select Normally Closed (NC)."
  }

}

def initialized()
{
    state.currentState = ""
    state.newState = ""
}

def isClosed() {
   
    if (normalState == "Normally Closed")
    state.newState = "open"
  else 
    state.newState = "closed"
    
  if (state.newState != state.currentState) {
      statusChange(state.newState)
  }
}

def isOpen() {
  if (normalState == "Normally Closed")
    state.newState = "closed"
  else
    state.newState = "open"

  if (state.newState != state.currentState) {
    statusChange(state.newState)
  }
}

// send out event to notify of a state change! 
def statusChange(stateStr) {  
  state.currentState = stateStr
  sendEvent(name: "contact", value: stateStr)
  log.info "$device is $stateStr"
}
