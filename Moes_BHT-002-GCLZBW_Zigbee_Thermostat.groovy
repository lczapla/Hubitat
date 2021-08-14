import hubitat.helper.HexUtils

metadata {
    definition (name: "Moes BHT-002-GCLZBW Thermostat", namespace: "Moes", author: "LCzapla") {
        capability "Configuration"
        capability "TemperatureMeasurement"
        capability "Thermostat"
        capability "ThermostatSetpoint"
        capability "ThermostatHeatingSetpoint"
        //capability "ThermostatCoolingSetpoint"
        capability "Switch"
        capability "Refresh"
        
        command "setThermostatSetpoint", ["number"]
        command "childLockOn"
        command "childLockOff"

        attribute "windowOpenDetected","String"
        attribute "childLock","String"
        attribute "lastRunningMode", "string"
        
        fingerprint endpointId: "01", profileId: "0104", inClusters: "0000,0004,0005,EF00", outClusters: "0019,000A", manufacturer: "_TZE200_aoclfnxz", model: "TS0601", deviceJoinName: "Moes BHT-002-GCLZBW Thermostat"
    }
    
    preferences {
        input(name: "debugLogging", type: "bool", title: "Enable debug logging", description: "", defaultValue: false, submitOnChange: true, displayDuringSetup: true)
        input(name: "infoLogging", type: "bool", title: "Enable info logging", description: "", defaultValue: true, submitOnChange: true, displayDuringSetup: true)
    }
}   

def logsOff(){
    logging("Debug logging disabled...","warn")
    device.updateSetting("debugLogging",[value:"false",type:"bool"])
}

def updated() {
    sendEvent(name: "supportedThermostatFanModes", value: [""])
    sendEvent(name: "supportedThermostatModes", value: ["off", "heat", "auto"] )
    sendEvent(name: "thermostatFanMode", value: "off")
    logging("${device.displayName} updated")
}

def installed() {
    sendEvent(name: "supportedThermostatFanModes", value: [""])
    sendEvent(name: "supportedThermostatModes", value: ["off", "heat", "auto"] )
    sendEvent(name: "thermostatFanMode", value: "off")
    logging("${device.displayName} installed")
}

def configure(){    
    logging("${device.displayName} configure")
    runIn(1800,logsOff) //turn off logging in 30mins
    //binding to Thermostat cluster"
    // Set unused default values (for Google Home Integration)
    //sendEvent(name: "coolingSetpoint", value: "30")
    sendEvent(name: "thermostatFanMode", value:"off")
    sendEvent(name: "lastRunningMode", value: "heat")
    updated()

}

def refresh() {
    zigbee.readAttribute(0,0)
    zigbee.readAttribute(CLUSTER_TUYA, 0x0000)
    zigbee.readAttribute(CLUSTER_TUYA, 0x0302)
    zigbee.readAttribute(CLUSTER_TUYA, 0x0402)
}

ArrayList<String> parse(String description) {
    ArrayList<String> cmd = []
    Map msgMap = parseMessage(description)

    switch(msgMap["cluster"] + '_' + msgMap["attrId"]) {
        case "0000_0001":
            logging("Application ID Received","trace")
            if(msgMap['value']) {
                updateDataValue("application", msgMap['value'])
            }
            break
        case "0000_0004":
            logging("Manufacturer Name Received ${msgMap['value']}","trace")
            if(msgMap['value']) {
                updateDataValue("manufacturer", msgMap['value'])
            }
            break
        case "0000_0005":
            logging("Model Name Received","trace")
            if(msgMap['value']) {
                updateDataValue('model', msgMap['value'])
            }    
            break
        default:
            switch(msgMap["clusterId"]) {
                case "0013":
                    logging("MULTISTATE CLUSTER EVENT")
                    break
                case "8021":
                    logging("BIND RESPONSE CLUSTER EVENT")
                    break
                case "8001":
                    logging("GENERAL CLUSTER EVENT")
                    break
                case "8004":
                    logging("Simple Descriptor Information Received - description:${description} | parseMap:${msgMap}","trace")
                    updateDataFromSimpleDescriptorData(msgMap["data"])
                    break
                case "8031":
                    logging("Link Quality Cluster Event - description:${description} | parseMap:${msgMap}")
                    break
                case "8032":
                    logging("Routing Table Cluster Event - description:${description} | parseMap:${msgMap}")
                    break
                case "8021":
                case "8038":
                    logging("GENERAL CATCHALL (0x${msgMap["clusterId"]}")
                    break
///////////TUYA TRV messages////////////
                case "EF00":  
                    List data = msgMap['data']
                    String values = data.collect{c -> HexUtils.hexStringToInt(c)}

                    if (data[2] && data[3]){
                        String commandType = data[2] + data[3]
                        Integer commandCode = HexUtils.hexStringToInt("${data[3]}${data[2]}")

                        //logging("Type: ${commandType} Code: ${commandCode} Values: ${values}","debug")
                        switch(commandType){
                            case COMMAND_TYPE_HEATPOINT: 
                                String setPoint = HexUtils.hexStringToInt("${data[-1]}")
                                sendEvent(name: "heatingSetpoint", value: setPoint.toFloat(), unit: "C")
                                sendEvent(name: "thermostatSetpoint", value: setPoint.toFloat(), unit: "C")
                                logging("${device.displayName} thermostat set to ${setPoint.toFloat()} C", "debug")
                            break
                            case COMMAND_TYPE_TEMP: 
                                String temperature = HexUtils.hexStringToInt("${data[-2]}${data[-1]}") / 10
                                sendEvent(name: "temperature", value: temperature, unit: "C" )
                                logging("${device.displayName} temperature ${temperature} C")
                            break                          
                            case COMMAND_TYPE_ONOFF_STATE: 
                                String mode = HexUtils.hexStringToInt(data[6])
                                switch (mode){
                                    case '1':
                                        def lastRunningMode = device.currentValue("lastRunningMode") ?: "heat"
                                        sendEvent(name: "thermostatMode", value: lastRunningMode , descriptionText:"On")
                                        sendEvent(name: "switch", value: true, descriptionText:"On")
                                        logging("${device.displayName} is on")
                                    break
                                    case '0':
                                        logging("${device.displayName} is off")
                                        sendEvent(name: "thermostatMode", value: "off" , descriptionText:"Off")
                                        sendEvent(name: "switch", value: false, descriptionText:"Off")
                                    break
                                }
                            break                           
                            case COMMAND_TYPE_MANUALMODE:
                                String mode = HexUtils.hexStringToInt(data[6])
                                switch (mode){
                                    case '1':
                                        logging("${device.displayName} mode set to auto")
                                        sendEvent(name: "thermostatMode", value: "auto" , descriptionText:"Using internally programmed schedule")
                                        sendEvent(name: "lastRunningMode", value: "auto")
                                        updateDataValue("lastRunningMode", "auto")
                                    break
                                    case '0':
                                        logging("${device.displayName} mode set to manual")
                                        sendEvent(name: "thermostatMode", value: "heat" , descriptionText:"Manual Mode")
                                        sendEvent(name: "lastRunningMode", value: "heat")
                                        updateDataValue("lastRunningMode", "heat")
                                    break
                                }
                            break
                            /*case COMMAND_TYPE_AUTOMODE:
                                String mode = HexUtils.hexStringToInt(data[6])
                                switch (mode){
                                    case '0':
                                        logging("${device.displayName} mode set to auto")
                                        sendEvent(name: "thermostatMode", value: "auto" , descriptionText:"Using internally programmed schedule")
                                        sendEvent(name: "lastRunningMode", value: "auto")
                                        updateDataValue("lastRunningMode", "auto")
                                    break
                                    case '1':
                                        logging("${device.displayName} mode set to manual")
                                        sendEvent(name: "thermostatMode", value: "heat" , descriptionText:"Manual Mode")
                                        sendEvent(name: "lastRunningMode", value: "heat")
                                        updateDataValue("lastRunningMode", "heat")
                                    break
                                }
                            break*/                 
                            case COMMAND_TYPE_CHILDLOCK:
                                String locked = HexUtils.hexStringToInt(data[6])
                                switch (locked){
                                    case '0':
                                        sendEvent(name: "childLock", value: "off" )
                                    break
                                    case '1':
                                        sendEvent(name: "childLock", value: "on")
                                    break
                                }
                            break
//Untested,
                            case "6010":
                                logging("${device.displayName} Command : ${commandType} - Values: ${values}","debug")
                            break
                            case "0702": //0x7202 away/off preset temperature
                                String SetPoint = HexUtils.hexStringToInt(data[9]) / 10
                                logging("${device.displayName} AWAY Temp Set Point ${commandType}, data9 ${SetPoint}","debug")
                            break  
                            case '2C02': //Temperature correction reporting
                                String temperatureCorr = HexUtils.hexStringToInt(data[9])/ 10
                                logging("${device.displayName} Temp correction reporting DEV STILL, ${temperatureCorr}, data ${msgMap["data"]}","debug")
                            break
                            case '6800': //window open detection
                                String winTemp = HexUtils.hexStringToInt(data[7])
                                String winMin = HexUtils.hexStringToInt(data[8])
                                logging("${device.displayName} window open detection ${winTemp}deg in ${winMin}min will trigger shutdown","debug")
                                sendEvent(name: "windowOpenDetected", value: "${winTemp}deg in ${winMin}min")
                            break
                            case '6902': //boost -- Dev
                                logging("${device.displayName} boost ${values}","debug")
                            break
                            case '7000': // schedule setting aka Auto mode -- Dev
                                logging("${device.displayName} schedual P1 ${data[6]}:${data[7]} = ${data[8]}deg , ${data[9]}:${data[10]} = ${data[11]}deg ,more ${data}","debug")
                                state.SchduleP1 = "${values[6]}:${values[7]} = ${values[8]}deg , ${values[9]}:${values[10]} = ${values[11]}deg ,more ${values}"
                            break
                            case '7001': // schedule setting aka Auto mode -- Dev
                                logging("${device.displayName} schedual P2 ${data[6]}:${data[7]} = ${data[8]}deg , ${data[9]}:${data[10]} = ${data[11]}deg ,more ${data} ","debug")
                                state.SchduleP2 = "${values[6]}:${values[7]} = ${values[8]}deg , ${values[9]}:${values[10]} = ${values[11]}deg ,more ${values}"
                            break
                            case '7100': // schedule setting aka Auto mode -- Dev
                                logging("${device.displayName} schedual P3? ${data[6]}:${data[7]} = ${data[8]}deg , ${data[9]}:${data[10]} = ${data[11]}deg ,more ${data} ","debug")
                                state.SchduleP3 = "${values[6]}:${values[7]} = ${values[8]}deg , ${values[9]}:${values[10]} = ${values[11]}deg ,more ${values}"
                            break                           
                            case '7502': // 0x7502 away preset number of days 
                                logging("${device.displayName} away preset number of days ${HexUtils.hexStringToInt(data[-1])} ","debug")
                                break
                            default:
                                logging("${device.displayName} other EF00 cluster - ${commandCode} - ${values}","debug")
                                break
                        }
                    }
                    else { 
                        // found data in map of, data:[02, 19]], data:[00, 00]]
                        logging("other cluster EF00 but map null- ${data}","debug")
                    }
                    break
                default:
                    logging("Unhandled Event IGNORE THIS - description:${description} | msgMap:${msgMap}","debug")
                    break
            }
            break
    }
    msgMap = null
    return cmd
}

////////////////////// helpers ///////////////////////////////
Map parseMessage(String description) {
    Map msgMap = null
    if(description.indexOf('encoding: 42') >= 0) {
        List values = description.split("value: ")[1].split("(?<=\\G..)")
        String fullValue = values.join()
        Integer zeroIndex = values.indexOf("01")
            if(zeroIndex > -1) {
                msgMap = zigbee.parseDescriptionAsMap(description.replace(fullValue, values.take(zeroIndex).join()))
                values = values.drop(zeroIndex + 3)
                msgMap["additionalAttrs"] = [
                    ["encoding": "41",
                    "value": parseXiaomiStruct(values.join(), isFCC0=false, hasLength=true)]
                    ]
            } 
            else {
                msgMap = zigbee.parseDescriptionAsMap(description) //modle name
            }
    } else {
        msgMap = zigbee.parseDescriptionAsMap(description)
    }
    
    if(msgMap.containsKey("encoding") && msgMap.containsKey("value") && msgMap["encoding"] != "41" && msgMap["encoding"] != "42") {
        msgMap["valueParsed"] = zigbee_generic_decodeZigbeeData(msgMap["value"], msgMap["encoding"])
    }   
    
    if(msgMap == [:] && description.indexOf("zone") == 0) {
        msgMap["type"] = "zone"
        java.util.regex.Matcher zoneMatcher = description =~ /.*zone.*status.*0x(?<status>([0-9a-fA-F][0-9a-fA-F])+).*extended.*status.*0x(?<statusExtended>([0-9a-fA-F][0-9a-fA-F])+).*/
        if(zoneMatcher.matches()) {
            msgMap["parsed"] = true
            msgMap["status"] = zoneMatcher.group("status")
            msgMap["statusInt"] = Integer.parseInt(msgMap["status"], 16)
            msgMap["statusExtended"] = zoneMatcher.group("statusExtended")
            msgMap["statusExtendedInt"] = Integer.parseInt(msgMap["statusExtended"], 16)
        } 
        else {
            msgMap["parsed"] = false
        }
    }
    return msgMap
}

void updateDataFromSimpleDescriptorData(List<String> data) {
    Map<String,String> sdi = parseSimpleDescriptorData(data)
    if(sdi != [:]) {
        updateDataValue("endpointId", sdi['endpointId'])
        updateDataValue("profileId", sdi['profileId'])
        updateDataValue("inClusters", sdi['inClusters'])
        updateDataValue("outClusters", sdi['outClusters'])
        getInfo(true, sdi)
    } else {
        logging("No VALID Simple Descriptor Data received!","warn")
    }
    sdi = null
}

def zigbee_generic_decodeZigbeeData(String value, String cTypeStr, boolean reverseBytes=true) {
    List values = value.split("(?<=\\G..)")
    values = reverseBytes == true ? values.reverse() : values
    Integer cType = Integer.parseInt(cTypeStr, 16)
    Map rMap = [:]
    rMap['raw'] = [:]
    List ret = zigbee_generic_convertStructValue(rMap, values, cType, "NA", "NA")
    return ret[0]["NA"]
}

List zigbee_generic_convertStructValueToList(List values, Integer cType) {
    Map rMap = [:]
    rMap['raw'] = [:]
    List ret = zigbee_generic_convertStructValue(rMap, values, cType, "NA", "NA")
    return [ret[0]["NA"], ret[1]]
}

List zigbee_generic_convertStructValue(Map r, List values, Integer cType, String cKey, String cTag) {
    String cTypeStr = cType != null ? integerToHexString(cType, 1) : null
    switch(cType) {
        case 0x10:
            r["raw"][cKey] = values.take(1)[0]
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16) != 0
            values = values.drop(1)
            break
        case 0x18:
        case 0x20:
            r["raw"][cKey] = values.take(1)[0]
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(1)
            break
        case 0x19:
        case 0x21:
            r["raw"][cKey] = values.take(2).reverse().join()
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(2)
            break
        case 0x1A:
        case 0x22:
            r["raw"][cKey] = values.take(3).reverse().join()
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(3)
            break
        case 0x1B:
        case 0x23:
            r["raw"][cKey] = values.take(4).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(4)
            break
        case 0x1C:
        case 0x24:
            r["raw"][cKey] = values.take(5).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(5)
            break
        case 0x1D:
        case 0x25:
            r["raw"][cKey] = values.take(6).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(6)
            break
        case 0x1E:
        case 0x26:
            r["raw"][cKey] = values.take(7).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(7)
            break
        case 0x1F:
        case 0x27:
            r["raw"][cKey] = values.take(8).reverse().join()
            r[cKey] = new BigInteger(r["raw"][cKey], 16)
            values = values.drop(8)
            break
        case 0x28:
            r["raw"][cKey] = values.take(1).reverse().join()
            r[cKey] = convertToSignedInt8(Integer.parseInt(r["raw"][cKey], 16))
            values = values.drop(1)
            break
        case 0x29:
            r["raw"][cKey] = values.take(2).reverse().join()
            r[cKey] = (Integer) (short) Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(2)
            break
        case 0x2B:
            r["raw"][cKey] = values.take(4).reverse().join()
            r[cKey] = (Integer) Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(4)
            break
        case 0x30:
            r["raw"][cKey] = values.take(1)[0]
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(1)
            break
        case 0x31:
            r["raw"][cKey] = values.take(2).reverse().join()
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(2)
            break
        case 0x39:
            r["raw"][cKey] = values.take(4).reverse().join()
            r[cKey] = parseSingleHexToFloat(r["raw"][cKey])
            values = values.drop(4)
            break
        case 0x42:
            Integer strLength = Integer.parseInt(values.take(1)[0], 16)
            values = values.drop(1)
            r["raw"][cKey] = values.take(strLength)
            r[cKey] = r["raw"][cKey].collect { 
                (char)(int) Integer.parseInt(it, 16)
            }.join()
            values = values.drop(strLength)
            break
        default:
            throw new Exception("The Struct used an unrecognized type: $cTypeStr ($cType) for tag 0x$cTag with key $cKey (values: $values, map: $r)")
    }
    return [r, values]
}

String integerToHexString(BigDecimal value, Integer minBytes, boolean reverse=false) {
    return integerToHexString(value.intValue(), minBytes, reverse=reverse)
}

String integerToHexString(Integer value, Integer minBytes, boolean reverse=false) {
    if(reverse == true) {
        return HexUtils.integerToHexString(value, minBytes).split("(?<=\\G..)").reverse().join()
    } else {
        return HexUtils.integerToHexString(value, minBytes)
    }
}

private boolean logging(message,level="info") {
    boolean didLogging = false
    Integer logLevelLocal = 0
    //log.debug "Level: ${level}, infoLogging: ${infoLogging}, debugLogging: ${debugLogging}"
    switch(level){
        case "info": 
            if (infoLogging == null || infoLogging == true) {
                log.info "$message"
                didLogging = true
            }
        break;
        case "debug": 
            if (debugLogging == null || debugLogging == true) {
                log.debug "$message"
                didLogging = true
            }
        break;
        case "warn": 
            if (infoLogging == null || infoLogging == true) {
                log.warn "$message"
                didLogging = true
            }
        break;
        case "trace": 
            if (infoLogging == null || infoLogging == true) {
                log.trace "$message"
                didLogging = true
            }
        break;
    }
    return didLogging
}

private getCLUSTER_TUYA() { 0xEF00 }
private getSETDATA() { 0x00 }

private sendTuyaCommand(dp, fn, data) {
    sendTuyaCommand("00" + zigbee.convertToHexString(rand(256), 2) + dp + fn + data)
}

private sendTuyaCommand(String cmd) { 
    logging("Sending ${cmd}","debug")
    zigbee.command(CLUSTER_TUYA, SETDATA, null, 200, cmd)
}

private rand(n) { return (new Random().nextInt(n))} 

////////////////////////////////////////////////////////////////////////////  

///////////// commands ///////////////

private getCOMMAND_TYPE_HEATPOINT() { return integerToHexString(528,2,true) };
private getCOMMAND_TYPE_TEMP() { return integerToHexString(536,2,true) };
private getCOMMAND_TYPE_MANUALMODE() { return integerToHexString(1026,2,true) };
private getCOMMAND_TYPE_AUTOMODE() { return integerToHexString(1027,2,true) };
private getCOMMAND_TYPE_ONOFF_STATE() { return integerToHexString(257,2,true) };
private getCOMMAND_TYPE_CHILDLOCK() { return integerToHexString(296,2,true) };

def on() { 
    def isRunning = device.currentValue("switch") ?: "false"
    logging("IsRunning: ${isRunning}","debug")
    if (isRunning=="false") {
        def lastRunningMode = device.currentValue("lastRunningMode") ?: "heat"
        logging("On command, lastRunningMode: ${lastRunningMode}")
        sendTuyaCommand(COMMAND_TYPE_ONOFF_STATE,"0001","02")
    } else { null }
}

def off() {
    logging("Off command")
    sendTuyaCommand(COMMAND_TYPE_ONOFF_STATE,"0001","00")
}

def heat(){
    def cmd = on()
    if(cmd!=null){
        runIn(1,"sendHeat")
        cmd
    } else {
        sendHeat() 
    }
}

def sendAuto() {
    logging("Turn Auto On")
    sendTuyaCommand(COMMAND_TYPE_MANUALMODE,"0001","01") 
}

def sendHeat() {
    logging("Turn Heat/Manual On")
    sendTuyaCommand(COMMAND_TYPE_MANUALMODE,"0001","00") 
}

def auto(){
    def cmd = on()
    if(cmd!=null){
        runIn(1,"sendAuto")
        cmd
    } else {
        sendAuto() 
    }
}

def setHeatingSetpoint(preciseDegrees) {
    setThermostatSetpoint(preciseDegrees)
}

def childLockOn() {
}

def childLockOff() {
}

def setThermostatSetpoint(preciseDegrees) {
    if (preciseDegrees != null) {
        def SP = preciseDegrees
        def X = (SP / 256).intValue()
        def Y = SP.intValue() % 256
        logging("Thermostat setpoint to ${preciseDegrees}")
        sendTuyaCommand(COMMAND_TYPE_HEATPOINT,"00","040000" + zigbee.convertToHexString(X.intValue(), 2) + zigbee.convertToHexString(Y.intValue(), 2))
    }
}

def setThermostatMode(String value) {
    switch (value) {
        case "heat":
        case "boost":
        case "emergency heat":
            return heat()
        case "eco":
        case "cool":
            return eco()
        case "auto":
            return auto()
        default:
            return off()
    }
}

//unused commands and redirected
def eco() { //holiday
    logging("Eco mode is not available for this device. => Defaulting to off mode instead.","debug")
    off()
}

def cool() {
    logging("Cool mode is not available for this device. => Defaulting to eco mode instead.","debug")
    eco()
}

def emergencyHeat() {
    logging("EmergencyHeat mode is not available for this device. => Defaulting to heat mode instead.","debug")
    heat()
}

def setCoolingSetpoint(degrees) {
    logging("SetCoolingSetpoint is not available for this device","debug")
}

def fanAuto() {
    logging("FanAuto mode is not available for this device","debug")
}

def fanCirculate(){
    logging("FanCirculate mode is not available for this device","debug")
}

def fanOn(){
    logging("FanOn mode is not available for this device","debug")
}

def setSchedule(JSON_OBJECT){
    logging("SetSchedule is not available for this device","debug")
}

def setThermostatFanMode(fanmode){
    logging("SetThermostatFanMode is not available for this device","debug")
}