/*
*Simple Bulk Get Tester
*/

import groovy.transform.Field
@Field def driverVersion = 0.1

metadata {
    definition (name: "Testing Configuration Bulk Get", namespace: "jvm", author:"jvm") {

        capability "Configuration"
        command "getBulk"
        command "getIndividual"
    }
}

@Field static Map CMD_CLASS_VERS=[0x70:2]
// 0x70 = 112 == 

//////////////////////////////////////////////////////////////////////
//////     code to Test Configuration Bulk Get Capabilities   ///////
////////////////////////////////////////////////////////////////////// 

void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationBulkReport cmd) {
log.debug "Configuration Buk Report ${cmd}"
}

void getBulk()
{
    log.debug "Sending a bulk get"

    sendToDevice( secure(zwave.configurationV2.configurationBulkGet(numberOfParameters:10, parameterOffset: 1)))
}
void getIndividual()
{
    log.debug "Sending individual gets"

    Short param = 0
    for( param  = 1; param <= 12; param++)
    {
    sendToDevice( secure(zwave.configurationV2.configurationGet(parameterNumber:param as Short)))
    }
}

//////////////////////////////////////////////////////////////////////
//////        Handle Startup and Configuration Tasks           ///////
//////   Refresh, Initialize, Configuration Capabilities       ///////
////////////////////////////////////////////////////////////////////// 

void configure() {
getBulk()
}

//////////////////////////////////////////////////////////////////////
//////                  Z-Wave Helper Functions                ///////
//////   Format messages, Send to Device, secure Messages      ///////
////////////////////////////////////////////////////////////////////// 

void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand)
    }
}

void parse(String description) {
    // log.debug "parse:${description}"
    hubitat.zwave.Command cmd = zwave.parse(description, [0x70:1])
    if (cmd) {
        zwaveEvent(cmd)
    }
}

void sendToDevice(List<hubitat.zwave.Command> cmds) {
    sendHubCommand(new hubitat.device.HubMultiAction(commands(cmds), hubitat.device.Protocol.ZWAVE))
}

void sendToDevice(hubitat.zwave.Command cmd) {
    sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE))
}

void sendToDevice(String cmd) {
    sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE))
}

List<String> commands(List<hubitat.zwave.Command> cmds, Long delay=200) {
    return delayBetween(cmds.collect{ secureCommand(it) }, delay)
}


String secure(String cmd){
    return zwaveSecureEncap(cmd)
}

String secure(hubitat.zwave.Command cmd){
    return zwaveSecureEncap(cmd)
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
   //  log.debug "Received a configuratino Report"
    log.debug "${cmd}"
}

void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
   //  log.debug "Received a configuratino Report"
    log.debug "${cmd}"
}

void zwaveEvent(hubitat.zwave.Command cmd) {
    log.debug "${cmd}"
}
