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
 *  Sense Monitor
 *
 *  Author: Alan Brown
 *
 *  Date: 2020-12-02
 *
 * Credit where credit is due. This is derived from the node.js code https://github.com/brbeaird/sense-energy-node
 * Removing the need for a linux node server in this implementation
 * So far focussed on trying to to gather and store data from the https and web socket interfaces.
 *
 */
def driverVer() { return "0.0.3" }

import java.net.URI
import java.net.URLEncoder

metadata 
{
	definition (name: "Sense Monitor", namespace: "dalanbrown", author: "Alan Brown", vid: "generic-power") 
    {
		capability "Power Meter"
        capability "Energy Meter"
        capability "Actuator"
        capability "Sensor"

        attribute "lastUpdated", "string"
        attribute "deviceLocation", "string"
        attribute "deviceName", "string"
        attribute "deviceSN", "string"
        attribute "frequency", "string"
        attribute "voltage0", "string"
        attribute "voltage1", "string"
        attribute "gridPower", "string"
        attribute "netPower", "string"
        attribute "solarPower", "string"
        attribute "solarContribution", "string"
        attribute "energy", "string"
        attribute "power", "string"
        
        // FIXME: Probably should be state variables
        attribute "connected", "string"
        attribute "state", "string"
	    attribute "energyCost", "string"
        attribute "bridgeServer", "string"
        attribute "solarConfigured", "string"
        attribute "solarConnected", "string"
        attribute "deviceSN", "string"
        attribute "deviceName", "string"
        
		command "refresh"
        command "connect"
        command "disconnect"
	}
    
    preferences 
    {
        input "isDebugEnabled", "bool", required: false, title: "Debug Logs?", defaultValue: false
        input "email", "email", required: true, title: "Sense Authentication email"
        input "password", "password", required: true, title: "Sense Password"
        input "autoReconnect", "bool", required: false, title: "Reconnect to sense server on connection drop?", defaultValue: false
        input ("refreshRate", "enum", title: "Websocket Ping Interval (milli-seconds)", 
			       options: ["default", "15", "30", "45", "60", "120"], 
			       defaultValue: "default")
    }
}

def installed() 
{
    if (isDebugEnabled == null) {
		device.updateSetting("isDebugEnabled", [value:"false",type:"bool"])	
	}
	
	if (email == null) {
		device.updateSetting("email", [value:"",type:"email"])	
	}
	
    if (password == null) {
		device.updateSetting("password", [value:"",type:"password"])	
	}

	if (autoReconnect == null) {
		device.updateSetting("autoReconnect", [value:"false",type:"bool"])	
	}
    
    if (refreshRate == null) {
        device.updateSetting("refreshRate", [value:"default", type:"enum"])
    }

    if (settings.autoReconnect) connect()
}

def updated() 
{
    if (refreshRate == null) {
        device.updateSetting("refreshRate", [value:"default", type:"enum"])
    }
    
    if (settings.autoReconnect) connect()
}

def getAuthdata()
{
    if ((null == state.authdata) || (state.authdata.equals("")))
    {
        return null;
    }
    return parseJson(state.authdata)
}

def refresh()
{
    authdata = getAuthdata()
    if (null != authdata)
    {
        getDevices()
        getMonitorInfo()
        getTimeline()
        getDailyUsage(new Date(now()).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
    }
    updateStatus(authdata)
}

private def urlEncodeMap( Map mm ) 
{
    def encode = { 
		URLEncoder.encode( "$it".toString() ) 
	}
	
    return mm.collect { 
		encode(it.key) + '=' + encode(it.value) 
	}.join('&')
}

private def String getAPIURL()
{
    return 'https://api.sense.com/apiservice/api/v1/'
}

private def void setAttribute(String name, value, Map options = [:])
{
    if (!options.containsKey('isStateChange'))
    {
        options.isStateChange = false
    }
    if (!options.containsKey('displayed'))
    {
        options.displayed = false
    }
    options.name = name
    options.value = value
    
    sendEvent(options)
}

private def void clearAttribute(String name)
{
    setAttribute(name, "")
}

private def void updateStatus(authdata)
{
    if (null == authdata)
    {
        setAttribute("connected", false, [display: true, isStateChange: true])
        
        clearAttribute("state")
	    clearAttribute("energyCost")
        clearAttribute("bridgeServer")
        clearAttribute("solarConfigured")
        clearAttribute("solarConnected")
        
        clearAttribute("deviceSN")
        clearAttribute("deviceName")
        clearAttribute("frequency")
        clearAttribute("voltage0")
        clearAttribute("voltage1")
        clearAttribute("gridPower")
        clearAttribute("netPower")
        clearAttribute("solarPower")
        clearAttribute("solarContribution")
        clearAttribute("energy")
        clearAttribute("power")
    }
    else
    {
        setAttribute("connected", true, [display: true, isStateChange: true])

        setAttribute("state", authdata.monitors[0].attributes.state)
	    setAttribute("energyCost", authdata.monitors[0].attributes.cost, [unit: '₵', descriptionText: 'Fixme: i18n'])
        setAttribute("bridgeServer", authdata.bridge_server)
        setAttribute("solarConfigured", authdata.monitors[0].solar_configured)
        setAttribute("solarConnected", authdata.monitors[0].solar_connected)
        
        setAttribute("deviceSN", authdata.monitors[0].serial_number)
        setAttribute("deviceName", authdata.monitors[0].attributes.name)
    }
}

def void disconnect()
{
    // Shut down all web sockets
    closeWS()
    
    // flush out the authdata
    state.authdata = null
    updateStatus(null)
}

def void connect() 
{
    apiURL = getAPIURL()
    
    if (settings.email && settings.password)
    {
        if (settings.isDebugEnabled) log.debug("We have a username and password")
        if ((null != state.authdata) && (!state.authData.equals("")))
        {
            if (settings.isDebugEnabled) log.debug("We have saved authdata")
            authdata = parseJson(state.authdata)
            if (authdata.authorized)
            {
                if (settings.isDebugEnabled) log.debug("Already authenticated with sense.com - ${authdata.access_token}")
				refresh()
                return
            }
        }
        else
        {
            if (settings.isDebugEnabled) log.debug("Authenticating with sense.com")
        }
    }
    else
    {
        log.warn("Trying to connect to sense server without username/password - will not try")
        return
    }
    try
    {
        String data = urlEncodeMap([email: settings.email, password: settings.password])
        httpPost([uri: apiURL + "authenticate", textParser: true, contentType: "application/json", ignoreSSLIssues: true, body: data]) { resp ->
        
            if (resp.success)
            {
                // json returned here
                String json = resp.data.text
                if (settings.isDebugEnabled) log.debug(json)
               
                authdata = parseJson(json);
                if (authdata.authorized)
                {
                    if (settings.isDebugEnabled) log.debug("Authenticated")
                    
                    state.authdata = json
					refresh()
                    
                    // Now that we are authenticated - set up for realtime notifications
                    setupWS()
                }
                else
                {
                    log.error("Authentication failed! Check username/password and try again.");
                }
            }
            else if ((401 == resp.status) || (400 == resp.status))
            {
                log.error("Authentication failed! Check username/password and try again.");
            }
        }
    } 
    catch (Exception e) 
    {
        log.error("Authentication error: ${e.message}")
    }
}

def webSocketStatus(String message)
{
    // This method is called with any status messages from the web socket client connection (disconnections, errors during connect, etc)
    if (settings.isDebugEnabled) log.debug("sense monitor: webSocketStatus ${message}")
    
    if (message.equals("closing"))
    {
        // Disconnection
        disconnect()

        if (settings.autoReconnect)
        {
            // Perhaps wait some time to reconnect?
            // What if network is disconnected for some time? Back off and wait longer?
            runOnce(new Date(now() + 10000), connect)
        }
    }
}

def removeChildDevices(delete) 
{
	// def List<ChildDeviceWrapper> kids = getChildDevices()
	def kids = getChildDevices()
	
	kids.each { kid ->
		deleteChildDevice(kid.deviceNetworkId)
	}
}

def resolve(String msgStr)
{
    def Object msg = parseJson(msgStr)
    
    // Called for all json packets. httpGet and WS notifications.
    
    if (msg.containsKey('payload') && (false == msg.payload.authorized))
    {
        if (settings.isDebugEnabled) log.debug('Authentication failed. Trying to reauth...')
        
        refreshAuth();
    }
    else if ((msg.containsKey('type')) && (msg.type.equals("realtime_update")))
    {
        // Websocket data    
        if (msg.containsKey('payload'))
        {
            if (msg.payload.containsKey('devices'))
            {
                // Device refresh
            }

            setAttribute("frequency", String.format('%.2f', msg.payload.hz), [unit: 'Hz', descriptionText: 'Hertz', type: msg.type])
            setAttribute("voltage0", String.format('%.2f', msg.payload.voltage[0]), [unit: 'V', descriptionText: "Volts", type: msg.type])
            setAttribute("voltage1", String.format('%.2f', msg.payload.voltage[1]), [unit: 'V', descriptionText: "Volts", type: msg.type])
            setAttribute("gridPower", String.format('%d', msg.payload.grid_w), [unit: 'W', descriptionText: "Watts", type: msg.type])
            setAttribute("power", String.format('%d', msg.payload.grid_w), [unit: 'W', descriptionText: "Watts", type:msg.type])
            setAttribute("energy", String.format('%d', msg.payload.c), [unit: 'A', descriptionText: "Amps", type:msg.type])

            if ((msg.payload.containsKey('aux')) && (msg.payload.aux.containsKey('solar')))
            {
                setAttribute("netPower", String.format('%d', msg.payload.d_w), [unit: 'W', descriptionText: "Watts", type:msg.type])
                setAttribute("solarPower", String.format('%d', msg.payload.d_solar_w), [unit: 'W', descriptionText: "Watts", type:msg.type])
                setAttribute("solarContribution", String.format('%d', msg.payload.solar_pct), [unit: '%', descriptionText: "Percent", type:msg.type])
            }
        }
    }
    else if (msg.containsKey('type') && (msg.type.equals('local_callback') && (msg.called_from.equals('devices'))))
    {
        // So we have a list of devices - let's create child devices for each one.
        def childDevices = msg.payload
        
        childDevices.each { dev ->
            // do we have a child device with this id?
        	if (null == getChildDevice(dev.id))
        	{
        		def child = addChildDevice('Sense Monitored Child Device', dev.id, [isComponent: true, name: dev.make, label: dev.name]);
                
				child.sendEvent(name: 'icon', value: dev.icon)
        		child.sendEvent(name: 'location', value: dev.location)
        	}
		}
    }
    else
    {
    }
}

def parse(String message)
{
    // Websocket messages come here
    if (settings.isDebugEnabled) log.debug("sense monitor: message - ${message}")
    
    resolve(message)
}

private def getWSURL()
{
    def authdata = getAuthdata()
    
    if (authdata.authorized)
    {
        return "wss://clientrt.sense.com/monitors/${authdata.monitors[0].id}/realtimefeed?access_token=${authdata.access_token}"
    }
    
    return ""
}

private def void closeWS()
{
    if (settings.isDebugEnabled) log.debug("closing websocket")
    interfaces.webSocket.close()
}

private def void setupWS()
{
    def String wsurl = getWSURL()
    if (!wsurl.equals(""))
    {
        if (settings.isDebugEnabled) log.debug("Opening websocket to ${wsurl}")
        
        pingInterval = 30
        if (!settings.refreshRate.equals("default"))
        {
            pingInterval = settings.refreshRate;
        }
        interfaces.webSocket.connect(wsurl, pingInterval: pingInterval)
    }
}

private def senseCallback(hubitat.scheduling.AsyncResponse resp, Object data) 
{
    // resp is hubitat.scheduling.AsyncResponse
    if (200 == resp.getStatus())
    {
        if (settings.isDebugEnabled) log.debug("senseCallback: ${data.callerName} - ${resp.getData()}")
        
        // res.getData() returns a String 
        // res.getJson() return java.util.ArrayList if there is a json response
        // https://community.hubitat.com/t/async-http-calls/1694
        def packet = [type: 'local_callback', called_from: data.callerName, payload: resp.getJson()]
        resolve(groovy.json.JsonOutput.toJson(packet))
    }
    else if (401 == resp.getStatus())
    {
        log.error("${data.callerName}: ${data.callingURI} - Authentication failed!")
    }
    else
    {
        log.error("${data.callerName}: ${data.callingURI} - ${resp.getErrorMessage()}")
    }
}

private def void callSense(String url, String id) 
{   
    def authdata = getAuthdata()
    
    try 
    {
        def params =    [
                            uri: url, 
                            headers: ["Authorization": "bearer ${authdata.access_token}", "Accept": "application/json"] 
                        ]
        def data =      [
                            callingURI: url,
                            callerName: id
                        ]
        
        asynchttpGet("senseCallback", params, data) 
    } 
    catch (Exception e) 
    {
        log.error(e.message)
    }
}

private def void getDevices()
{
    def authdata = getAuthdata()
    def String apiURL = getAPIURL()
    
    callSense("${apiURL}app/monitors/${authdata.monitors[0].id}/devices", "devices")
}

private def void getMonitorInfo()
{
    def String apiURL = getAPIURL()
    def authdata = getAuthdata()
    
    callSense("${apiURL}app/monitors/${authdata.monitors[0].id}/status", "monitorInfo")
}

private def void getTimeline()
{
    def String apiURL = getAPIURL()
    def authdata = getAuthdata()
    
    callSense("${apiURL}users/${authdata.user_id}/timeline", "timeline")
}

private def void getDailyUsage(String startTime)
{
    def String apiURL = getAPIURL()
    def authdata = getAuthdata()
    
    callSense("${apiURL}app/history/trends?monitor_id=${authdata.monitors[0].id}&scale=DAY&start=${URLEncoder.encode(startTime)}", "dailyUsage")
}
