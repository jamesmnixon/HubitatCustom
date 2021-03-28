import java.util.concurrent.* // Available (white-listed) concurrency classes: ConcurrentHashMap, ConcurrentLinkedQueue, Semaphore, SynchronousQueue
import groovy.transform.Field

/*
	Schlage Component Lock
	For locks, the setCodeLength function is device-specific. Accordingly, most functionality is implemented
	at the parent level, but a separate component driver "stub" is provided, and a child device, created
	for the actual lock. The setCodeLength function is implemented in the stub.
*/
metadata {
    definition(name: "Schlage BE469NX Component Lock", namespace: "jvm", author: "jvm", component: true) {
        capability "Actuator"
		capability "Sensor"
		capability "Lock"
        capability "LockCodes"
		capability "Battery"
		capability "TamperAlert"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void updated() {
    log.info "Updated..."
    log.warn "description logging is: ${txtEnable == true}"
}

void installed() {
    log.info "Installed..."
    refresh()
}

void parse(String description) { log.warn "parse(String description) not implemented" }

void parse(List<Map> description) {
    description.each {
        if (it.name in ["lock","codeChanged", "codeLength", "lockCodes", "maxCodes", "battery", "tamper"]) {
            if (txtEnable) log.info it.descriptionText
            sendEvent(it)
        }
    }
}

void lock() {
    parent?.componentLock(cd:this.device)
}

void unlock() {
    parent?.componentUnlock(cd:this.device)
}

void deleteCode(codeposition) {
    parent?.componentDeleteCode(cd:this.device , codeposition:codeposition)
}

void getCodes() {
    parent?.componentGetCodes(cd:this.device)
}

void setCode(codeposition, pincode, name) {
    parent?.componentSetCode(cd:this.device, codeposition:codeposition, pincode:pincode, name:name)
}

void refresh() {
    parent?.componentRefresh(cd:this.device)
}

void setCodeLength(pincodelength) {
	log.info "Device ${device.displayName}: Attempting to change PIN length to length $pincodelength."
	def newValue =	parent?.setParameter(parameterNumber: 16, value:pincodelength)
log.debug "New Pincode Length is: ${newValue}."
	sendEvent(name:"codeLength", value:newValue, descriptionText: "Pincode Length set")
	
}
