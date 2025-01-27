import java.util.concurrent.* // Available (allow-listed) concurrency classes: ConcurrentHashMap, ConcurrentLinkedQueue, Semaphore, SynchronousQueue
import groovy.transform.Field

@Field static Integer dataRecordFormatVersion = 1
@Field static ConcurrentHashMap globalDataStorage = new ConcurrentHashMap(64)

metadata {
	definition (name: "Any Z-Wave Switch Driver v1.1.9",namespace: "jvm", author: "jvm") {
		capability "Initialize"
		capability "Refresh"

		capability "Actuator"
		capability "Switch"
		// capability "SwitchLevel"
		
        // capability "Sensor"				
        // capability "MotionSensor"
        // capability "TamperAlert"
		// capability "WaterSensor"
		// capability "ContactSensor"
		// capability "ShockSensor"		// Use this for glass breakage
		// capability "IllumanceMeasurement"
		// capability "LiquidFlowRate"
		// attribute "carbonDioxideDetected"
		
		capability "EnergyMeter"
        capability "PowerMeter"
		capability "VoltageMeasurement"
        capability "CurrentMeter"
		attribute "energyConsumed", "number" 	// Custom Attribute for meter devices supporting energy consumption. Comment out if not wanted.
		// attribute "powerFactor", "number"	// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		// attribute "pulseCount", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		// attribute "reactiveCurrent", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		// attribute "reactivePower", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		
		// capability "Battery"

		// capability "Consumable" 		// For smoke, CO, CO2 alarms that report their end-of-life
		// capability "FilterStatus" 	// For water filters that report status of filter
		
		capability "PushableButton"
		capability "HoldableButton"
		capability "ReleasableButton"
		capability "DoubleTapableButton"	
		attribute "multiTapButton", "number"

		command "identify" // implements the Z-Wave Plus identify function which can flash device indicators.
		command "resetDriver" // deletes the stored state information
							
        command "multiTap", [[name:"button",type:"NUMBER", description:"Button Number", constraints:["NUMBER"]],
					[name:"taps",type:"NUMBER", description:"Tap count", constraints:["NUMBER"]]]	

		command "setParameter",[[name:"parameterNumber",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]],
					[name:"value",type:"NUMBER", description:"Parameter Value", constraints:["NUMBER"]]
					]	

		// Following Command is to help create a new data record to be added to deviceDatabase
        command "logDataRecord"

    }
	
	preferences 
	{	
        input name: "showParameterInputs", type: "bool", title: "Show Parameter Value Input Controls", defaultValue: false    
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
		input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true
		if (showParameterInputs) {
			getParameterValuesFromDevice()
			deviceInputs?.each{key, value -> input value}
        }
    }	
}
/////////////////////////////////////////////////////////////////////////
//////        Create and Manage Child Devices for Endpoints       ///////
/////////////////////////////////////////////////////////////////////////

void deleteUnwantedChildDevices()
{	
	// Delete child devices that don't use the proper network ID form (parent ID, followed by "-ep" followed by endpoint number).
	getChildDevices()?.each
	{ child ->	
	
		List childNetIdComponents = child.deviceNetworkId.split("-ep")
		if ((thisDeviceDataRecord.endpoints.containsKey(childNetIdComponents[1] as Integer)) && (childNetIdComponents[0] == device.deviceNetworkId)) {
			return
		} else {
			deleteChildDevice(child.deviceNetworkId)
		}			
	}
}

void createChildDevices()
{	
	thisDeviceDataRecord.endpoints.findAll{k, v -> (k != 0)}.each
	{ ep, value ->
		String childNetworkId = "${device.deviceNetworkId}-ep${"${ep}".padLeft(3, "0") }"
		com.hubitat.app.DeviceWrapper cd = getChildDevice(childNetworkId)
		if (cd.is( null )) {
			log.info "Device ${device.displayName}: creating child device: ${childNetworkId} with driver ${value.driver.type} and namespace: ${value.driver.namespace}."
			
			addChildDevice(value.driver.namespace, value.driver.type, childNetworkId, [name: value.driver.childName ?:"${device.displayName}-ep${ep}", isComponent: false])
		} 
	}
}
/////////////////////////////////////////////////////////////////

void identify() {
	log.warn "Device ${device.displayName}: The 'identify' function is experimental and only works for Zwave Plus Version 2 or greater devices!"
	// Identify function supported by Zwave Plus Version 2 and greater devices!
		List<Map<String, Short>> indicators = [
			[indicatorId:0x50, propertyId:0x03, value:0x08], 
			[indicatorId:0x50, propertyId:0x04, value:0x03],  
			[indicatorId:0x50, propertyId:0x05, value:0x06]
		]
		sendUnsupervised(zwave.indicatorV3.indicatorSet(indicatorCount:3 , value:0, indicatorValues: indicators ))
}


Map reparseDeviceData(deviceData = null )
{
	// When data is stored in the state.deviceRecord it can lose its original data types, so need to restore after reading the data froms state.
	// This is only done during the startup / initialize routine and results are stored in a global variable, so it is only done for the first device of a particular model.

	if (deviceData.is( null )) return null
	Map reparsed = [formatVersion: null , fingerprints: null , classVersions: null ,endpoints: null , deviceInputs: null ]

	reparsed.formatVersion = deviceData.formatVersion as Integer
	
	if (deviceData.endpoints) {
		reparsed.endpoints = deviceData.endpoints.collectEntries{k, v -> [(k as Integer), (v)] }
	} else {
		List<Integer> endpoint0Classes = getDataValue("inClusters")?.split(",").collect{ hexStrToUnsignedInt(it) as Integer }
						endpoint0Classes += getDataValue("secureInClusters")?.split(",").collect{ hexStrToUnsignedInt(it) as Integer }
		if (endpoint0Classes.contains(0x60))
			{
				log.error "Device ${device.displayName}: Error in function reparseDeviceData. Missing endpoint data for a multi-endpoint device. This usually occurs if there is a locally stored data record which does not properly specify the endpoint data. This device may still function, but only for the root device."
			}
		reparsed.endpoints = [0:[classes:(endpoint0Classes)]]
	}
	
	reparsed.deviceInputs = deviceData.deviceInputs?.collectEntries{ k, v -> [(k as Integer), (v)] }
	reparsed.fingerprints = deviceData.fingerprints?.collect{ it -> [manufacturer:(it.manufacturer as Integer), deviceId:(it.deviceId as Integer),  deviceType:(it.deviceType as Integer), name:(it.name)] }
	if (deviceData.classVersions) reparsed.classVersions = deviceData.classVersions?.collectEntries{ k, v -> [(k as Integer), (v as Integer)] }
	if (logEnable) "Device ${device.displayName}: Reparsed data is ${reparsed}"
	return reparsed
}

ConcurrentHashMap getDataRecordByProduct()
{
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2)
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2)
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2)
	String productKey = "${manufacturer}:${deviceType}:${deviceID}"
	return globalDataStorage.get(productKey, new ConcurrentHashMap())
}

void resetDriver() {
	state.clear()
}

void showglobalDataRecord() {
	ConcurrentHashMap dataRecord = getDataRecordByProduct()
	log.info "Data record in global storage is ${dataRecord}."
}

void clearLeftoverStates() {
	List<String> allowed = ["deviceRecord"] 
	
	// Can't modify state from within state.each{}, so first collect what is unwanted, then remove in a separate unwanted.each
	List<String> unwanted = state.collect{ 
			if (allowed.contains( it.key as String)) return
			return it.key
		}.each{state.remove( it ) }
}

void sendInitialCommand() {
	// If a device uses 'Supervision', then following a restart, code doesn't know the last sessionID that was sent to 
	// the device, so to reset that, send a command twice at startup.
	if (device.hasAttribute("switch") && (device.currentValue("switch") == "off")) {
		sendZwaveValue(value:0)
		sendZwaveValue(value:0)
	} else if ( device.hasAttribute("switch") && (device.currentValue("switch") == "on")) {
		if (device.hasAttribute("level")) { 
			sendZwaveValue(value:(device.currentValue("level") as Integer ))
			sendZwaveValue(value:(device.currentValue("level") as Integer ))
		} else {
			sendZwaveValue(value:99)
			sendZwaveValue(value:99)
		}
	}
}

void initialize()
{
	// By default, hide the parameter settings inputs since displaying them forces a refresh of all values the first time they are shown and is time consuming!
    device.updateSetting("showParameterInputs",[value:"false",type:"bool"])

	clearLeftoverStates()
	log.info "Device ${device.displayName}: Initializing."

	/////////////////////////////////////////////////////////////////////////////////////
	///                      Don't Alter this code block code!                        ///
	/// This code manages the different ways in which the device record may be stored ///
	///             - i.e., locally or from the openSmartHouse database               ///
	/////////////////////////////////////////////////////////////////////////////////////
	// If the format of the device record has changed, delete any locally stored data and recreate 
	if ((state.deviceRecord?.formatVersion as Integer) != dataRecordFormatVersion) state.remove("deviceRecord")
	
	Map localDataRecord = getLocallyStoredDataRecord()
	if (localDataRecord && (localDataRecord.formatVersion != dataRecordFormatVersion)) {
		log.warn "Device ${device.displayName}: Locally stored data record has wrong version number and will be ignored. Obtaining data from openSmartHouse instead. Locally stored record is: ${localDataRecord.inspect()}."
		}
		
	if (localDataRecord && (localDataRecord.formatVersion == dataRecordFormatVersion)){
		state.remove("deviceRecord") // If a device data record was added to the database, delete if it was previously from openSmartHouse.
		dataRecordByProduct.deviceRecord = reparseDeviceData(localDataRecord) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord && dataRecordByProduct.deviceRecord.is( null ) ) { 
		// Put in the Global ConcurrentHashMap if it exist locally.
		dataRecordByProduct.deviceRecord = reparseDeviceData(state.deviceRecord) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord.is( null ) && dataRecordByProduct.deviceRecord ) {
		// Data record doesn't exist in state, but it is in the concurrentHashMap - So store in state rather than re-retrieve
		state.deviceRecord = dataRecordByProduct.deviceRecord
	} else if ( state.deviceRecord.is( null )) {
		// Data record doesn't exist - get it and store in the global data record
		Map createdRecord = createDeviceDataRecord() 
		state.deviceRecord = createdRecord
		if (createdRecord) dataRecordByProduct.deviceRecord = reparseDeviceData(createdRecord)
	}
	///////////////////////////////////////////////////////////////////////////////////
	//////////          Done with Device Data Record Management      //////////////////
	///////////////////////////////////////////////////////////////////////////////////	
	
	// Create child devices if this is a multi-channel device.
	if (getThisDeviceDataRecord().classVersions?.containsKey(0x60)) {
		deleteUnwantedChildDevices()
		createChildDevices()
		}

	if (getThisDeviceDataRecord().classVersions?.containsKey(0x5B)) sendUnsupervised(zwave.centralSceneV3.centralSceneSupportedGet())
	if (getThisDeviceDataRecord().classVersions?.containsKey(0x6C)) sendInitialCommand()
	
	if (txtEnable) log.info "Device ${device.displayName}: Refreshing device data."
	refresh()  
	
	if (txtEnable) log.info "Device ${device.displayName}: Done Initializing."

}

Map getLocallyStoredDataRecord() {
	Integer mfr = getDataValue("manufacturer")?.toInteger()
	Integer Id = getDataValue("deviceId")?.toInteger()
	Integer Type = getDataValue("deviceType")?.toInteger()
	
	return deviceDatabase.find{ DBentry ->
					DBentry?.fingerprints.find{ subElement-> 
							((subElement.manufacturer == mfr) && (subElement.deviceId == Id) && (subElement.deviceType == Type )) 
						}
				}
}
//////////// Get Inputs //////////////
Map getThisDeviceDataRecord(){
	dataRecordByProduct?.deviceRecord
}

Map getDeviceInputs()  { 
	Map returnMe = dataRecordByProduct?.deviceRecord?.deviceInputs.sort({it.key})
	if (logEnable && returnMe.is( null ) ) log.warn "Device ${device.displayName}: Device has no inputs. Check if device was initialized. returnMe is ${returnMe}."
	return returnMe
}

Map filteredDeviceInputs() {
	if (advancedEnable) { 
		return getDeviceInputs()?.sort()
	} else  { // Just show the basic items
		return 	getDeviceInputs()?.findAll { it.value.category != "advanced" }?.sort()
	}
}

//////////////////////////////////////////////////////////////////////
//////        Handle Basic Reports and Device Functions        ///////
////////////////////////////////////////////////////////////////////// 

void zwaveEvent(hubitat.zwave.commands.switchbinaryv2.SwitchBinaryReport cmd, ep = null)
{
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)

	if (! targetDevice.hasAttribute("switch")) log.error "Device ${targetDevice.displayName}: received a Switch Binary Report for a device that does not have a switch attribute."
	
	String priorSwitchState = targetDevice.currentValue("switch")
	String newSwitchState = ((cmd.value > 0) ? "on" : "off")
	
    if (priorSwitchState != newSwitchState) // Only send the state report if there is a change in switch state!
	{
		targetDevice.sendEvent(	name: "switch", value: newSwitchState, descriptionText: "Switch set", type: "physical")
		if (txtEnable) log.info "Device ${targetDevice.displayName} set to ${newSwitchState}."
	}
}

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, ep = null) { processSwitchReport(cmd, ep) }
void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd, ep = null) { processSwitchReport(cmd, ep) }
void processSwitchReport(cmd, ep)
{
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)

	if (targetDevice.hasAttribute("position")) 
	{ 
		targetDevice.sendEvent( name: "position", value: (cmd.value == 99 ? 100 : cmd.value) , unit: "%", descriptionText: "Position set", type: "physical" )
	}
	if (targetDevice.hasAttribute("windowShade"))
	{
		String positionDescription
		switch (cmd.value as Integer)
		{
			case 0:  positionDescription = "closed" ; break
			case 99:  positionDescription = "open" ; break
			default : positionDescription = "partially open" ; break
		}
		targetDevice.sendEvent( name: "windowShade", value: positionDescription, descriptionText: "Window Shade position set.", type: "physical" )	
	}

	if (targetDevice.hasAttribute("level") || targetDevice.hasAttribute("switch") ) // Switch or a fan
	{
		Integer targetLevel = 0

		if (cmd.hasProperty("targetValue")) //  Consider duration and target, but only when both are present and in transition with duration > 0 
		{
			targetLevel = cmd.targetValue ?: cmd.value
		} else {
			targetLevel = cmd.value
		}

		String priorSwitchState = targetDevice.currentValue("switch")
		String newSwitchState = ((targetLevel != 0) ? "on" : "off")
		Integer priorLevel = targetDevice.currentValue("level")

		if ((targetLevel == 99) && (priorLevel == 100)) targetLevel = 100

		if (targetDevice.hasAttribute("switch"))
		{
			targetDevice.sendEvent(	name: "switch", value: newSwitchState, descriptionText: "Switch state set", type: "physical" )
			if (txtEnable) log.info "Device ${targetDevice.displayName} set to ${newSwitchState}."
		}
		if (targetDevice.hasAttribute("speed")) 
		{
			targetDevice.sendEvent( name: "speed", value: levelToSpeed(targetLevel), descriptionText: "Speed set", type: "physical" )
		}
		if (targetDevice.hasAttribute("level") && (targetLevel != 0 )) // Only handle on values 1-99 here. If device was turned off, that would be handle in the switch state block above.
		{
			targetDevice.sendEvent( name: "level", value: targetLevel, descriptionText: "Level set", unit:"%", type: "physical" )
			if (txtEnable) log.info "Device ${targetDevice.displayName} level set to ${targetLevel}%"		
		}
	}
}

void on(Map params = [cd: null , duration: null , level: null ])
{
	com.hubitat.app.DeviceWrapper targetDevice = (params.cd ?: device)
	Integer ep = params.cd ? (params.cd.deviceNetworkId.split("-ep")[-1] as Integer) : null
	if (txtEnable) log.info "Device ${targetDevice.displayName}: Turning device to: On."
	
	Integer targetLevel = 100
	if (targetDevice.hasAttribute("switch")) {	
		targetDevice.sendEvent(name: "switch", value: "on", descriptionText: "Device turned on", type: "digital")
	} else {
		log.error "Device ${targetDevice.displayName}: Error in function on(). Device does not have a switch attribute."
	}
	
	if (targetDevice.hasAttribute("level")) {
		targetLevel = params.level ?: (targetDevice.currentValue("level") as Integer) ?: 100
		targetLevel = Math.max(Math.min(targetLevel, 100), 0)
		targetDevice.sendEvent(name: "level", value: targetLevel, descriptionText: "Device level set", unit:"%", type: "digital")
		if (txtEnable) log.info "Device ${targetDevice.displayName}: Setting level to: ${targetLevel}%."

	}
	sendZwaveValue(value: targetLevel, duration: params.duration, ep: ep)
}

void off(Map params = [cd: null , duration: null ]) {
	com.hubitat.app.DeviceWrapper targetDevice = (params.cd ?: device)
	Integer ep = params.cd ? (params.cd.deviceNetworkId.split("-ep")[-1] as Integer) : null
	
	if (txtEnable) log.info "Device ${targetDevice.displayName}: Turning device to: Off."

	if (targetDevice.hasAttribute("switch")) {	
		targetDevice.sendEvent(name: "switch", value: "off", descriptionText: "Device turned off", type: "digital")
		
		sendZwaveValue(value: 0, duration: params.duration, ep: ep)
	} else {
		log.error "Device ${targetDevice.displayName}: Error in function off(). Device does not have a switch attribute."
	}
}

void setLevel(level, duration = null ) {
	setLevel(level:level, duration:duration)
}
	
void setLevel(Map params = [cd: null , level: null , duration: null ])
{
	if ( (params.level as Integer) <= 0) {
		off(cd:params.cd, duration:params.duration)
	} else {
		on(cd:params.cd, level:params.level, duration:params.duration)
	}
}

void startLevelChange(direction, cd = null ){
	com.hubitat.app.DeviceWrapper targetDevice = (cd ? cd : device)
	Integer ep = cd ? (cd.deviceNetworkId.split("-ep")[-1] as Integer) : null
	
    Integer upDown = (direction == "down" ? 1 : 0)
    sendSupervised(zwave.switchMultilevelV4.switchMultilevelStartLevelChange(upDown: upDown, ignoreStartLevel: 1, startLevel: 0), ep)
}

void stopLevelChange(cd = null ){
	com.hubitat.app.DeviceWrapper targetDevice = (cd ? cd : device)
	Integer ep = cd ? (cd.deviceNetworkId.split("-ep")[-1] as Integer) : null
	
	sendSupervised(zwave.switchMultilevelV4.switchMultilevelStopLevelChange(), ep)
	sendUnsupervised(zwave.basicV1.basicGet(), ep)
}

////////////////////////////////////////////////////////
//////                Handle Fans                ///////
////////////////////////////////////////////////////////

String levelToSpeed(Integer level)
{
// 	Map speeds = [(0..0):"off", (1..20):"low", (21..40):"medium-low", (41-60):"medium", (61..80):"medium-high", (81..100):"high"]
//	return (speeds.find{ key, value -> key.contains(level) }).value
	switch (level)
	{
	case 0 : 		return "off" ; break
	case 1..20: 	return "low" ; break
	case 21..40: 	return "medium-low" ; break
	case 41..60: 	return "medium" ; break
	case 61..80: 	return "medium-high" ; break
	case 81..100: 	return "high" ; break
	default : return null
	}
}

Integer speedToLevel(String speed) {
	return ["off": 0, "low":20, "medium-low":40, "medium":60, "medium-high":80, "high":100].get(speed)
}

void setSpeed(speed, com.hubitat.app.DeviceWrapper cd = null ) { setSpeed(speed:speed, cd:cd) }
void setSpeed(Map params = [speed: null , level: null , cd: null ])
{
	com.hubitat.app.DeviceWrapper targetDevice = params.cd ?: device
	Integer ep = params.cd ? (targetDevice.deviceNetworkId.split("-ep")[-1] as Integer) : null
	
	if (params.speed.is( null ) ) {
		log.error "Device ${targetDevice.deviceName}: setSpeed command received without a valid speed setting. Speed setting was ${params.speed}. Returning without doing anything!"
		return
	}
	
    if (logEnable) log.info "Device ${device.displayName}: received setSpeed(${params.speed}) request from child ${targetDevice.displayName}"

	String currentOnState = targetDevice.currentValue("switch")
	Integer currentLevel = targetDevice.currentValue("level") // Null if attribute isn't supported.
	Integer targetLevel
	
	if (params.speed == "on")
	{
		if (currentOnState == "on") return // If already on, and receive on, do nothing!

		currentLevel = currentLevel ?: 100 // If it was a a level of 0, turn to 100%. Level should never be 0 -- except it might be 0 or null on first startup!
		targetDevice.sendEvent(name: "switch", value: "on", descriptionText: "Fan turned on", type: "digital")

		targetDevice.sendEvent(name: "level", value: currentLevel, descriptionText: "Fan level set", unit:"%", type: "digital")
		targetDevice.sendEvent(name: "speed", value: levelToSpeed(currentLevel), descriptionText: "Fan speed set", type: "digital")
		
		sendZwaveValue(value: currentLevel, duration: 0, ep: ep)

	} else if (params.speed == "off")
	{ 
		if (currentOnState == "off") return // if already off, and receive off, do nothing.
		
		targetDevice.sendEvent(name: "switch", value: "off", descriptionText: "Fan switched off", type: "digital")
		targetDevice.sendEvent(name: "speed", value: "off", descriptionText: "Fan speed set", type: "digital")	 
		
		sendZwaveValue(value: 0, duration: 0, ep: ep)
		
	} else {
		targetLevel = (params.level as Integer) ?: speedToLevel(params.speed) ?: currentLevel

		targetDevice.sendEvent(name: "switch", value: "on", descriptionText: "Fan turned on", type: "digital")
		targetDevice.sendEvent(name: "speed", value: params.speed, descriptionText: "Fan speed set", type: "digital")
		targetDevice.sendEvent(name: "level", value: targetLevel, descriptionText: "Fan level set", unit:"%", type: "digital")
		
		sendZwaveValue(value: targetLevel, duration: 0, ep: ep)
	}
}
//////////////////////////////////////////////////////////////////////
//////        Child Device Methods        ///////
////////////////////////////////////////////////////////////////////// 

Integer getEndpoint(com.hubitat.app.DeviceWrapper thisDevice) {
	if (thisDevice.is( null )) return null 
	return thisDevice.deviceNetworkId.split("-ep")[-1] as Integer
}

void componentOn(com.hubitat.app.DeviceWrapper cd){
	on(cd:cd)
}

void componentOff(com.hubitat.app.DeviceWrapper cd){
	off(cd:cd)
}

void componentSetLevel(com.hubitat.app.DeviceWrapper cd, level, transitionTime = null) {
	if (cd.hasCapability("FanControl") ) {
			setSpeed(cd:cd, level:level, speed:levelToSpeed(level as Integer))
		} else { 
			setLevel(level:level, duration:transitionTime, cd:cd) 
		}
}

void componentStartLevelChange(com.hubitat.app.DeviceWrapper cd, direction) {
	startLevelChange(direction:direction, cd:cd)
}

void componentStopLevelChange(com.hubitat.app.DeviceWrapper cd) {
	stopLevelChange(cd:cd)
}

void componentSetSpeed(com.hubitat.app.DeviceWrapper cd, speed) {
	setSpeed(speed:speed, cd:cd)
}
///////////////////////////////////////////////////////////////////////////////////////////////
///////////////                  Central Scene Processing          ////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////

// Use a concurrentHashMap to hold the last reported state. This is used for "held" state checking
// In a "held" state, the device will send "held down refresh" messages at either 200 mSecond or 55 second intervals.
// Hubitat should not generated repreated "held" messages in response to a refresh, so inhibit those
// Since the concurrentHashMap is @Field static -- its data structure is common to all devices using this
// Driver, therefore you have to key it using the device.deviceNetworkId to get the value for a particuarl device.
@Field static  ConcurrentHashMap centralSceneButtonState = new ConcurrentHashMap<String, String>(128)

String getCentralSceneButtonState(Integer button) { 
 	String key = "${device.deviceNetworkId}.Button.${button}"
	return centralSceneButtonState.get(key)
}

String setCentralSceneButtonState(Integer button, String state) {
 	String key = "${device.deviceNetworkId}.Button.${button}"
	centralSceneButtonState.put(key, state)
	return centralSceneButtonState.get(key)
}

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneSupportedReport  cmd) {
		sendEvent(name:"numberOfButtons", value: cmd.supportedScenes)
}

void doubleTap(button) 	{ multiTap(button, 2)	}
void push(button) 		{ multiTap(button, 1) }
void hold(button) 		{ sendEvent(name:"held", value:button, type:"digital", isStateChange:true )	}
void release(button) 	{ sendEvent(name:"released", value:button, type:"digital", isStateChange:true )	 }

void multiTap(button, taps) {
	if (taps == 1) {
	    sendEvent(name:"pushed", value:button, type:"digital", isStateChange:true )	

	} else if (taps == 2) {
		sendEvent(name:"doubleTapped", value:button, type:"digital", isStateChange:true )	
	}
	
    sendEvent(name:"multiTapButton", value:("${button}.${taps}" as Float), type:"physical", unit:"Button #.Tap Count", isStateChange:true )		
}

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd)
{

	// Check if central scene is already in a held state, if so, and you get another held message, its a refresh, so don't send a sendEvent
	if ((getCentralSceneButtonState(cmd.sceneNumber as Integer) == "held") && (cmd.keyAttributes == 2)) return

	// Central scene events should be sent with isStateChange:true since it is valid to send two of the same events in a row (except held, whcih is handled in previous line)
    Map event = [value:cmd.sceneNumber, type:"physical", unit:"button#", isStateChange:true]
	
	event.name = [	0:"pushed", 1:"released", 2:"held",  3:"doubleTapped", 
					4:"buttonTripleTapped", 5:"buttonFourTaps", 6:"buttonFiveTaps"].get(cmd.keyAttributes as Integer)
	
	String tapDescription = [	0:"Pushed", 1:"Released", 2:"Held",  3:"Double-Tapped", 
								4:"Three Taps", 5:"Four Taps", 6:"Five Taps"].get(cmd.keyAttributes as Integer)
    
	// Save the event name for event that is about to be sent using sendEvent. This is important for 'held' state refresh checking
	setCentralSceneButtonState(cmd.sceneNumber, event.name)	
	
	event.descriptionText="${device.displayName}: Button #${cmd.sceneNumber}: ${tapDescription}"

	if (device.hasAttribute( event.name )) sendEvent(event)
	
	// Next code is for the custom attribute "multiTapButton".
	Integer taps = [0:1, 3:2, 4:3, 5:4, 6:5].get(cmd.keyAttributes as Integer)
	if ( taps && device.hasAttribute("multiTapButton") )
	{
		event.name = "multiTapButton"
		event.unit = "Button #.Tap Count"
		event.value = ("${cmd.sceneNumber}.${taps}" as Float)
		sendEvent(event)		
	} 
}

///////////////////////////////////////////////////////////////////////////////////////
///////      Handle Refreshes      ////////
/////////////////////////////////////////////////////////////////////////////////////// 
void componentRefresh(com.hubitat.app.DeviceWrapper cd){
	refreshEndpoint(cd:cd)
}

void refreshEndpoint(Map params = [cd: null, ep: null ])
{
	// com.hubitat.app.DeviceWrapper targetDevice = device
	Integer ep = null
	if (params.cd) {
			ep = (params.cd.deviceNetworkId.split("-ep")[-1]) as Integer
	} else if (! params.ep.is( null )) {
		ep = params.ep as Integer
	}
	if (ep.is( null )) return
	
	Map record = thisDeviceDataRecord.get("endpoints").get(ep)
		if (logEnable) log.debug "Device ${device.displayName}: Refreshing endpoint: ${ep ?: 0} with record ${record}"
		if (txtEnable) log.info "Device ${device.displayName}: refreshing values for endpoint ${ep}."
		
		if (record.classes.contains(0x25)) 		sendUnsupervised(zwave.switchBinaryV1.switchBinaryGet(), ep)
		if (record.classes.contains(0x26)) 		sendUnsupervised(zwave.switchMultilevelV4.switchMultilevelGet(), ep)
		if (record.classes.contains(0x32)) 		refreshMeters(ep)
		if (record.classes.contains(0x71)) 		refreshNotifications(ep)
		// if (record.classes.contains(0x62)) 		refreshLock(ep)
		if (record.classes.contains(0x80)) 		refreshBattery()
}

void refresh()
{
	thisDeviceDataRecord.get("endpoints").each{thisEp, v ->
		refreshEndpoint(ep:thisEp)
	}
}

void	refreshLock(ep = null ) {
	log.error "Device ${device.displayName} Function refreshLock is not fully implemented."
}

//////////////////////////////////////////////////////////////////////
//////        Handle Battery Reports and Device Functions        ///////
////////////////////////////////////////////////////////////////////// 
void zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) 
{
	
	if (cmd.batteryLevel == 0xFF) {
		device.sendEvent ( name: "battery", value:1, unit: "%", descriptionText: "Low Battery Alert. Change now!")
	} else {
		device.sendEvent ( name: "battery", value:cmd.batteryLevel, unit: "%", descriptionText: "Battery level report.")
	}
}

void refreshBattery() {
	sendUnsupervised(zwave.batteryV1.batteryGet())
}
/////////////////////////////////////////////////////////////////////////////////////// 
///////                   Parameter Updating and Management                    ////////
///////      Handle Update(), and Set, Get, and Process Parameter Values       ////////
/////////////////////////////////////////////////////////////////////////////////////// 

void logsOff() {
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void updated()
{
	if (txtEnable) log.info "Device ${device.displayName}: Updating changed parameters (if any) . . ."
	if (logEnable) runIn(1800,logsOff)
	
	ConcurrentHashMap<Integer, BigInteger> parameterValueMap = getParameterValuesFromDevice()
	if (parameterValueMap.is( null ))
		{
			log.error "In function updated, parameterValueMap is ${parameterValueMap}"
			return
		}

	ConcurrentHashMap<Integer, BigInteger> pendingChanges = getPendingChangeMap()

	Map<Integer, BigInteger>  settingValueMap = getParameterValuesFromInputControls()
	// if (logEnable ) log.debug "Device ${device.displayName}: Current input control values are: ${settingValueMap}"

	// Find what changed
	settingValueMap.findAll{k, v -> !(v.is( null ))}.each {k, v ->
			Boolean changedValue = ((v as BigInteger) != (parameterValueMap.get(k as Integer) as BigInteger)) 
			if (changedValue) {
				pendingChanges?.put(k as Integer, v as BigInteger)
			} else pendingChanges?.remove(k)
		}

	if (txtEnable) log.info "Device ${device.displayName}: Pending parameter changes are: ${pendingChanges ?: "None"}"
	
	processPendingChanges()
	if (txtEnable) log.info "Device ${device.displayName}: Done updating changed parameters (if any) . . ."

}

void processPendingChanges()
{
	if (txtEnable) log.info "Device ${device.displayName}: Processing Pending parameter changes: ${getPendingChangeMap()}"
		pendingChangeMap?.findAll{k, v -> !(v.is( null ))}.each{ k, v ->
			if (txtEnable) log.info "Updating parameter ${k} to value ${v}"
			setParameter(parameterNumber: k , value: v)
		}
}

void setParameter(parameterNumber, value = null ) {
	if (parameterNumber && ( ! value.is( null) )) {
		setParameter(parameterNumber:parameterNumber, value:value)
	} else if (parameterNumber) {
		sendUnsupervised( zwave.configurationV1.configurationGet(parameterNumber: parameterNumber))
		hubitat.zwave.Command report = myReportQueue("7006").poll(10, TimeUnit.SECONDS)
		if (logEnable) log.debug "Device ${device.displayName}: Received a parameter configuration report: ${report}."
	}
}

Boolean setParameter(Map params = [parameterNumber: null , value: null ] ){
    if (params.parameterNumber.is( null ) || params.value.is( null ) ) {
		log.warn "Device ${device.displayName}: Can't set parameter ${parameterNumber}, Incomplete parameter list supplied... syntax: setParameter(parameterNumber,size,value), received: setParameter(${parameterNumber}, ${size}, ${value})."
		return false
    } 
	
	String getThis = "${params.parameterNumber}" as String

	Integer PSize = ( deviceInputs.get(getThis)?.size) ?: (deviceInputs.get(params.parameterNumber as Integer)?.size )
	
	if (!PSize) {log.error "Device ${device.displayName}: Could not get parameter size in function setParameter. Defaulting to 1"; PSize = 1}

	sendUnsupervised(zwave.configurationV1.configurationSet(scaledConfigurationValue: params.value as BigInteger, parameterNumber: params.parameterNumber, size: PSize))
	// The 'get' should not be supervised!
	sendUnsupervised( zwave.configurationV1.configurationGet(parameterNumber: params.parameterNumber))
	
	// Wait for the report that is returned after the configurationGet, and then update the input controls so they display the updated value.
	Boolean success = myReportQueue("7006").poll(10, TimeUnit.SECONDS)

}

// Gets a map of all the values currently stored in the input controls.
Map<Integer, BigInteger> getParameterValuesFromInputControls()
{
	ConcurrentHashMap inputs = getDeviceInputs()
	
	if (!inputs) return
	
	Map<Integer, BigInteger> settingValues = [:]
	
	inputs.each 
		{ PKey , PData -> 
			BigInteger newValue = 0
			// if the setting returns an array, then it is a bitmap control, and add together the values.
			
			if (settings.get(PData.name as String) instanceof ArrayList) {
				settings.get(PData.name as String).each{ newValue += it as BigInteger }
			} else  {   
				newValue = settings.get(PData.name as String) as BigInteger  
			}
			settingValues.put(PKey, newValue)
		}
	if (txtEnable) log.info "Device ${device.displayName}: Current Parameter Setting Values are: " + settingValues
	return settingValues
}

@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allPendingParameterChanges = new ConcurrentHashMap<String, ConcurrentHashMap>(128)
@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allDevicesParameterValues = new ConcurrentHashMap<String, ConcurrentHashMap>(128)

ConcurrentHashMap getPendingChangeMap() {
	return  allPendingParameterChanges.get(device.deviceNetworkId, new ConcurrentHashMap(32) )
}

Map<Integer, BigInteger> getParameterValuesFromDevice()
{
	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32))
	
	ConcurrentHashMap inputs = getDeviceInputs()	
	
	log.debug "In function getParameterValuesFromDevice, parameter values are: ${parameterValues}. Size is: ${parameterValues.size()}. Inputs size is ${inputs.size()}."
	
	if (!inputs) return null

	if ((parameterValues?.size() as Integer) == (inputs?.size() as Integer) ) 
	{
		// if (logEnable) log.debug "Device ${device.displayName}: In Function getParameterValuesFromDevice, returning Previously retrieved Parameter values: ${parameterValues}"

		return parameterValues
	} else {
		// if (logEnable) log.debug "Getting missing parameter values"
		Boolean success  = false
		inputs.each 
			{ k, v ->
				if (parameterValues.get(k as Integer).is( null ) ) {
					if (txtEnable) log.info "Device ${device.displayName}: Obtaining value for parameter #: ${k}"
					sendUnsupervised(zwave.configurationV1.configurationGet(parameterNumber: k))
					success = (myReportQueue("7006").poll(10, TimeUnit.SECONDS)) ?: false
				} else {
					// if (logEnable) log.debug "Device ${device.displayName}: For parameter: ${k} previously retrieved a value of ${parameterValues.get(k as Integer)}."
				}
			}
		// if (logEnable) log.debug "Device ${device.displayName}: In Function getParameterValuesFromDevice, returning newly retrieved Parameter values: ${parameterValues}"
	return parameterValues			
	}
	return null
}

void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport  cmd)
{ 
	if (logEnable) log.debug "Device ${device.displayName}: Received a configuration report ${cmd}."
	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32))
	BigInteger newValue = (cmd.size == 1) ? cmd.configurationValue[0] : cmd.scaledConfigurationValue			
	if (newValue < 0) log.warn "Device ${device.displayName}: Negative configuration value reported for configuration parameter ${cmd.parameterNumber}."
				
	parameterValues.put((cmd.parameterNumber as Integer), newValue )
	
	pendingChangeMap.remove(cmd.parameterNumber as Integer)
	
	if (txtEnable) log.info "Device ${device.displayName}: updating parameter: ${cmd.parameterNumber} to ${newValue}."
	device.updateSetting("${cmd.parameterNumber}", newValue as Integer)
		
	myReportQueue(cmd.CMD).offer( true )
}

//////////////////////////////////////////////////////////////////////
//////        Handle Supervision request and reports           ///////
////////////////////////////////////////////////////////////////////// 

// @Field static results in variable being shared among all devices that use the same driver, so I use a concurrentHashMap keyed by a device's deviceNetworkId to get a unqiue value for a particular device
// supervisionSessionIDs stores the last used sessionID value (0..63) for a device. It must be incremented mod 64 on each send
// supervisionSentCommands stores the last command sent
// Each is initialized for 64 devices, but can automatically grow
@Field static ConcurrentHashMap<String, Integer> supervisionSessionIDs = new ConcurrentHashMap<String, Integer>(64)
@Field static ConcurrentHashMap<String, ConcurrentHashMap> supervisionSentCommands = new ConcurrentHashMap<String, ConcurrentHashMap<Integer, String>>(64)

Integer getNewSessionId() {
		// Get the next session ID mod 64, but if there is no stored session ID, initialize it with a random value.
		Integer lastSessionID = supervisionSessionIDs.get(device.getDeviceNetworkId() as String,((Math.random() * 64) % 64) as Integer )
		Integer nextSessionID = (lastSessionID + 1) % 64 // increment and then mod with 64, and then store it back in the Hash table.
		supervisionSessionIDs.replace(device.getDeviceNetworkId(), nextSessionID)
		return nextSessionID
} 

Boolean getNeverSupervise()
{
	Integer mfr = 			getDataValue("manufacturer")?.toInteger()
	Integer deviceId = 		getDataValue("deviceId")?.toInteger()
	Integer deviceType =	getDataValue("deviceType")?.toInteger()
	List<Map> supervisionBroken = [
			[	manufacturer:798,  	deviceType:14,	deviceId: 1  	], // Inovelli LZW36 firmware 1.36 supervision is broken!
			]

	Map thisDevice =	supervisionBroken.find{ element ->
						((element.manufacturer == mfr ) && (element.deviceId == deviceId ) && (element.deviceType == deviceType))
					}
	if (thisDevice && logEnable) log.warn "Device ${device.displayName}: This device is on the broken supervision list. Check manufacturer for a firmware update. Not supervising."
	return ( thisDevice ? true : false )
}

Boolean getSuperviseThis() {
		if (neverSupervise) return false
		return (getDataValue("S2")?.toInteger() != null )
}

void sendSupervised(hubitat.zwave.Command cmd, ep = null ) { 
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)
	
	Integer thisSessionId = getNewSessionId()
	
	ConcurrentHashMap commandStorage = supervisionSentCommands.get(device.getDeviceNetworkId() as String, new ConcurrentHashMap<Integer, hubitat.zwave.Command>(64))
	
	def sendThisCommand = cmd // use def rather than more specific data class, as data class changes from a Hubitat Command to a String after security encapsulation

	if (superviseThis)
	{
		sendThisCommand = zwave.supervisionV1.supervisionGet(sessionID: thisSessionId, statusUpdates: true ).encapsulate(sendThisCommand)
		sendThisCommand = secure(sendThisCommand, ep) // Returns security and endpoint encapsulated string
		commandStorage.put(thisSessionId, sendThisCommand)
		sendHubCommand(new hubitat.device.HubAction( sendThisCommand, hubitat.device.Protocol.ZWAVE)) 
		runIn(3, supervisionCheck)	
		// if (logEnable)  log.debug "Device ${targetDevice}: In sendSupervised, Sending supervised command: ${sendThisCommand}"

	} else {
		sendThisCommand = secure(sendThisCommand, ep) // Returns security and endpoint encapsulated string
		sendHubCommand(new hubitat.device.HubAction( sendThisCommand, hubitat.device.Protocol.ZWAVE)) 
		// if (logEnable)  log.debug "Device ${targetDevice}: In sendSupervised, Sending Un-supervised command: ${sendThisCommand}"
	}
}

void sendUnsupervised(hubitat.zwave.Command cmd, ep = null ) { 
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)
	def sendThisCommand = secure(cmd, ep)
	sendHubCommand(new hubitat.device.HubAction( sendThisCommand, hubitat.device.Protocol.ZWAVE)) 
	// if (logEnable)  log.debug "Device ${targetDevice}: In sendUnsupervised, Sending Un-supervised command: ${sendThisCommand}"
}
	

// This handles a supervised message (a "get") received from the Z-Wave device //
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, ep = null ) {
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)
	// if (logEnable) log.debug "Device ${targetDevice.displayName}: Supervision Get - SessionID: ${cmd.sessionID}, Command Class: ${cmd.commandClassIdentifier}, Command: ${cmd.commandIdentifier}"
	
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(defaultParseMap)
	
    if (encapsulatedCommand) {
		if ( ep ) {
			zwaveEvent(encapsulatedCommand, ep)
		} else {
			zwaveEvent(encapsulatedCommand)
		}
    }
	
	hubitat.zwave.Command confirmationReport = (new hubitat.zwave.commands.supervisionv1.SupervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0))
	
	sendHubCommand(new hubitat.device.HubAction(secure(confirmationReport, ep), hubitat.device.Protocol.ZWAVE))
}

Boolean ignoreSupervisionNoSupportCode()
{
	// Some devices implement the Supervision command class incorrectly and return a "No Support" code even when they work.
	// This function is to ignore the No Support code from those devices.
	List<Map> poorSupervisionSupport = [
			[	manufacturer:12,  	deviceType:17479,	deviceId: 12340  	], // HomeSeer WD100 S2 is buggy!
			[	manufacturer:12,  	deviceType:17479,	deviceId: 12342  	], // HomeSeer WD200 is buggy!
			]
    	Map thisDevice =	poorSupervisionSupport.find{ element ->
							((element.manufacturer == getDataValue("manufacturer")?.toInteger()) && (element.deviceId == getDataValue("deviceId")?.toInteger()) && (element.deviceType == getDataValue("deviceType")?.toInteger()))
						}
		return ( thisDevice ? true : false )				
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionReport cmd, ep = null ) 
{
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)
	
	ConcurrentHashMap whatThisDeviceSent = supervisionSentCommands?.get(device.getDeviceNetworkId() as String)
	
	String whatWasSent = null

	switch (cmd.status as Integer)
	{
		case 0x00: // "No Support" 
			whatWasSent = whatThisDeviceSent?.remove(cmd.sessionID as Integer)
			if (ignoreSupervisionNoSupportCode()) {
				if (logEnable) log.warn "Received a 'No Support' supervision report ${cmd} for command ${whatWasSent}, but this device has known problems with its Supervision implementation so the 'No Support' code was ignored."
			} else 	{
				log.warn "Device ${targetDevice.displayName}: Z-Wave Command supervision reported as 'No Support' for command ${whatWasSent}. If you see this warning repeatedly, please report as an issue on https://github.com/jvmahon/HubitatCustom/issues. Please provide the manufacturer, deviceType, and deviceId code for your device as shown on the device's Hubitat device web page."
			}
			break
		case 0x01: // "working"
			whatWasSent = whatThisDeviceSent?.get(cmd.sessionID as Integer)
			if (txtEnable) log.info "Device ${targetDevice.displayName}: Still processing command: ${whatWasSent}."
			runIn(5, supervisionCheck)	
			break ;
		case 0x02: // "Fail"
			whatWasSent = whatThisDeviceSent?.remove(cmd.sessionID as Integer)
			log.warn "Device ${targetDevice.displayName}: Z-Wave supervised command reported failure. Failed command: ${whatWasSent}."
			sendUnsupervised(zwave.basicV1.basicGet(), ep)
			break
		case 0xFF: // "Success"
			whatWasSent = whatThisDeviceSent?.remove(cmd.sessionID as Integer)
			if (txtEnable || logEnable) log.info "Device ${targetDevice.displayName}: Device successfully processed supervised command ${whatWasSent}."
			break
	}
	if (whatThisDeviceSent?.size() < 1) unschedule(supervisionCheck)
}

void supervisionCheck() {
    // re-attempt once
	ConcurrentHashMap tryAgain = supervisionSentCommands?.get(device.getDeviceNetworkId() as String)
	tryAgain?.each{ sessionId, cmd ->
		log.warn "Device ${device.displayName}: Supervision Check is resending command: ${cmd} with sessionId: ${sessionId}"
		sendHubCommand(new hubitat.device.HubAction( cmd, hubitat.device.Protocol.ZWAVE)) 
		supervisionSentCommands?.get(device.getDeviceNetworkId() as String).remove(sessionId as Integer)
	}
}

//////////////////////////////////////////////////////////////////////
//////                  Report Queues                          ///////
//////////////////////////////////////////////////////////////////////
// reportQueues stores a map of SynchronousQueues. When requesting a report from a device, the report handler communicates the report back to the requesting function using a queue. This makes programming more like "synchronous" programming, rather than asynchronous handling.
// This is a map within a map. The first level map is by deviceNetworkId. Since @Field static variables are shared among all devices using the same driver code, this ensures that you get a unique second-level map for a particular device. The second map is keyed by the report class hex string. For example, if you want to wait for the configurationGet report, wait for "7006".
@Field static reportQueues = new ConcurrentHashMap<String, ConcurrentHashMap>(128)

SynchronousQueue myReportQueue(String reportClass) {
	ConcurrentHashMap thisDeviceQueues = reportQueues.get(device.deviceNetworkId, new ConcurrentHashMap<String,SynchronousQueue>(32))
	
	// Get the queue if it exists, create (new) it if it does not.
	SynchronousQueue thisReportQueue = thisDeviceQueues.get(reportClass, new SynchronousQueue())
	return thisReportQueue
}

//////////////////////////////////////////////////////////////////////
//////                  Z-Wave Helper Functions                ///////
//////   Format messages, Send to Device, secure Messages      ///////
//////////////////////////////////////////////////////////////////////
Map getDefaultParseMap() {
	return [
		0x20:2, // Basic Set
		0x25:2, // Switch Binary
		0x26:4, // Switch MultiLevel 
		0x31:11, // Sensor MultiLevel
		0x32:6, // Meter
		0x5B:3,	// Central Scene
		0x60:4,	// MultiChannel
		0x62:1,	// Door Lock
		0x63:1,	// User Code
		0x6C:1,	// Supervision
		0x71:8, // Notification
		0x72: 1, // Manufacturer Specific
		0x80:1, // Battery
		0x86:3,	// Version
		0x98:1,	// Security
		0x9B:2,	// Configuration
		0x87:3  // Indicator
		]
}

com.hubitat.app.DeviceWrapper getTargetDeviceByEndPoint(ep = null ) {
	if (ep) { 
		return getChildDevices().find{ (it.deviceNetworkId.split("-ep")[-1] as Integer) == (ep as Integer)}
	} else { 
		return device 
	}
}

//// Catch Event Not Otherwise Handled! /////

void zwaveEvent(hubitat.zwave.Command cmd, ep = null) {
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)

    log.warn "Device ${targetDevice.displayName}: Received Z-Wave Message ${cmd} that is not handled by this driver. Endpoint: ${ep}. Message class: ${cmd.class}."
}

////    Hail   ////
void zwaveEvent(hubitat.zwave.commands.hailv1.Hail cmd) {
	refresh()
}

////    Security Encapsulation   ////
void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand( defaultParseMap )
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand) }
}

String secure(Integer cmd, Integer hexBytes = 2, ep = null ) { 
    return secure(hubitat.helper.HexUtils.integerToHexString(cmd, hexBytes), ep) 
}

String secure(String cmd, ep = null ){ 
	if (ep) {
		return zwaveSecureEncap(zwave.multiChannelV4.multiChannelCmdEncap(sourceEndPoint: 0, bitAddress: 0, res01:0, destinationEndPoint: ep).encapsulate(cmd))
	} else {
		return zwaveSecureEncap(cmd) 
	}
}

String secure(hubitat.zwave.Command cmd, ep = null ){ 
	if (ep) {
		return zwaveSecureEncap(zwave.multiChannelV4.multiChannelCmdEncap(sourceEndPoint: 0, bitAddress: 0, res01:0, destinationEndPoint: ep).encapsulate(cmd))
	} else {
		return zwaveSecureEncap(cmd) 
	}
}

////    Multi-Channel Encapsulation   ////
void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    hubitat.zwave.Command  encapsulatedCommand = cmd.encapsulatedCommand(defaultParseMap)
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint) }
}

////    Z-Wave Message Parsing   ////
void parse(String description) {
		hubitat.zwave.Command cmd = zwave.parse(description, defaultParseMap)
		if (cmd) { zwaveEvent(cmd) }
}

////    Z-Wave Message Sending to Hub  ////
// void sendToDevice(List<hubitat.zwave.Command> cmds) { sendHubCommand(new hubitat.device.HubMultiAction(commands(cmds), hubitat.device.Protocol.ZWAVE)) }

// void sendToDevice(hubitat.zwave.Command cmd, ep = null ) { sendHubCommand(new hubitat.device.HubAction(secure(cmd, ep), hubitat.device.Protocol.ZWAVE)) }

// void sendToDevice(String cmd) { sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE)) }

List<String> commands(List<hubitat.zwave.Command> cmds, Long delay=200) { return delayBetween(cmds.collect{ it }, delay) }

////    Send Simple Z-Wave Commands to Device  ////	
void sendZwaveValue(Map params = [value: null , duration: null , ep: null ] )
{
	Integer newValue = Math.max(Math.min(params.value, 99),0)
	Map endpointData = getThisDeviceDataRecord().get("endpoints")
	Map thisEndpoint = (endpointData.get( (params.ep ?: 0) as String) ?: endpointData.get( (params.ep ?: 0) as Integer))
	List deviceClasses = thisEndpoint.classes

	if ( !(0..100).contains(params.value) ) {
	log.warn "Device ${}: in function sendZwaveValue() received a value ${params.value} that is out of range. Valid range 0..100. Using value of ${newValue}."
	}
	
	if (deviceClasses.contains(0x26)) { // Multilevel  type device
		if (! params.duration.is( null) ) sendSupervised(zwave.switchMultilevelV4.switchMultilevelSet(value: newValue, dimmingDuration:params.duration), params.ep)	
			else sendSupervised(zwave.switchMultilevelV1.switchMultilevelSet(value: newValue), params.ep)
	} else if (deviceClasses.contains(0x25)) { // Switch Binary Type device
		sendSupervised(zwave.switchBinaryV1.switchBinarySet(switchValue: newValue ), params.ep)
	} else if (deviceClasses.contains(0x20)) { // Basic Set Type device
		log.warn "Device ${targetDevice.displayName}: Using Basic Set to turn on device. A more specific command class should be used!"
		sendSupervised(zwave.basicV1.basicSet(value: newValue ), params.ep)
	} else {
		log.error "Device ${device.displayName}: Error in function sendZwaveValue(). Device does not implement a supported class to control the device!.${ep ? " Endpoint # is: ${params.ep}." : ""}"
		return
	}
}

//////////////////////////////////////////////////////////////////////
//////        Handle   Notifications     ///////
//////////////////////////////////////////////////////////////////////


void	refreshNotifications(ep = null ) {
	Map specifiedNotifications = thisDeviceDataRecord?.endpoints.get((ep ?: 0) as Integer)?.get("notifications")
	log.debug "specifiedNotifications are ${specifiedNotifications}"
	if (specifiedNotifications)
	{ 
		log.debug "using the specifiedNotifications to refresh"

		specifiedNotifications.each{type, events ->
				performRefresh(type, events, ep)
				}
	}	else  {
			log.debug "learning the Notifications to refresh"

			sendUnsupervised(zwave.notificationV8.notificationSupportedGet(), ep)
	}
}

void performRefresh(type, events, ep)
{
	events.each{ it ->
		sendUnsupervised(zwave.notificationV8.notificationGet(v1AlarmType:0, event: (it as Integer), notificationType: type), ep)
	}
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationSupportedReport report, ep = null )
{ 
	if (logEnable) {
		com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)
		log.debug "Device ${targetDevice}: Received a NotificationSupportedReport: ${report}."
	}
	
	List<Integer> notificationTypes = []
	if (report.smoke)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:1), ep) } // Smoke
	if (report.co)					{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:2), ep) }  // CO
	if (report.co2)					{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:3), ep) }  // CO2
	if (report.heat)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:4), ep) }  // Heat
	if (report.water)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:5), ep) }  // Water
	if (report.accessControl) 		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:6), ep) }  // Access Control
	if (report.burglar)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:7), ep) }  // Burglar
	if (report.powerManagement)		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:8), ep) }  // Power Management
	if (report.system)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:9), ep) }  // System
	if (report.emergency)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:10), ep) }  // Emergency Alarm
	if (report.clock)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:11), ep) }  // Clock
	if (report.appliance)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:12), ep) } // Appliance
	if (report.homeHealth)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:13), ep) }  // Home Health
	if (report.siren)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:14), ep) }  // Siren
	if (report.waterValve)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:15), ep) }  // Water Valve
	if (report.weatherAlarm)		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:16), ep) }  // Weather Alarm
	if (report.irrigation)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:17), ep) } // Irrigation
	if (report.gasAlarm)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:18), ep) } // Gas Alarm
	if (report.pestControl)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:19), ep) }  // Pest Control
	if (report.lightSensor)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:20), ep) } // Light Sensor
	if (report.waterQuality)		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:21), ep) }  // Water Quality
	if (report.homeMonitoring)		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:22), ep) }  // Home Monitoring
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.EventSupportedReport cmd, ep = null )
{
	// Build a map of the notifications supported by a device endpoint and store it in the endpoint data
	List supportedEventsByType = cmd.supportedEvents.findAll{k, v -> ((v as Boolean) == true) }.collect{k, v -> (k as Integer) }
	
	Map thisEndpointNotifications = thisDeviceDataRecord?.endpoints.get((ep ?: 0) as Integer).get("notifications", [:])
		
	thisEndpointNotifications.put( (cmd.notificationType as Integer), supportedEventsByType)
}

Map getFormattedZWaveNotificationEvent(def cmd)
{
	Map returnEvent =
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
					1:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],
					2:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],	
					4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					7:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance required cleared, periodic inspection."],				
					], 
				1:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected (location provided)."], 
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
						5:[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected (location provided)"], // glass Breakage  attribute!
						6:[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected"], 	 // glass Breakage attribute!					
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

		if (returnEvent.is( null )) return null
		
		if ((cmd.event == 0) && (cmd.eventParametersLength == 1)) { // This is for clearing events.
				return returnEvent.get(cmd.eventParameter[0] as Integer)
		}
		
		if (cmd.eventParametersLength > 1) { // This is unexpected! None of the current notifications use this.
			log.error "In function getZWaveNotificationEvent(), received command with eventParametersLength of unexpected size."
			return null
		} 
		return returnEvent
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, ep = null )
{
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)

	Map thisEvent = getFormattedZWaveNotificationEvent(cmd)

	if ( ! thisEvent ) { 
		if ( logEnable ) log.debug "Device ${targetDevice.displayName}: Received an unhandled report ${cmd} for endpoint ${ep}." 
	} else { 
		if (targetDevice.hasAttribute(thisEvent.name)) { 
			targetDevice.sendEvent(thisEvent) 
		} else {
			log.warn "Device ${targetDevice.displayName}: Device missing attribute for event ${thisEvent}, Zwave report: ${cmd}."
		}
	}
}

//////////////////////////////////////////////////////////////////////
//////        Handle   Binary Sensor     ///////
//////////////////////////////////////////////////////////////////////

void	refreshBinarySensor(ep = null ) {
	log.debug "Device ${device}: refreshBinarySensor function is not implemented."
}

void performBinarySensorRefresh(type, events, ep) {
	log.debug "Device ${device}: performBinarySensorRefresh function is not implemented."
}

Map getFormattedZWaveSensorBinaryEvent(def cmd)
{
	Map returnEvent = [ 	
			2:[ // Smoke
				0:[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."],
				255:[name:"smoke" , value:"detected", descriptionText:"Smoke detected."], 
				],
			3:[ // CO
				0:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],
				255:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."],
				],
			4:[ // CO2
				0:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],	
				255:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."],
				],					
			6:[ // Water
				0:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
				255:[name:"water" , value:"wet", descriptionText:"Water leak detected."],
				],
			8:[ // Tamper
				0:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
				255:[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"], 
				],
			10:[ // Door/Window
				0:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed"], 					
				255:[name:"contact" , value:"open", descriptionText:"Contact sensor, open"], 					
				],
			12:[ // Motion
				0:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."],
				255:[name:"motion" , value:"active", descriptionText:"Motion detected."],
				]
				
		].get(cmd.sensorType as Integer)?.get(cmd.sensorValue as Integer)
		return returnEvent
}

void zwaveEvent(hubitat.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd, ep = null )
{
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)

	if (logEnable) log.debug "Device ${targetDevice}: Received SensorBinaryReport: ${cmd}"
	
	Map thisEvent = getFormattedZWaveSensorBinaryEvent(cmd)
	
	if ( ! thisEvent ) { 
		if ( logEnable ) log.debug "Device ${targetDevice.displayName}: Received an unhandled report ${cmd} for endpoint ${ep}." 
	} else { 
		if (targetDevice.hasAttribute(thisEvent.name)) { 
			targetDevice.sendEvent(thisEvent) 
		} else {
			log.warn "Device ${targetDevice.displayName}: Device missing attribute for event ${thisEvent}, Zwave report: ${cmd}."
		}
	}
}
//////////////////////////////////////////////////////////////////////
//////        Handle  Multilevel Sensor       ///////
//////////////////////////////////////////////////////////////////////
Map getFormattedZWaveSensorMultilevelReportEvent(def cmd)
{
	Map tempReport = [
		1: [name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Air temperature"], 
		23:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Water temperature"], 
		24:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Soil temperature"], 
		34:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Target temperature"], 
		62:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Boiler Water temperature"],
		63:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Domestic Hot Water temperature"], 
		64:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Outside temperature"], 
		65:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Exhaust temperature"],
		72:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Return Air temperature"],		
		73:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Supply Air temperature"],		
		74:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Condenser Coil temperature"],		
		75:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Evaporator Coil temperature"],		
		76:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Liquid Line temperature"],
		77:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Discharge Line temperature"],
		80:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Defrost temperature"],	
	].get(cmd.sensorType as Integer)

	if (tempReport)
	{
		tempReport.value = convertTemperatureIfNeeded(cmd.scaledMeterValue, (((cmd.scale as Integer) == 0) ? "C" : "F"), 2)
		returntempReport
	}
	
	Map otherSensorReport = [
		3000:[name: "illuminance", value: cmd.scaledMeterValue, unit: "%"], // Illuminance
		3001:[name: "illuminance", value: cmd.scaledMeterValue, unit: "lx"],
		4000:[name: "power", value: cmd.scaledMeterValue, unit: "W"],
		4001:[name: "power", value: cmd.scaledMeterValue, unit: "BTU/h"],
		5000:[name: "humidity", value: cmd.scaledMeterValue, unit: "%"],
		5001:[name: "humidity", value: cmd.scaledMeterValue, unit: "g/m3"],
		8000:[name: "pressure", value: (cmd.scaledMeterValue * ((cmd.scale == 0) ? 1000 : 3386.38867)), unit:"Pa", descriptionText:"Atmospheric Pressure"],
		9000:[name: "pressure", value: (cmd.scaledMeterValue * ((cmd.scale == 0) ? 1000 : 3386.38867)), unit:"Pa", descriptionText:"Barometric Pressure"],
		15000:[name: "voltage", value: cmd.scaledMeterValue, unit: "V"],
		15001:[name: "voltage", value: cmd.scaledMeterValue, unit: "mV"],
		16000:[name: "amperage", value: cmd.scaledMeterValue, unit: "A"],
		16001:[name: "amperage", value: cmd.scaledMeterValue, unit: "mA"],
		17000:[name: "carbonDioxide ", value: cmd.scaledMeterValue, unit: "ppm"],
		27000:[name: "ultravioletIndex", value: cmd.scaledMeterValue, unit: "UV Index"],
		40000:[name: "carbonMonoxide ", value: cmd.scaledMeterValue, unit: "ppm"],
		56000:[name: "rate", value: cmd.scaledMeterValue, unit: "LPH"], // Water flow	
		58000:[name: "rssi", value: cmd.scaledMeterValue, unit: "%"],
		58001:[name: "rssi", value: cmd.scaledMeterValue, unit: "dBm"],
		67000:[name: "pH", value: cmd.scaledMeterValue, unit: "pH"],
	].get((cmd.sensorType * 1000 + cmd.scale) as Integer)	
	
	return otherSensorReport
}

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv11.SensorMultilevelReport cmd, ep = null )
{
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)
	Map thisEvent = getFormattedZWaveSensorMultilevelReportEvent(cmd)
		
	if ( ! thisEvent ) { 
		if ( logEnable ) log.debug "Device ${targetDevice.displayName}: Received an unhandled report ${cmd} for endpoint ${ep}." 
	} else { 
		if (targetDevice.hasAttribute(thisEvent.name)) { 
			targetDevice.sendEvent(thisEvent) 
		} else {
			log.warn "Device ${targetDevice.displayName}: Device missing attribute for event ${thisEvent}, Zwave report: ${cmd}."
		}
	}
}

//////////////////////////////////////////////////////////////////////
//////                         Handle  Meters                  ///////
//////////////////////////////////////////////////////////////////////

void	refreshMeters(ep = null ) {
	// To make driver more generic, if meter type isn't known, then ask the device
	List specifiedScales = thisDeviceDataRecord?.endpoints.get((ep ?: 0) as Integer)?.metersSupported
	if (logEnable) log.debug "Refreshing a meter with scales ${specifiedScales}"
	if (specifiedScales)
	{ 
		meterRefresh(specifiedScales, ep)
	}	else  {
		sendUnsupervised(zwave.meterV6.meterSupportedGet(), ep)
	}
}

// Next function is not currently used! 
void meterRefresh ( List supportedScales, ep = null ) 
{ // meterSupported is a List of supported scales - e.g., [2, 5, 6]]
	if (logEnable) log.debug "Refreshing a meter with scales ${supportedScales}"

	supportedScales.each{ scaleValue ->
		if ((scaleValue as Integer) <= 6) {
			sendUnsupervised(zwave.meterV6.meterGet(scale: scaleValue), ep)
		} else {
			sendUnsupervised(zwave.meterV6.meterGet(scale: 7, scale2: (scaleValue - 7) ), ep)
		}
	}
}

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterSupportedReport report, ep = null )
{ 
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)

    if ((report.meterType as Integer) != 1 ){
		log.warn "Device ${targetDevice.displayName}: Received a meter support type of ${report.meterType} which is not processed by this code."
		return null
	}
	
	List<Integer> scaleList = []

	if (( report.scaleSupported & 0b00000001 ) as Boolean ) { scaleList.add(0) } // kWh
	if (( report.scaleSupported & 0b00000010 ) as Boolean ) { scaleList.add(1) } // kVAh
	if (( report.scaleSupported & 0b00000100 ) as Boolean ) { scaleList.add(2) } // Watts
	if (( report.scaleSupported & 0b00001000 ) as Boolean ) { scaleList.add(3) } // PulseCount
	if (( report.scaleSupported & 0b00010000 ) as Boolean ) { scaleList.add(4) } // Volts
	if (( report.scaleSupported & 0b00100000 ) as Boolean ) { scaleList.add(5) } // Amps
	if (( report.scaleSupported & 0b01000000 ) as Boolean ) { scaleList.add(6) } // PowerFactor

	if ((( report.scaleSupported & 0b10000000 ) as Boolean ) && report.moreScaleTypes) {
		if (( report.scaleSupportedBytes[1] & 0b00000001 ) as Boolean) { scaleList.add(7)} // kVar
		if (( report.scaleSupportedBytes[1] & 0b00000010 ) as Boolean) { scaleList.add(8)} // kVarh
	}
	thisDeviceDataRecord.endpoints.get((ep ?: 0) as Integer).put("metersSupported", scaleList) // Store in the device record so you don't have to ask device again!
	meterRefresh(scaleList, ep)
}

Map getFormattedZWaveMeterReportEvent(def cmd)
{
	BigDecimal meterValue = cmd.scaledMeterValue
	Integer deltaTime = cmd.deltaTime
	
	Map meterReport = [
		1:[
			0000:[name: "energy", 		value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Active Power Usage"],
			1000:[name: "energyConsumed", 	value: meterValue, deltaTime:deltaTime, unit: "kVAh", descriptionText:"Energy Consumed"], // custom attribute energyConsumed must be added to driver preferences
			2000:[name: "power", 		value: meterValue, deltaTime:deltaTime, unit: "W", descriptionText:"Watts"],
			3000:[name: "pulseCount", 	value: meterValue, deltaTime:deltaTime, unit: "Pulses", descriptionText:"Electric Meter - Pulse Count"], // custom attribute must be added to driver preferences
			4000:[name: "voltage", 		value: meterValue, deltaTime:deltaTime, unit: "V", descriptionText:"Current Voltage"],
			5000:[name: "amperage", 	value: meterValue, deltaTime:deltaTime, unit: "A", descriptionText:"Current Amperage"],
			6000:[name: "powerFactor", 	value: meterValue, deltaTime:deltaTime, unit: "Power Factor", descriptionText:"Power Factor"], // custom attribute must be added to driver preferences
			7000:[name: "reactiveCurrent", 	value: meterValue, deltaTime:deltaTime, unit: "KVar", descriptionText:"Reactive Current"], // custom attribute must be added to driver preferences
			7001:[name: "reactivePower", 	value: meterValue, deltaTime:deltaTime, unit: "KVarh", descriptionText:"Reactive Power"], // custom attribute must be added to driver preferences
		], 
		2:[ // Gas meter
			0000:[name: "gasFlow", 	value: meterValue, deltaTime:deltaTime, unit: "m3", descriptionText:"Gas volume."],
			1000:[name: "gasFlow", 	value: meterValue, deltaTime:deltaTime, unit: "ft3", descriptionText:"Gas volume"],
			3000:[name: "gasPulses", 	value: meterValue, deltaTime:deltaTime, unit: "pulses", descriptionText:"Gas MEter - Pulse Count"],
		],
		3:[ // Water meter
			0000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "m3", descriptionText:"Water Volume"],
			1000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "ft3", descriptionText:"Water Volume"],
			2000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "gpm", descriptionText:"Water flow"],			
			3000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "pulses", descriptionText:"Water Meter - Pulse Count"],
		],
		4:[ //Heating meter
			0000:[name: "heatingMeter", 	value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Heating"],
		],
		5:[ // Cooling meter
			0000:[name: "coolingMeter", 	value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Cooling"],
		]
	].get(cmd.meterType as Integer)?.get( ( (cmd.scale as Integer) * 1000 + ((cmd.scale2 ?: 0) as Integer)))
	
	if (meterReport.is( null )) return null
	
	if (cmd.scaledPreviousMeterValue) 
		{
			meterReport.put("previousValue", (cmd.scaledPreviousMeterValue) )
			String reportText = meterReport.descriptionText + " Prior value: ${cmd.scaledPreviousMeterValue}"
			meterReport.put("descriptionText", reportText)
		}
		
	return meterReport
}

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterReport cmd, ep = null )
{
	com.hubitat.app.DeviceWrapper targetDevice = getTargetDeviceByEndPoint(ep)

	Map thisEvent = getFormattedZWaveMeterReportEvent(cmd)
	if (logEnable) log.debug "Responding to a meter report ${cmd} with event ${thisEvent}"
	if ( ! thisEvent ) { 
		if ( logEnable ) log.debug "Device ${targetDevice.displayName}: Received an unhandled report ${cmd} for endpoint ${ep}." 
	} else { 
		if (targetDevice.hasAttribute(thisEvent.name)) { 
			targetDevice.sendEvent(thisEvent) 
		} else {
			log.warn "Device ${targetDevice.displayName}: Device missing attribute for event ${thisEvent}, Zwave report: ${cmd}."
		}
	}	
}


//////////////////////////////////////////////////////////////////////
//////      Get Device's Database Information           ///////
////////////////////////////////////////////////////////////////////// 


Map getSpecificRecord(id)
{
    String queryByDatabaseID= "http://www.opensmarthouse.org/dmxConnect/api/zwavedatabase/device/read.php?device_id=${id}"    
    
	httpGet([uri:queryByDatabaseID]) { resp-> 
				return resp?.data
			}
}

Map createDeviceDataRecord()
{
	Map firstQueryRecord = getOpenSmartHouseData();
	
	if (firstQueryRecord.is( null )) {
	log.error "Device ${device.displayName}: Failed to retrieve data record identifier for device from OpenSmartHouse Z-Wave database. OpenSmartHouse database may be unavailable. Try again later or check database to see if your device can be found in the database."
	}

	Map thisRecord = getSpecificRecord(firstQueryRecord.id)
	
	if (thisRecord.is( null )) {
	log.error "Device ${device.displayName}: Failed to retrieve data record for device from OpenSmartHouse Z-Wave database. OpenSmartHouse database may be unavailable. Try again later or check database to see if your device can be found in the database."
	}

	Map deviceRecord = [fingerprints: [] , endpoints: [:] , deviceInputs: null ]
	Map thisFingerprint = [manufacturer: (getDataValue("manufacturer")?.toInteger()) , deviceId: (getDataValue("deviceId")?.toInteger()) ,  deviceType: (getDataValue("deviceType")?.toInteger()) ]
	thisFingerprint.name = "${firstQueryRecord.manufacturer_name}: ${firstQueryRecord.label}" as String
	
	deviceRecord.fingerprints.add(thisFingerprint )
	
	deviceRecord.deviceInputs = createInputControls(thisRecord.parameters)
	deviceRecord.classVersions = getRootClassData(thisRecord.endpoints)
	deviceRecord.endpoints = getEndpointClassData(thisRecord.endpoints)
	deviceRecord.formatVersion = dataRecordFormatVersion
	
	return deviceRecord
}
	
void logDataRecord() {
	log.info "Data record is: \n${thisDeviceDataRecord.inspect()}"
}

// List getOpenSmartHouseData()
Map getOpenSmartHouseData()
{
	if (txtEnable) log.info "Getting data from OpenSmartHouse for device ${device.displayName}."
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2)
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2)
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2)

    String DeviceInfoURI = "http://www.opensmarthouse.org/dmxConnect/api/zwavedatabase/device/list.php?filter=manufacturer:0x${manufacturer}%20${deviceType}:${deviceID}"

    Map thisDeviceData
			
    httpGet([uri:DeviceInfoURI])
    { 
		resp ->
		Map maxRecord = resp.data.devices.max(			{ a, b -> 
				List<Integer> a_version = a.version_max.split("\\.")
				List<Integer> b_version = b.version_max.split("\\.")
			
				Float a_value = a_version[0].toFloat() + (a_version[1].toFloat() / 1000)
				Float b_value = b_version[0].toFloat() + (b_version[1].toFloat() / 1000)
				
				(a_value <=> b_value)
			})
		return maxRecord
	}
}

Map getRootClassData(endpointRecord) {
	endpointRecord.find{ it.number == 0}.commandclass.collectEntries{thisClass ->
					[(classMappings.get(thisClass.commandclass_name, 0) as Integer), (thisClass.version as Integer)]
				}
}

String getChildComponentDriver(List classes)
{
	if (classes.contains(0x25) ){  // Binary Switch
		if (classes.contains(0x32) ){ // Meter Supported
			return "Generic Component Metering Switch"
		} else {
			return "Generic Component Switch"
		}
	} else  if (classes.contains(0x26)){ // MultiLevel Switch
		if (classes.contains(0x32) ){ // Meter Supported
			return "Generic Component Metering Dimmer"
		} else {
			return "Generic Component Dimmer"
		}			
	}
	return "Generic Component Dimmer"
}

Map getEndpointClassData(endpointRecord)
{
	Map endpointClassMap = [:]

	endpointRecord.each{ it ->
			List thisEndpointClasses =  it.commandclass.collect{thisClass -> classMappings.get(thisClass.commandclass_name, 0) as Integer }
			
			if (it.number == 0) {
				endpointClassMap.put((it.number as Integer), [classes:(thisEndpointClasses)])
				return
			} else {
				String childDriver = getChildComponentDriver(thisEndpointClasses)
				endpointClassMap.put((it.number as Integer), [driver:[type:childDriver, namespace:"hubitat"], classes:(thisEndpointClasses)])
			}
		}
    return endpointClassMap
}

Map createInputControls(data)
{
	if (!data) return null
	
	Map inputControls = [:]	
	data?.each
	{
		if (it.read_only as Integer) {
				log.info "Device ${device.displayName}: Parameter ${it.param_id}-${it.label} is read-only. No input control created."
				return
			}
	
		if (it.bitmask.toInteger())
		{
			if (!(inputControls?.get(it.param_id)))
			{
				log.warn "Device ${device.displayName}: Parameter ${it.param_id} is a bitmap field. This is poorly supported. Treating as an integer - rely on your user manual for proper values!"
				String param_name_string = "${it.param_id}"
				String title_string = "(${it.param_id}) ${it.label} - bitmap"
				Map newInput = [name:param_name_string , type:"number", title: title_string, size:it.size]
				if ((it.description.size() != 0) && (it.description != it.label)) newInput.description = it.description
				
				inputControls.put(it.param_id, newInput)
			}
		} else {
			String param_name_string = "${it.param_id}"
			String title_string = "(${it.param_id}) ${it.label}"
			
			Map newInput = [name: param_name_string, title: title_string, size:it.size]
			if ((it.description.size() != 0) && (it.description != it.label)) newInput.description = it.description

			def deviceOptions = [:]
			it.options.each { deviceOptions.put(it.value, it.label) }
			
			// Set input type. Should be one of: bool, date, decimal, email, enum, number, password, time, text. See: https://docs.hubitat.com/index.php?title=Device_Preferences
			
			// use enum but only if it covers all of the choices!
			Integer numberOfValues = (it.maximum - it.minimum) +1
			if (deviceOptions && (deviceOptions.size() == numberOfValues) )
			{
				newInput.type = "enum"
				newInput.options = deviceOptions
			} else {
				newInput.type = "number"
				newInput.range = "${it.minimum}..${it.maximum}"
			}
			inputControls[it.param_id] = newInput
		}
	}
	return inputControls
}

@Field static Map classMappings = [
	COMMAND_CLASS_ALARM:0x71,
	COMMAND_CLASS_SENSOR_ALARM :0x9C,
	COMMAND_CLASS_SILENCE_ALARM:0x9D,
	COMMAND_CLASS_SWITCH_ALL:0x27,
	COMMAND_CLASS_ANTITHEFT:0x5D,
	COMMAND_CLASS_ANTITHEFT_UNLOCK:0x7E,
	COMMAND_CLASS_APPLICATION_CAPABILITY:0x57,
	COMMAND_CLASS_APPLICATION_STATUS:0x22,
	COMMAND_CLASS_ASSOCIATION:0x85,
	COMMAND_CLASS_ASSOCIATION_COMMAND_CONFIGURATION:0x9B,
	COMMAND_CLASS_ASSOCIATION_GRP_INFO:0x59,
	COMMAND_CLASS_AUTHENTICATION:0xA1,
	COMMAND_CLASS_AUTHENTICATION_MEDIA_WRITE:0xA2,
	COMMAND_CLASS_BARRIER_OPERATOR:0x66,
	COMMAND_CLASS_BASIC:0x20,
	COMMAND_CLASS_BASIC_TARIFF_INFO:0x36,
	COMMAND_CLASS_BASIC_WINDOW_COVERING:0x50,
	COMMAND_CLASS_BATTERY:0x80,
	COMMAND_CLASS_SENSOR_BINARY:0x30,
	COMMAND_CLASS_SWITCH_BINARY:0x25,
	COMMAND_CLASS_SWITCH_TOGGLE_BINARY:0x28,
	COMMAND_CLASS_CLIMATE_CONTROL_SCHEDULE:0x46,
	COMMAND_CLASS_CENTRAL_SCENE:0x5B,
	COMMAND_CLASS_CLOCK:0x81,
	COMMAND_CLASS_SWITCH_COLOR:0x33,
	COMMAND_CLASS_CONFIGURATION:0x70,
	COMMAND_CLASS_CONTROLLER_REPLICATION:0x21,
	COMMAND_CLASS_CRC_16_ENCAP:0x56,
	COMMAND_CLASS_DCP_CONFIG:0x3A,
	COMMAND_CLASS_DCP_MONITOR:0x3B,
	COMMAND_CLASS_DEVICE_RESET_LOCALLY:0x5A,
	COMMAND_CLASS_DOOR_LOCK:0x62,
	COMMAND_CLASS_DOOR_LOCK_LOGGING:0x4C,
	COMMAND_CLASS_ENERGY_PRODUCTION:0x90,
	COMMAND_CLASS_ENTRY_CONTROL :0x6F,
	COMMAND_CLASS_FIRMWARE_UPDATE_MD:0x7A,
	COMMAND_CLASS_GENERIC_SCHEDULE:0xA3,
	COMMAND_CLASS_GEOGRAPHIC_LOCATION:0x8C,
	COMMAND_CLASS_GROUPING_NAME:0x7B,
	COMMAND_CLASS_HAIL:0x82,
	COMMAND_CLASS_HRV_STATUS:0x37,
	COMMAND_CLASS_HRV_CONTROL:0x39,
	COMMAND_CLASS_HUMIDITY_CONTROL_MODE:0x6D,
	COMMAND_CLASS_HUMIDITY_CONTROL_OPERATING_STATE:0x6E,
	COMMAND_CLASS_HUMIDITY_CONTROL_SETPOINT:0x64,
	COMMAND_CLASS_INCLUSION_CONTROLLER:0x74,
	COMMAND_CLASS_INDICATOR:0x87,
	COMMAND_CLASS_IP_ASSOCIATION:0x5C,
	COMMAND_CLASS_IP_CONFIGURATION:0x9A,
	COMMAND_CLASS_IR_REPEATER:0xA0,
	COMMAND_CLASS_IRRIGATION:0x6B,
	COMMAND_CLASS_LANGUAGE:0x89,
	COMMAND_CLASS_LOCK:0x76,
	COMMAND_CLASS_MAILBOX:0x69,
	COMMAND_CLASS_MANUFACTURER_PROPRIETARY:0x91,
	COMMAND_CLASS_MANUFACTURER_SPECIFIC:0x72,
	COMMAND_CLASS_MARK:0xEF,
	COMMAND_CLASS_METER:0x32,
	COMMAND_CLASS_METER_TBL_CONFIG:0x3C,
	COMMAND_CLASS_METER_TBL_MONITOR:0x3D,
	COMMAND_CLASS_METER_TBL_PUSH:0x3E,
	COMMAND_CLASS_MTP_WINDOW_COVERING:0x51,
	COMMAND_CLASS_MULTI_CHANNEL:0x60,
	COMMAND_CLASS_MULTI_CHANNEL_ASSOCIATION:0x8E,
	COMMAND_CLASS_MULTI_CMD:0x8F,
	COMMAND_CLASS_SENSOR_MULTILEVEL:0x31,
	COMMAND_CLASS_SWITCH_MULTILEVEL:0x26,
	COMMAND_CLASS_SWITCH_TOGGLE_MULTILEVEL:0x29,
	COMMAND_CLASS_NETWORK_MANAGEMENT_BASIC:0x4D,
	COMMAND_CLASS_NETWORK_MANAGEMENT_INCLUSION:0x34,
	NETWORK_MANAGEMENT_INSTALLATION_MAINTENANCE:0x67,
	COMMAND_CLASS_NETWORK_MANAGEMENT_PRIMARY:0x54,
	COMMAND_CLASS_NETWORK_MANAGEMENT_PROXY:0x52,
	COMMAND_CLASS_NO_OPERATION:0x00,
	COMMAND_CLASS_NODE_NAMING:0x77,
	COMMAND_CLASS_NODE_PROVISIONING:0x78,
	COMMAND_CLASS_NOTIFICATION:0x71,
	COMMAND_CLASS_POWERLEVEL:0x73,
	COMMAND_CLASS_PREPAYMENT:0x3F,
	COMMAND_CLASS_PREPAYMENT_ENCAPSULATION:0x41,
	COMMAND_CLASS_PROPRIETARY:0x88,
	COMMAND_CLASS_PROTECTION:0x75,
	COMMAND_CLASS_METER_PULSE:0x35,
	COMMAND_CLASS_RATE_TBL_CONFIG:0x48,
	COMMAND_CLASS_RATE_TBL_MONITOR:0x49,
	COMMAND_CLASS_REMOTE_ASSOCIATION_ACTIVATE:0x7C,
	COMMAND_CLASS_REMOTE_ASSOCIATION:0x7D,
	COMMAND_CLASS_SCENE_ACTIVATION:0x2B,
	COMMAND_CLASS_SCENE_ACTUATOR_CONF:0x2C,
	COMMAND_CLASS_SCENE_CONTROLLER_CONF:0x2D,
	COMMAND_CLASS_SCHEDULE:0x53,
	COMMAND_CLASS_SCHEDULE_ENTRY_LOCK:0x4E,
	COMMAND_CLASS_SCREEN_ATTRIBUTES:0x93,
	COMMAND_CLASS_SCREEN_MD:0x92,
	COMMAND_CLASS_SECURITY:0x98,
	COMMAND_CLASS_SECURITY_2:0x9F,
	COMMAND_CLASS_SECURITY_SCHEME0_MARK :0xF100,
	COMMAND_CLASS_SENSOR_CONFIGURATION:0x9E,
	COMMAND_CLASS_SIMPLE_AV_CONTROL:0x94,
	COMMAND_CLASS_SOUND_SWITCH:0x79,
	COMMAND_CLASS_SUPERVISION:0x6C,
	COMMAND_CLASS_TARIFF_CONFIG:0x4A,
	COMMAND_CLASS_TARIFF_TBL_MONITOR:0x4B,
	COMMAND_CLASS_THERMOSTAT_FAN_MODE:0x44,
	COMMAND_CLASS_THERMOSTAT_FAN_STATE:0x45,
	COMMAND_CLASS_THERMOSTAT_MODE:0x40,
	COMMAND_CLASS_THERMOSTAT_OPERATING_STATE:0x42,
	COMMAND_CLASS_THERMOSTAT_SETBACK:0x47,
	COMMAND_CLASS_THERMOSTAT_SETPOINT:0x43,
	COMMAND_CLASS_TIME:0x8A,
	COMMAND_CLASS_TIME_PARAMETERS:0x8B,
	COMMAND_CLASS_TRANSPORT_SERVICE:0x55,
	COMMAND_CLASS_USER_CODE:0x63,
	COMMAND_CLASS_VERSION:0x86,
	COMMAND_CLASS_WAKE_UP:0x84,
	COMMAND_CLASS_WINDOW_COVERING:0x6A,
	COMMAND_CLASS_ZIP:0x23,
	COMMAND_CLASS_ZIP_6LOWPAN:0x4F,
	COMMAND_CLASS_ZIP_GATEWAY:0x5F,
	COMMAND_CLASS_ZIP_NAMING:0x68,
	COMMAND_CLASS_ZIP_ND:0x58,
	COMMAND_CLASS_ZIP_PORTAL:0x61,
	COMMAND_CLASS_ZWAVEPLUS_INFO:0x5E,
]


@Field static List deviceDatabase = 
[
	[
		formatVersion:1,
		fingerprints: [
				[manufacturer:838, deviceId: 769,  deviceType:769, name:"Ring G2 Motion Sensor"]
				],
		classVersions:[0:1, 85:0, 89:1, 90:1, 94:1, 108:0, 112:1, 113:8, 114:1, 115:1, 122:1, 128:1, 133:2, 134:2, 135:3, 142:3, 159:0], 
		endpoints:[
				0:[
					classes:[80, 85, 89, 90, 94, 108, 112, 113, 114, 115, 122, 128, 133, 134, 135, 142, 159], 
					notifications:[7:[3, 8], 8:[1, 5], 9:[4, 5]]]
				],
		deviceInputs:[
			1:[ size:1,	category:"advanced", name:"1", title:"(1) Heartbeat Interval", description:"Number of minutes between heartbeats.", range:"1..70",  type:"number" ],
			2:[ size:1,	category:"advanced", name:"2", title:"(2) Application Retries", description:"Number of application level retries attempted", range:"0..5", type:"number" ],
			3:[ size:1,	category:"advanced", name:"3", title:"(3) App Level Retry Base Wait", description:"Application Level Retry Base Wait Time Period (seconds)", range:"1..96", type:"number"],
			4:[ size:1,	category:"advanced", name:"4", title:"(4) LED Indicator Enable", description:"Configure the various LED indications on the device", options:[0:"Don’t show green", 1:"Show green after Supervision Report Intrusion (Fault)", 2:"Show green after Supervision Report both Intrusion and Intrusion clear"], type:"enum" ],
			5:[ size:1,	category:"advanced", name:"5", title:"(5) Occupancy Signal Clear", range:"0..255", type:"number"],
			6:[ size:1,	category:"advanced", name:"6", title:"(6) Intrusion Clear Delay", range:"0..255", type:"number"],
			7:[ size:1,	category:"advanced", name:"7", title:"(7) Standard Delay Time", range:"0..255", type:"number"],
			8:[ size:1,	category:"basic", name:"8", title:"(8) Motion Detection Mode", description:"Adjusts motion sensitivity, 0 = low ... 4 = high", range:"0..4", type:"number" ],
			9:[ size:1,	category:"advanced", name:"9", title:"(9) Lighting Enabled", range:"0..1", type:"number"],
			10:[size:1, category:"advanced", name:"10", title:"(10) Lighting Delay", description:"Delay to turn off lights when motion no longer detected", range:"0..60", type:"number"],
			11:[size:2, category:"advanced", name:"11", title:"(11) Supervisory Report Timeout", range:"500..30000", type:"number"]
		]
	],
	[
	formatVersion:1, 
	fingerprints:[['manufacturer':634, 'deviceId':18, 'deviceType':769, name:'Zooz: ZSE18']], 
	classVersions:[89:1, 48:2, 152:0, 0:1, 132:2, 122:1, 133:2, 112:1, 134:2, 113:5, 114:1, 115:1, 159:0, 90:1, 128:1, 108:0, 94:1, 85:0, 32:1], 
	endpoints:[
		0:[classes:[0, 32, 48, 85, 89, 90, 94, 108, 112, 113, 114, 115, 122, 128, 132, 133, 134, 152, 159], 'notifications':[7:[8, 9]]]
		], 
	deviceInputs:[
		12:[size:1, name:'12', description:' 1 = low sensitivity and 8 = high sensitivity.', range:'1..8', title:'(12)  PIR sensor sensitivity', type:'number'], 
		14:[size:1, name:'14', options:[0:'Disabled', 1:'Enabled'], title:'(14) BASIC SET reports', type:'enum'], 
		15:[size:1, name:'15', options:[0:'Send 255 on motion, 0 on clear (normal)', 1:'Send 0 on motion, 255 on clear (reversed)'], title:'(15) reverse BASIC SET', type:'enum'], 
		17:[size:1, name:'17', options:[0:'Disabled', 1:'Enabled'], title:'(17) vibration sensor', type:'enum'], 
		18:[size:2, name:'18', description:'3=6 seconds, 65535=65538 seconds (add 3 seconds to value set)', range:'3..65535', title:'(18) trigger interval', type:'number'], 
		19:[size:1, name:'19', options:[ 0:'Send Notification Reports to Hub', 1:'Send Binary Sensor Reports to Hub'], title:'(19) Report Type', type:'enum'], 		
		20:[size:1, name:'20', options:[0:'Disabled', 1:'Enabled'],title:'(20) LED Indicator', type:'enum'], 
		32:[size:1, name:'32', description:'percent battery left', range:'10..50', title:'(32) Low Battery Alert', type:'number']]
	],	
	[
		formatVersion:1,
		fingerprints: [
				[manufacturer:0x0184, 	deviceId: 0x3034,  deviceType:0x4447, name:"Dragon Tech WD100"],
				[manufacturer:0x000C, 	deviceId: 0x3034,  deviceType:0x4447, name:"HomeSeer WD100+"],
				[manufacturer:0x0315, 	deviceId: 0x3034,  deviceType:0x4447, name:"ZLink Products WD100+"],
				],
		classVersions: [89:1, 38:1, 39:1, 122:2, 133:2, 112:1, 134:2, 114:2, 115:1, 90:1, 91:1, 94:1, 32:1, 43:1],
		endpoints:[
				0:[classes:[94, 134, 114, 90, 133, 89, 115, 38, 39, 112, 44, 43, 91, 122]]
				],
		deviceInputs:[
			4:[ size:1,	category:"basic", name:"4", title:"(4) Orientation", description:"Control the on/off orientation of the rocker switch", options:[0:"Normal", 1:"Inverted"], type:"enum" ],

			7:[ size:1,	category:"basic", name:"7", title:"(7) Remote Dimming Level Increment", range:"1..99", type:"number"],
			8:[ size:2,	category:"basic", name:"8", title:"(8) Remote Dimming Level Duration", description:"Time interval (in tens of ms) of each brightness level change when controlled locally", range:"0..255", type:"number" ],
			9:[ size:1,	category:"basic", name:"9", title:"(9) Local Dimming Level Increment", range:"1..99", type:"number"],
			10:[size:2, category:"basic", name:"10", title:"(10) Local Dimming Level Duration", description:"Time interval (in tens of ms) of each brightness level change when controlled locally", range:"0..255", type:"number"]
		]
	],
	[
	
		formatVersion:1, 
		fingerprints: [
				[manufacturer:0x000C, 	deviceId: 0x3033,  deviceType:0x4447, name:"HomeSeer WS100 Switch"],
				],
		classVersions:[44:1, 89:1, 37:1, 39:1, 0:1, 122:1, 133:1, 112:1, 134:1, 114:1, 115:1, 90:1, 91:1, 94:1, 32:1, 43:1], 
		endpoints:[	
					0:[classes:[0, 32, 37, 39, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]]
				], 
		deviceInputs:[
			3:[ size:1, level:"basic", name:"3", title:"(3) LED Indication Configuration", options:[0:"LED On when device is Off", 1:"LED On when device is On", 2:"LED always Off", 4:"LED always On"], type:"enum" ],
			4:[ size:1, level:"advanced", name:"4", title:"(4) Orientation", description:"Controls the on/off orientation of the rocker switch", options:[0:"Normal", 1:"Inverted"], type:"enum"],
		]
	],
	[
		formatVersion:1, 
		fingerprints:[[manufacturer:12, deviceId:12342, deviceType:17479, name:'HomeSeer Technologies: HS-WD200+']], 
		classVersions:[44:1, 89:1, 38:3, 39:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1, 43:1], 
		endpoints:[
					0:[classes:[0, 32, 38, 39, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]]
				], 
		deviceInputs:[ // Firmware 5.14 and above!
			3:[size:1, name:'3', options:['0':'Bottom LED ON if load is OFF', '1':'Bottom LED OFF if load is OFF'], title:'(3) Bottom LED Operation', type:'enum'], 
			4:[size:1, name:'4', options:['0':'Normal - Top of Paddle turns load ON', '1':'Inverted - Bottom of Paddle turns load ON'], title:'(4) Paddle Orientation', type:'enum'], 
			5:[size:1, name:'5', options:['0':'(0) No minimum set', '1':'(1) 6.5%', '2':'(2) 8%', '3':'(3) 9%', '4':'(4) 10%', '5':'(5) 11%', '6':'(6) 12%', '7':'(7) 13%', '8':'(8) 14%', '9':'(9) 15%', '10':'(10) 16%', '11':'(11) 17%', '12':'(12) 18%', '13':'(13) 19%', '14':'(14) 20%'], title:'(5) Minimum Dimming Level', type:'enum'],
 			6:[size:1, name:'6', options:['0':'Central Scene Enabled', '1':'Central Scene Disabled'], title:'(5) Central Scene Enable/Disable', type:'enum'], 			
			11:[size:1, name:'11', range:'0..90', title:'(11) Set dimmer ramp rate for remote control (seconds)', type:'number'], 
			12:[size:1, name:'12', range:'0..90', title:'(12) Set dimmer ramp rate for local control (seconds)', type:'number'], 
			13:[size:1, name:'13', options:['0':'LEDs show load status', '1':'LEDs display a custom status'], description:'Set dimmer display mode', title:'(13) Status Mode', type:'enum'], 	
			14:[size:1, name:'14', options:['0':'White', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan'], title:'(14) Set the LED color when displaying load status', type:'enum'], 		
			21:[size:1, name:'21', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(21) Status LED 1 Color (bottom LED)', type:'enum'],
			22:[size:1, name:'22', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(22) Status LED 2 Color', type:'enum'], 
			23:[size:1, name:'23', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(23) Status LED 3 Color', type:'enum'], 

			24:[size:1, name:'24', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(24) Status LED 4 Color', type:'enum'], 

			25:[size:1, name:'25', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(25) Status LED 5 Color', type:'enum'], 
			26:[size:1, name:'26', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(26) Status LED 6 Color', type:'enum'], 
			27:[size:1, name:'27', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(27) Status LED 7 Color (top LED)', type:'enum'], 
			30:[size:1, name:'30', range:'0..255', title:'(30) Blink Frequency when displaying custom status', type:'number'], 
			31:[size:1, name:'31', type:'number', title:'(31) LED 7 Blink Status - bitmap', description:'bitmap defines LEDs to blink '], 

		]
	],
	[
		formatVersion:1, 
		fingerprints:[
			[manufacturer:12, deviceId:12341, deviceType:17479, name:'HomeSeer Technologies: HS-WS200+']
			], 
		classVersions:[44:1, 89:1, 37:1, 39:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1, 43:1], 
		endpoints:[
					0:[classes:[0, 32, 37, 39, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]]
				], 
		deviceInputs:[
			3:[size:1, name:'3', options:[0:'LED ON if load is OFF', '1':'LED OFF if load is OFF'], description:'Sets LED operation (in normal mode)', title:'(3) Bottom LED Operation', type:'enum'], 
			4:[size:1, name:'4', options:[0:'Top of Paddle turns load ON', '1':'Bottom of Paddle turns load ON'], description:'Sets paddle’s load orientation', title:'(4) Orientation', type:'enum'], 
			6:[size:1, name:'6', options:[0:'Disabled', '1':'Enabled'], description:'Enable or Disable Scene Control', title:'(6) Scene Control', type:'enum'], 
			13:[size:1, name:'13', options:[0:'Normal mode (load status)', '1':'Status mode (custom status)'], description:'Sets switch mode of operation', title:'(13) Status Mode', type:'enum'], 
			14:[size:1, name:'14', options:['0':'White', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan'], description:'Sets the Normal mode LED color', title:'(14) Load Status LED Color', type:'enum'], 
			21:[size:1, name:'21', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], description:'Sets the Status mode LED color', title:'(21) Status LED Color', type:'enum'],
			31:[size:1, name:'31', description:'Sets the switch LED Blink frequency', range:'0..255', title:'(31) Blink Frequency', type:'number'],
		]
	],
	[
		formatVersion:1, 
		fingerprints:[
			[manufacturer:99, deviceId:12597, deviceType:18770, name:'Jasco Products: 46201']
			], 
		classVersions:[44:1, 34:1, 89:1, 37:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1, 43:1], 
		endpoints:[
				0:[classes:[0, 32, 34, 37, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]]
			], 
		deviceInputs:[
			3:[size:1, name:'3', range:'0..255', title:'(3) Blue LED Night Light', type:'number']
		]
	],
	[
		formatVersion:1, 
		fingerprints:[[manufacturer:99, deviceId:12338, deviceType:20292, name:'GE/Jasco Heavy Duty Switch 14285']], 
		classVersions:[44:1, 89:1, 37:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 50:3, 94:1, 32:1, 43:1], 
		endpoints:[
				0:[	classes:[0, 32, 37, 43, 44, 50, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134], 
					metersSupported:[0, 2, 4, 5, 6]]
			], 
		deviceInputs:[
			1:[size:1, name:'1', options:['0':'Return to last state', '1':'Return to off', '2':'Return to on'], title:'(1) Product State after Power Reset', type:'enum'], 
			2:[size:1, name:'2', options:['0':'Once monthly', '1':'Reports based on Parameter 3 setting', '2':'Once daily'], title:'(2) Energy Report Mode', type:'enum'], 
			3:[size:1, name:'3', range:'5..60', title:'(3) Energy Report Frequency', type:'number'], 
			19:[size:1, name:'19', options:['0':'Default', '1':'Alternate Exclusion (3 button presses)'], title:'(19) Alternate Exclusion', type:'enum']
			]
	],
	[
		formatVersion:1, 
		fingerprints:[
				[manufacturer:634, deviceId:40963, deviceType:40960, name:'Zooz: ZEN25']
			], 
		classVersions:[0:1, 32:1, 37:1, 50:3, 89:1, 90:1, 94:1, 96:2, 112:1, 113:8, 114:1, 115:1, 122:1, 133:2, 134:2, 142:3], 
		endpoints:[
				0:[	classes:[0, 32, 37, 50, 89, 90, 94, 96, 112, 113, 114, 115, 122, 133, 134, 142]], 
				1:[ driver:[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 Left Outlet"], 
					classes:[32, 37, 50, 89, 94, 133, 142], 
					metersSupported:[0, 2, 4, 5]], 
				2:[ driver:[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 Right Outlet"], 
					classes:[32, 37, 50, 89, 94, 133, 142], 
					metersSupported:[0, 2, 4, 5]], 
				3:[ driver:[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 USB Port"], 
					classes:[32, 37, 50, 89, 94, 133, 142]]
			], 
		deviceInputs:[
				1:[name:'1', title:'(1) On/Off After Power', size:1, description:'On/Off Status After Power Failure', type:'enum', options:[0:'Previous State', 1:'On', 2:'Off']], 
				2:[name:'2', title:'(2) Wattage Threshold', size:4, description:'Power Wattage Report Value Threshold', type:'number', range:0..65535], 
				3:[name:'3', title:'(3) Wattage Frequency', size:4, description:'Power Wattage Report Frequency', type:'number', range:30..2678400], 
				4:[name:'4', title:'(4) Energy Frequency', size:4, description:'Energy (kWh) Report Frequency', type:'number', range:30..2678400], 
				5:[name:'5', title:'(5) Voltage Frequency', size:4, description:'Voltage (V) Report Frequency', type:'number', range:30..2678400], 
				6:[name:'6', title:'(6) Current Frequency', size:4, description:'Electrical Current (A) Report Frequency', type:'number', range:30..2678400], 
				7:[name:'7', title:'(7) Overload Protection', size:1, type:'number', range:1..10], 
				8:[name:'8', title:'(8) Enable Auto-Off (Left)', size:1, description:'Enable Auto Turn-Off Timer for Left Outlet', type:'enum', options:[0:'Disable', 1:'Enable']], 
				9:[name:'9', title:'(9) Turn-Off Time, Minutes (Left)', size:4, description:'Auto Turn-Off Time for Left Outlet', type:'number', range:1..65535], 
				10:[name:'10', title:'(10) Enable Auto-On (Left)', size:1, description:'Enable Auto Turn-On Timer for Left Outlet', type:'enum', options:[0:'Disable', 1:'Enable']], 
				11:[name:'11', title:'(11) Turn-On Time, Minutes (Left)', size:4, description:'Auto Turn-On Time for Left Outlet', type:'number', range:1..65535], 
				12:[name:'12', title:'(12) Enable Auto-Off (Right)', size:1, description:'Enable Auto Turn-Off Timer for Right Outlet', type:'enum', options:[0:'Disable', 1:'Enable']], 
				13:[name:'13', title:'(13) Turn-Off Time, Minutes (Right)', size:4, description:'Auto Turn-Off Time for Right Outlet', type:'number', range:1..65535], 
				14:[name:'14', title:'(14) Enable Auto-On (Right)', size:1, description:'Enable Auto Turn-On Timer for Right Outlet', type:'enum', options:[0:'Disable', 1:'Enable']], 
				15:[name:'15', title:'(15) Turn-On Time, Minutes (Right)', size:4, description:'Auto Turn-On Time for Right Outlet', type:'number', range:1..65535], 
				16:[name:'16', title:'(16) Manual Control', size:1, description:'Enable/Disable Manual Control', type:'enum', options:[0:'Disable', 1:'Enable']], 
				17:[name:'17', title:'(17) LED Mode', size:1, description:'LED Indicator Mode', type:'enum', options:[0:'Always On', 1:'Follow Outlet', 2:'Momentary', 3:'Always Off']], 
				18:[name:'18', title:'(18) Reports', size:1, description:'Enable/Disable Energy and USB Reports', type:'enum', options:[0:'0 - Energy and USB reports enabled ', 1:'1 - Energy and USB reports disabled', 2:'2 - Energy reports for left outlet disabled', 3:'3 - Energy reports for right outlet disabled', 4:'4 - USB reports disabled']]
			]
	],	
	[
		formatVersion:1,
		fingerprints: [
				[manufacturer:0x031E, deviceId: 0x0001,  deviceType:0x000E, name:"Inovelli LZW36 Light / Fan Controller"]
				],
		classVersions: [32:1, 34:1, 38:3, 50:3, 89:1, 90:1, 91:3, 94:1, 96:2, 112:1, 114:1, 115:1, 117:2, 122:1, 133:2, 134:2, 135:3, 142:3, 152:1],
		endpoints:[ // List of classes for each endpoint. Key classes:  0x25 (37) or 0x26 (38), 0x32 (50), 0x70  (switch, dimmer, metering, 
					0:[ classes:[32, 34, 38, 50, 89, 90, 91, 94, 96, 112, 114, 115, 117, 122, 133, 134, 135, 142, 152]],
					1:[	driver:[type:"Generic Component Dimmer", namespace:"hubitat", childName:"LZW36 Dimmer Device"], 
						classes:[0x26] ], 
					2:[	driver:[type:"Generic Component Fan Control", namespace:"hubitat", childName:"LZW36 Fan Device"], 
						classes:[0x26] ]
				],
		deviceInputs:[
				1:[size:1, name:"1", range:"0..99", title:"(1) Light Dimming Speed (Remote)", type:"number"], 
				2:[size:1, name:"2", range:"0..99", title:"(2) Light Dimming Speed (From Switch)", type:"number"], 
				3:[size:1, name:"3", range:"0..99", title:"(3) Light Ramp Rate (Remote)", type:"number"], 
				4:[size:1, name:"4", range:"0..99", title:"(4) Light Ramp Rate (From Switch)", type:"number"], 
				5:[size:1, name:"5", range:"1..45", title:"(5) Minimum Light Level", type:"number"], 
				6:[size:1, name:"6", range:"55..99", title:"(6) Maximum Light Level", type:"number"], 
				7:[size:1, name:"7", range:"1..45", title:"(7) Minimum Fan Level", type:"number"], 
				8:[size:1, name:"8", range:"55..99", title:"(8) Maximum Fan Level", type:"number"], 
				10:[size:2, name:"10", range:"0..32767", title:"(10) Auto Off Light Timer", type:"number"], 
				11:[size:2, name:"11", range:"0..32767", title:"(11) Auto Off Fan Timer", type:"number"], 
				12:[size:1, name:"12", range:"0..99", title:"(12) Default Light Level (Local)", type:"number"], 
				13:[size:1, name:"13", range:"0..99", title:"(13) Default Light Level (Z-Wave)", type:"number"], 
				14:[size:1, name:"14", range:"0..99", title:"(14) Default Fan Level (Local)", type:"number"], 
				15:[size:1, name:"15", range:"0..99", title:"(15) Default Fan Level (Z-Wave)", type:"number"], 
				16:[size:1, name:"16", range:"0..100", title:"(16) Light State After Power Restored", type:"number"], 
				17:[size:1, name:"17", range:"0..100", title:"(17) Fan State After Power Restored", type:"number"], 
				18:[size:2, name:"18", range:"0..255", title:"(18) Light LED Indicator Color", type:"number"], 
				19:[size:1, name:"19", range:"0..10", title:"(19) Light LED Strip Intensity", type:"number"], 
				20:[size:2, name:"20", range:"0..255", title:"(20) Fan LED Indicator Color", type:"number"], 
				21:[size:1, name:"21", range:"0..10", title:"(21) Fan LED Strip Intensity", type:"number"],
				22:[size:1, name:"22", range:"0..10", title:"(22) Light LED Strip Intensity (When OFF)", type:"number"], 
				23:[size:1, name:"23", range:"0..10", title:"(23) Fan LED Strip Intensity (When OFF)", type:"number"], 
				24:[size:4, name:"24", range:"0..83823359", title:"(24) Light LED Strip Effect", type:"number"], 
				25:[size:4, name:"25", range:"0..83823359", title:"(25) Fan LED Strip Effect", type:"number"], 
				26:[size:1, name:"26", options:[0:"Stay Off", 1:"One Second", 2:"Two Seconds", 3:"Three Seconds", 4:"Four Seconds", 5:"Five Seconds", 6:"Six Seconds", 7:"Seven Seconds", 8:"Eight Seconds", 9:"Nine Seconds", 10:"Ten Seconds"], title:"(26) Light LED Strip Timeout", type:"enum"], 
				27:[size:1, name:"27", options:[0:"Stay Off", 1:"One Second", 2:"Two Seconds", 3:"Three Seconds", 4:"Four Seconds", 5:"Five Seconds", 6:"Six Seconds", 7:"Seven Seconds", 8:"Eight Seconds", 9:"Nine Seconds", 10:"Ten Seconds"], title:"(27) Fan LED Strip Timeout", type:"enum"], 
				28:[size:1, name:"28", range:"0..100", title:"(28) Active Power Reports", type:"number"], 
				29:[size:2, name:"29", range:"0..32767", title:"(29) Periodic Power & Energy Reports", type:"number"], 
				30:[size:1, name:"30", range:"0..100", title:"(30) Energy Reports", type:"number"], 
				31:[size:1, name:"31", options:[0:"None", 1:"Light Button", 2:"Fan Button", 3:"Both Buttons"], title:"(31) Local Protection Settings", type:"enum"], 
				51:[size:1, name:"51", description:"Disable the 700ms Central Scene delay.", title:"(51) Enable instant on", options:[0:"No Delay (Central scene Disabled)", 1:"700mS Delay (Central Scene Enabled)"], type:"enum"], 
		]
	],
	[
		formatVersion:1, 'fingerprints':[['manufacturer':634, 'deviceId':40961, 'deviceType':40960, name:'Zooz: ZEN26']], 
		classVersions:[89:1, 37:1, 142:3, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1], 
		endpoints:[ 0:[classes:[0, 32, 37, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134, 142]]
				], 
		deviceInputs:[
			11:[size:1, name:'11', options:['0':'Local control disabled', '1':'Local control enabled'], description:'Enable or disable local ON/OFF control', title:'(11) Enable/disable paddle control', type:'enum'], 
			1:[size:1, name:'1', description:'Choose paddle functionality (invert)', range:'0..1', title:'(1) Paddle control', type:'number'], 
			2:[size:1, name:'2', options:['0':'LED ON when switch OFF', '1':'LED ON when switch ON', '2':'LED OFF', '3':'LED ON'], description:'Change behavior of the LED indicator', title:'(2) LED indicator control', type:'enum'], 
			3:[size:1, name:'3', options:['0':'Disable', '1':'Enable'], description:'Enable/disable turn-OFF timer', title:'(3) Auto turn-OFF timer', type:'enum'], 
			4:[size:4, name:'4', description:'Length of time before switch turns OFF', range:'0..65535', title:'(4) Auto turn-OFF timer length', type:'number'], 
			5:[size:1, name:'5', options:['0':'Disable', '1':'Enable'], description:'Enable/disable turn-ON timer', title:'(5) Auto turn-ON timer', type:'enum'], 
			6:[size:4, name:'6', description:'Length of time before switch turns ON', range:'0..65535', title:'(6) Auto turn-ON timer length', type:'number'], 
			7:[size:1, name:'7', options:['11':'Physical tap ZEN26, 3-way switch or timer', '12':'Z-Wave command or timer', '13':'Physical tap ZEN26, Z-Wave or timer', '14':'Physical tap ZEN26, 3-way switch, Z-Wave or timer', '15':'All of the above', '0':'None', '1':'Physical tap ZEN26 only', '2':'Physical tap 3-way switch only', '3':'Physical tap ZEN26 or 3-way switch', '4':'Z-Wave command', '5':'Physical tap ZEN26 or Z-Wave', '6':'Physical tap 3-way switch or Z-Wave', '7':'Physical tap ZEN26, 3-way switch or Z-Wave', '8':'Timer only', '9':'Physical tap ZEN26 or timer', '10':'Physical tap 3-way switch or timer'], title:'(7) Association reports', type:'enum'], 
			8:[size:1, name:'8', options:['0':'OFF', '1':'ON', '2':'Restore last state'], description:'Set the ON/OFF status for the switch after power failure', title:'(8) ON/OFF status after power failure', type:'enum'], 
			10:[size:1, name:'10', options:['0':'Scene control disabled', '1':'Scene control enabled'], title:'(10) Enable/disable scene control', type:'enum']
		]
	],	
	[
		formatVersion:1, 
		fingerprints:[
				['manufacturer':634, 'deviceId':40968, 'deviceType':40960, name:'Zooz: ZEN30'] // Zooz Zen 30
			], 
		classVersions:[0:1, 32:1, 37:1, 38:3, 89:1, 90:1, 91:3, 94:1, 96:2, 112:1, 114:1, 115:1, 122:1, 133:2, 134:2, 142:3, 152:2], 
		endpoints:[
					0:[classes:[0, 32, 37, 38, 89, 90, 91, 94, 96, 112, 114, 115, 122, 133, 134, 142, 152]], 
					1:[ driver:[type:'Generic Component Switch', namespace:'hubitat', childName:"Relay Switch"], 
						classes:[32, 37, 89, 94, 133, 142]]
				], 
		deviceInputs:[
			1:[name:'1', title:'(1) LED Indicator Mode for Dimmer', size:1, type:'enum', options:[0:'ON when switch is OFF and OFF when switch is ON', 1:'ON when switch is ON and OFF when switch is OFF', 2:'LED indicator is always OFF', 3:'LED indicator is always ON']], 
			2:[name:'2', title:'(2) LED Indicator Control for Relay', size:1, type:'enum', options:[0:'ON when relay is OFF and OFF when relay is ON', 1:'ON when relay is ON and OFF when relay is OFF', 2:'LED indicator is always OFF', 3:'LED indicator is always ON']], 
			3:[name:'3', title:'(3) LED Indicator Color for Dimmer', size:1, description:'Choose the color of the LED indicators for the dimmer', type:'enum', options:[0:'White (default)', 1:'Blue', 2:'Green', 3:'Red']], 
			4:[name:'4', title:'(4) LED Indicator Color for Relay', size:1, type:'enum', options:[0:'White (default)', 1:'Blue', 2:'Green', 3:'Red']], 
			5:[name:'5', title:'(5) LED Indicator Brightness for Dimmer', size:1, type:'enum', options:[0:'Bright (100%)', 1:'Medium (60%)', 2:'Low (30% - default)']], 
			6:[name:'6', title:'(6) LED Indicator Brightness for Relay', size:1, type:'enum', options:[0:'Bright (100%)', 1:'Medium (60%)', 2:'Low (30% - default)']], 
			7:[name:'7', title:'(7) LED Indicator Mode for Scene Control', size:1, type:'enum', options:[0:'Enabled to indicate scene triggers', 1:'Disabled to indicate scene triggers (default)']], 
			8:[name:'8', title:'(8) Auto Turn-Off Timer for Dimmer', size:4, type:'number', range:"0..65535"], 
			9:[name:'9', title:'(9) Auto Turn-On Timer for Dimmer', size:4, type:'number', range:"0..65535"], 
			10:[name:'10', title:'(10) Auto Turn-Off Timer for Relay', size:4, type:'number', range:"0..65535"], 
			11:[name:'11', title:'(11) Auto Turn-On Timer for Relay', size:4, type:'number', range:"0..65535"], 
			12:[name:'12', title:'(12) On Off Status After Power Failure', size:1, type:'enum', options:[0:'Dimmer and relay forced to OFF', 1:'Dimmer forced to OFF, relay forced to ON', 2:'Dimmer forced to ON, relay forced to OFF', 3:'Restores status for dimmer and relay (default)', 4:'Restores status for dimmer, relay forced to ON', 5:'Restores status for dimmer, relay forced to OFF', 6:'Dimmer forced to ON, restores status for relay', 7:'Dimmer forced to OFF, restores status for relay', 8:'Dimmer and relay forced to ON']], 
			13:[name:'13', title:'(13) Ramp Rate Control for Dimmer', size:1, type:'number', range:"0..99"], 
			14:[name:'14', title:'(14) Minimum Brightness', size:1, type:'number', range:"1..99"], 
			15:[name:'15', title:'(15) Maximum Brightness', size:1, type:'number', range:"1..99"], 
			17:[name:'17', title:'(17) Double Tap Function for Dimmer', size:1, type:'enum', options:[0:'ON to full brightness with double tap (default)', 1:'ON to brightness set in #15 with double tap']], 
			18:[name:'18', title:'(18) Disable Double Tap', size:1, type:'enum', options:[0:'Full/max brightness level enabled (default)', 1:'Disabled, single tap for last brightness', 2:'Disabled, single tap to full brightness']], 
			19:[name:'19', title:'(19) Smart Bulb Setting', size:1, description:'Enable/Disable Load Control for Dimmer', type:'enum', options:[0:'Manual control disabled', 1:'Manual control enabled (default)', 2:'Manual and Z-Wave control disabled']], 
			20:[name:'20', title:'(20) Remote Control Setting', size:1, description:'Enable/Disable Load Control for Relay', type:'enum', options:[0:'Manual control disabled', 1:'Manual control enabled (default)', 2:'Manual and Z-Wave control disabled']], 
			21:[name:'21', title:'(21) Manual Dimming Speed', size:1, type:'number', range:"1..99"], 
			// 22:[name:'22', title:'(22) Z-Wave Ramp Rate for Dimmer', size:1, type:'enum', options:[0:'Match #13', 1:'Set through Command Class']], 
			23:[name:'23', title:'(23) Default Brightness Level ON for Dimmer', size:1, type:'number', range:"0..99"]	
		]
	]	
]
