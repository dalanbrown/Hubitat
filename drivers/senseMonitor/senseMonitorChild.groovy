/**
 *  Copyright 2020 
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
 *  My Sense Connector
 *
 *  Author: Alan Brown
 *
 *  Date: 2020-12-02
 */

def driverVer() { return "0.0.3" }

metadata {
	definition (name: "Sense Monitored Child Device", namespace: "dalanbrown", author: "Alan Brown", vid: "generic-power")
	{
        capability "Energy Meter"
        capability "Power Meter"
        capability "Refresh"
	}

	tiles 
	{
        valueTile("power", "device.power", decoration: "flat", width: 2, height: 2) 
		{
			state "default", label:'${currentValue} W'
	    }
        valueTile("energy", "device.energy", decoration: "flat", width: 2, height: 2) 
		{
			state "default", label:'${currentValue} kWh'
	    }
	}
}
