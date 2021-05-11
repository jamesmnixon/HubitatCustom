import java.util.concurrent.* // Available (allow-listed) concurrency classes: ConcurrentHashMap, ConcurrentLinkedQueue, Semaphore, SynchronousQueue
import groovy.transform.Field



metadata {
	definition (name: "Simple Ring G2 Motion Sensor Driver",namespace: "jvm", author: "jvm") {
		// capability "Configuration"
		capability "Initialize"
		capability "Refresh"
		
	
		capability "Battery"
        capability "MotionSensor"
        capability "TamperAlert"
        capability "Sensor"
		
		command "identify"
		
		
		command "setParameter",[[name:"parameterNumber",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]],
					[name:"value",type:"NUMBER", description:"Parameter Value", constraints:["NUMBER"]]
					]	
    }
	
    preferences 
	{	
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
		input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true
    }	
}

void identify()
{
		List<Map<String, Short>> indicators = [
		[indicatorId:0x50, propertyId:0x03, value:0x08], 
		[indicatorId:0x50, propertyId:0x04, value:0x03],  
		[indicatorId:0x50, propertyId:0x05, value:0x06]
		]
		sendToDevice(secure(zwave.indicatorV3.indicatorSet(indicatorCount:3 , value:0, indicatorValues: indicators )))
}

///////////////////////////////////////////////////////////////////////
//////        Install, Configure, Initialize, and Refresh       ///////
///////////////////////////////////////////////////////////////////////
void resetState()
{
	state.clear()
}


void initialize( )
{
    refresh()    
}


void refresh()
{
	log.debug "Sending refresh events"
	sendToDevice(zwave.notificationV8.notificationGet(v1AlarmType:0, event: 3, notificationType: 7) )
	sendToDevice(zwave.notificationV8.notificationGet(v1AlarmType:0, event: 8, notificationType: 7) )
	// batteryGet()
}


//////////////////////////////////////////////////////////////////////
//////        Handle Notifications        ///////
//////////////////////////////////////////////////////////////////////

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd )
{
	com.hubitat.app.DeviceWrapper targetDevice = device

	if (logEnable) log.debug "Received NotificationReport: ${cmd}"
	
	Map events =
		[ 	1:[ // Smoke
				0:[	
					1:[name:"smoke" , value:"clear", descriptionText:"Smoke detected (location provided) status Idle."],
					2:[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."]
					], 
				1:[name:"smoke" , value:"detected", descriptionText:"Smoke detected (location provided)."], 
				2:[name:"smoke" , value:"detected", descriptionText:"Smoke detected."]
				],
			2:[ // CO
				0:[
					1:[name:"carbonMonoxide" , value:"clear", descriptionText:"Smoke detector status Idle."],
					2:[name:"carbonMonoxide" , value:"clear", descriptionText:"Smoke detector status Idle."]					
					], 
				1:[name:"carbonMonoxide" , value:"detected", descriptionText:"Smoke detected (location provided)."], 
				2:[name:"carbonMonoxide" , value:"detected", descriptionText:"Smoke detected."]
				],				
			5:[ // Water
				0:[
					1:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
					2:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."]
				], 
				1:[name:"water" , value:"wet", descriptionText:"Water leak detected (location provided)."], 
				2:[name:"water" , value:"wet", descriptionText:"Water leak detected."]
				],
			6:[ // Access Control (Locks)
				0:[], 
				1:[name:"lock" , value:"locked", descriptionText:"Manual lock operation"], 
				2:[name:"lock" , value:"unlocked", descriptionText:"Manual unlock operation"], 
				3:[name:"lock" , value:"locked", descriptionText:"RF lock operation"], 
				4:[name:"lock" , value:"unlocked", descriptionText:"RF unlock operation"], 
				5:[name:"lock" , value:"locked", descriptionText:"Keypad lock operation"], 
				6:[name:"lock" , value:"unlocked", descriptionText:"Keypad unlock operation"], 
				11:[name:"lock" , value:"unknown", descriptionText:"Lock jammed"], 				
				254:[name:"lock" , value:"unknown", descriptionText:"Lock in unknown state"]
				],
			7:[ // Home Security
				0:[		3:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
						4:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
						9:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
						7:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."],
						8:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."] 
					], 
				3:[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"], 
				4:[name:"tamper" , value:"detected", descriptionText:"Tampering, invalid code."], 
				7:[name:"motion" , value:"active", descriptionText:"Motion detected (location provided)."],
				8:[name:"motion" , value:"active", descriptionText:"Motion detected."],
				9:[name:"tamper" , value:"detected", descriptionText:"Tampering, device moved"]
				],
			14:[ // Siren
				0:[
					1:[name:"alarm" , value:"off", descriptionText:"Alarm Siren Off."]
					], 
				1:[name:"alarm" , value:"siren", descriptionText:"Alarm Siren On."]
				], 
			15:[ // Water Valve
				0:[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Valve Operation."], 
				1:[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Master Valve Operation."] 
				], 
			18:[ // Gas Detector
				0:[		1:[name:"naturalGas" , value:"clear", descriptionText:"Combustible Gas state cleared (location provided)."],
						2:[name:"naturalGas" , value:"clear", descriptionText:"Combustible Gas state cleared."],
						3:[name:"naturalGas" , value:"clear", descriptionText:"Toxic gas state cleared (location provided)."],
						4:[name:"naturalGas" , value:"clear", descriptionText:"Toxic gas state cleared."] 
					], 
				1:[name:"naturalGas" , value:"detected", descriptionText:"Combustible Gas Detected (location provided)"], 
				2:[name:"naturalGas" , value:"detected", descriptionText:"Combustible Gas Detected"], 
				3:[name:"naturalGas" , value:"detected", descriptionText:"Toxic Gas detected (location provided)."],
				4:[name:"naturalGas" , value:"detected", descriptionText:"Toxic Gas detected."]
				],				
			22:[ // Presence
				0:[
					1:[name:"presence" , value:"not present", descriptionText:"Home not occupied"],
					2:[name:"presence" , value:"not present", descriptionText:"Home not occupied"]
					], 
				1:[name:"presence" , value:"present", descriptionText:"Home occupied (location provided)"],  
				2:[name:"presence" , value:"present", descriptionText:"Home occupied"]
				]
				
		].get(cmd.notificationType as Integer)?.get(cmd.event as Integer)
	
	if ( ! events ) { 
		if( logEnable ) log.debug "Device ${targetDevice.displayName}: Received an unhandled notifiation event ${cmd} for endpoint ${ep}." 
	} else 
	{ 
	
		if(cmd.eventParametersLength == 0)
		{
			if (targetDevice.hasAttribute(events.name)) { 
				targetDevice.sendEvent(events) 
			} else {
				log.warn "Device ${device.displayName}: Device missing attribute for notification event ${it}, notification report: ${cmd}."
			}
			
		} else 
		{
			Map clearMessage = events.get(cmd.eventParameter[0] as Integer)
			if (logEnable) log.debug "Clearing an notification state by sending event: ${clearMessage}"
			if (clearMessage) targetDevice.sendEvent(clearMessage)
		}
	}
}




//////////////////////////////////////////////////////////////////////
//////        Handle Battery Reports and Device Functions        ///////
////////////////////////////////////////////////////////////////////// 
void zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) 
{
	com.hubitat.app.DeviceWrapper targetDevice = device
	
	if (cmd.batteryLevel == 0xFF) {
		targetDevice.sendEvent ( name: "battery", value:1, unit: "%", descriptionText: "Low Battery Alert. Change now!")
	} else {
		targetDevice.sendEvent ( name: "battery", value:cmd.batteryLevel, unit: "%", descriptionText: "Battery level report.")
	}
}

void batteryGet() {
	sendToDevice(zwave.batteryV1.batteryGet())
}
//////////////////////////////////////////////////////////////////////
//////        Handle Basic Reports and Device Functions        ///////
////////////////////////////////////////////////////////////////////// 
com.hubitat.app.DeviceWrapper getTargetDeviceByEndPoint(Short ep = null )
{
	if (ep) { return getChildDevices().find{ (it.deviceNetworkId.split("-ep")[-1] as Short) == (ep as Short)}
	} else { return device }
}


///////////////////////////////////////////////////////////////////////////////////////
///////      Handle Update(), and Set, Get, and Process Parameter Values       ////////
/////////////////////////////////////////////////////////////////////////////////////// 

void updated()
{
}

		
//////////////////////////////////////////////////////////////////////
//////        Handle Supervision request and reports           ///////
////////////////////////////////////////////////////////////////////// 

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd) {
    hubitat.zwave.Command encapCmd = cmd.encapsulatedCommand(commandClassVersions)
    if (encapCmd) {
        zwaveEvent(encapCmd)
    }
    sendHubCommand(new hubitat.device.HubAction(zwaveSecureEncap(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0).format()), hubitat.device.Protocol.ZWAVE))
}


//////////////////////////////////////////////////////////////////////
//////                  Z-Wave Helper Functions                ///////
//////   Format messages, Send to Device, secure Messages      ///////
//////////////////////////////////////////////////////////////////////
Map getDefaultParseMap()
{
return [
	0x6C:1,	// Supervision
	0x71:8, // Notification
	0x80:1, // Battery
	]
}

//// Catch Event Not Otherwise Handled! /////

void zwaveEvent(hubitat.zwave.Command cmd) {
    log.warn "For ${device.displayName}, Received Z-Wave Message ${cmd} that is not handled by this driver."
}


////    Security Encapsulation   ////
void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand( parseMap, defaultParseMap )
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand) }
}


String secure( cmd){  return zwaveSecureEncap(cmd) }

////    Z-Wave Message Parsing   ////
void parse(String description) {
	hubitat.zwave.Command cmd = zwave.parse(description, defaultParseMap)
	if (cmd) { zwaveEvent(cmd) }

}

////    Z-Wave Message Sending to Hub  ////

void sendToDevice(hubitat.zwave.Command cmd) { sendHubCommand(new hubitat.device.HubAction(secure(cmd), hubitat.device.Protocol.ZWAVE)) }

void sendToDevice(String cmd) { sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE)) }
