com.hubitat.app.DeviceWrapper getTargetDeviceByEndPoint(ep = null ) {
  // assumes Endpoint numbering for the child device ends in  -epXXX. E.g.,  98-ep001
	if (ep) { 
		return getChildDevices().find{ (it.deviceNetworkId.split("-ep")[-1] as Integer) == (ep as Integer)}
	} else { 
		return device 
	}
}


void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, ep = null )
{
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep) // returns root device if ep = null 

	if (logEnable) log.debug "Device ${targetDevice}: Received NotificationReport: ${cmd}"
	
	// Map of all the Z-Wave Notifications that are 'meaningful' in Hubitat.
	Map events =
		[ 	1:[ // Smoke
				0:[	
					1:[name:"smoke" , value:"clear", descriptionText:"Smoke detected (location provided) status Idle."],
					2:[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."],
					4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					7:[name:"consumableStatus" , value:"good", descriptionText:"Periodic Maintenance Not Due"],				
					8:[name:"consumableStatus" , value:"good", descriptionText:"No Dust in device - clear."],
					], 
				1:[name:"smoke" , value:"detected", descriptionText:"Smoke detected (location provided)."], 
				2:[name:"smoke" , value:"detected", descriptionText:"Smoke detected."],
				4:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required."],				
				5:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."],				
				7:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."],				
				8:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."],
				],
			2:[ // CO
				0:[
					1:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],
					2:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],	
					4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					7:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance required cleared, periodic inspection."],				
					], 
				1:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected (location provided)."], 
				2:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."]
				],
			2:[ // CO2
				0:[
					1:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."], // Needs custom attribute 'carbonDioxideDetected'
					2:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],	
					4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					7:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance required cleared, periodic inspection."],				
					], 
				1:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected (location provided)."], // Needs custom attribute 'carbonDioxideDetected'
				2:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."]
				],					
			5:[ // Water
				0:[
					1:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
					2:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
					5:[name:"filterStatus " , value:"normal", descriptionText:"Water filter good."],				

				], 
				1:[name:"water" , value:"wet", descriptionText:"Water leak detected (location provided)."], 
				2:[name:"water" , value:"wet", descriptionText:"Water leak detected."],
				5:[name:"filterStatus " , value:"replace", descriptionText:"Replace water filter (End-of-Life)."],				

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
				0:[	 // These events "clear" a sensor.	
						1:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed (location provided)"], 
						2:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed"], 					
						3:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
						4:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
						5:[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected (location provided)"], // glass Breakage !
						6:[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected"], 	 // glass Breakage					
						7:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."],
						8:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."],
						9:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
						
					], 
				1:[name:"contact" , value:"open", descriptionText:"Contact sensor, open (location provided)"], 	
				2:[name:"contact" , value:"open", descriptionText:"Contact sensor, open"], 					
				3:[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"], 
				4:[name:"tamper" , value:"detected", descriptionText:"Tampering, invalid code."], 
				5:[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected (location provided)"], 
				6:[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected"], 				
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
	} else { 
	
		if ((cmd.eventParametersLength as Integer) == 0) {
			if (targetDevice.hasAttribute(events.name)) { 
				targetDevice.sendEvent(events) 
			} else {
				log.warn "Device ${targetDevice.displayName}: Device missing attribute for notification event ${events}, device report: ${cmd}."
			}
			
		} else if ((cmd.eventParametersLength as Integer) == 1) {
			Map clearMessage = events.get(cmd.eventParameter[0] as Integer)
			if (logEnable) log.debug "Clearing an notification state by sending event: ${clearMessage}"
			if (clearMessage && (targetDevice.hasAttribute(clearMessage.name)) ) { targetDevice.sendEvent(clearMessage) }
		} else {
			log.error "Device ${targetDevice.displayName}: Driver Error - generated a Notification with an eventParametersLength > 1 from device report ${cmd}. Expected zero length eventParameter[0] field." 
		}
	}
}
