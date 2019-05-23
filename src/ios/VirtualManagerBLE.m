/********* VirtualManagerBLE.m Cordova Plugin Implementation *******/

#import <Cordova/CDV.h>
#import <CoreBluetooth/CoreBluetooth.h>

//#define DEBUGLOG(fmt, ...) NSLog(fmt, ##__VA_ARGS__)
#define DEBUGLOG(x, ...)

#define PLUGIN_VERSION @"1.5.0"

NSMutableDictionary* getCharacteristicInfo(CBCharacteristic* characteristic)
{
    NSMutableDictionary* info = [[NSMutableDictionary alloc] init];

    [info setObject: characteristic.UUID.UUIDString forKey: @"uuid"];
    [info setObject: [NSNumber numberWithUnsignedInteger: characteristic.properties] forKey: @"properties"];
    return info;
}

NSMutableDictionary* getPeripheralInfo(CBPeripheral* peripheral, NSDictionary* advertisementData, NSNumber* rssi)
{
    NSMutableDictionary* info = [[NSMutableDictionary alloc] init];

    [info setObject: peripheral.identifier.UUIDString forKey:@"id"];
    if (peripheral.name != nil) {
        [info setObject: peripheral.name forKey: @"name"];
    }
    if (rssi != nil) {
        [info setObject: rssi forKey:@"rssi"];
    }
    if (advertisementData != nil) {
        NSMutableDictionary* advertisementInfo = [[NSMutableDictionary alloc] init];

        NSData* data = [advertisementData objectForKey:CBAdvertisementDataManufacturerDataKey];
        if (data != nil) {
            [advertisementInfo setObject: [data base64EncodedStringWithOptions:0] forKey:@"data"];
        }

        NSNumber* txPowerLevel = [advertisementData objectForKey:CBAdvertisementDataTxPowerLevelKey];
        if (txPowerLevel != nil) {
            [advertisementInfo setObject: txPowerLevel forKey:@"txPower"];
        }

        NSNumber* localName = [advertisementData objectForKey:CBAdvertisementDataLocalNameKey];
        if (localName != nil) {
            [advertisementInfo setObject: localName forKey:@"localName"];
        }

        NSNumber* isConnectable = [advertisementData objectForKey:CBAdvertisementDataIsConnectable];
        if (isConnectable != nil) {
            [advertisementInfo setObject: isConnectable forKey:@"connectable"];
        }

        NSArray* uuids = [advertisementData objectForKey:CBAdvertisementDataServiceUUIDsKey];
        if (uuids != nil) {
            NSMutableArray* uuidsInfo = [[NSMutableArray alloc] init];
            for(CBUUID* uuid in uuids) {
                [uuidsInfo addObject: uuid.UUIDString];
            }
            [advertisementInfo setObject: uuidsInfo forKey:@"UUIDS"];
        }

        NSArray* solicitedUuids = [advertisementData objectForKey:CBAdvertisementDataSolicitedServiceUUIDsKey];
        if (solicitedUuids != nil) {
            NSMutableArray* uuidsInfo = [[NSMutableArray alloc] init];
            for(CBUUID* uuid in solicitedUuids) {
                [uuidsInfo addObject: uuid.UUIDString];
            }
            [advertisementInfo setObject: uuidsInfo forKey:@"solicitedUUIDS"];
        }

        NSArray* overflowUuids = [advertisementData objectForKey:CBAdvertisementDataOverflowServiceUUIDsKey];
        if (overflowUuids != nil) {
            NSMutableArray* uuidsInfo = [[NSMutableArray alloc] init];
            for(CBUUID* uuid in overflowUuids) {
                [uuidsInfo addObject: uuid.UUIDString];
            }
            [advertisementInfo setObject: uuidsInfo forKey:@"overflowUUIDS"];
        }

        NSDictionary* serviceData = [advertisementData objectForKey:CBAdvertisementDataServiceDataKey];
        if (serviceData != nil) {
            NSMutableDictionary* serviceInfo = [[NSMutableDictionary alloc] init];
            for (CBUUID* uuid in serviceData) {
                NSData* data = [serviceData objectForKey:uuid];
                [serviceInfo setObject: [data base64EncodedStringWithOptions:0] forKey:uuid.UUIDString];
            }
            [advertisementInfo setObject: serviceInfo forKey:@"serviceData"];
        }

        [info setObject:advertisementInfo forKey:@"advertisement"];
    }

    if (peripheral.services != nil) {
        NSMutableArray* servicesInfo = [[NSMutableArray alloc] init];
        for(CBService* service in peripheral.services) {
            [servicesInfo addObject:service.UUID.UUIDString];
        }
        [info setObject:servicesInfo forKey:@"services"];
    }
    return info;
}

NSString* getCentralManagerStateName(CBCentralManagerState state)
{
    switch(state) {
        case CBCentralManagerStateUnknown:
            return @"Unknown";

        case CBCentralManagerStatePoweredOn:
            return @"PoweredOn";

        case CBCentralManagerStateResetting:
            return @"Resetting";

        case CBCentralManagerStatePoweredOff:
            return @"PoweredOff";

        case CBCentralManagerStateUnsupported:
            return @"Unsupported";

        case CBCentralManagerStateUnauthorized:
            return @"Unauthorized";
    }
    return @"Invalid State";
}

@interface VMPeripheral : NSObject {
}

-(id)initWithPeripheral:(CBPeripheral*)peripheral;
-(void)dispose;

@property (nonatomic, retain) CBPeripheral* peripheral;
@property (nonatomic, retain) NSMutableDictionary* callbackIds;

@end

@implementation VMPeripheral

@synthesize peripheral, callbackIds;

-(id)initWithPeripheral:(CBPeripheral *)initPeripheral
{
    if ((self = [super init])) {
        self.peripheral = initPeripheral;
        self.callbackIds = [[NSMutableDictionary alloc] init];
    }
    return self;
}

-(void)dispose
{
    self.peripheral = nil;
    self.callbackIds = nil;
}

-(void)setCallbackId:(NSString*)callbackId forKey:(id)key
{
    [callbackIds setObject:callbackId forKey:key];
}

-(NSString*)callbackIdForKey:(id)key remove:(bool)remove
{
    NSString* callbackId = [callbackIds objectForKey:key];
    if (callbackId != nil && remove) {
        [callbackIds removeObjectForKey:key];
    }
    return callbackId;
}
@end


// VMScanClient allows multiple JS clients to call startScanning, we will merge their requests to make it look
// like
@interface VMScanClient : NSObject<CBCentralManagerDelegate, CBPeripheralDelegate> {
    // To make JS bridge more efficient, rather than returning every scan result to JS individually the following
    // 2 fields allow results to be gathered and sent as a group of maximum "groupSize" and waiting no more than
    // "groupTimeout" milliseconds once 1 scan has been gathered before delivering to JS
    NSInteger _groupSize;
    NSTimeInterval _groupTimeout;
    bool _groupTimeoutScheduled;
}

@property (nonatomic, retain) NSString* clientId;
@property (nonatomic, retain) id<CDVCommandDelegate> commandDelegate;
@property (nonatomic, retain) NSMutableDictionary* peripherals;
@property (nonatomic, retain) NSString* scanResultCallbackId;
@property (nonatomic, retain) NSString* stateChangeCallbackId;
@property (nonatomic, retain) CBCentralManager* centralManager;
@property (nonatomic, retain) NSMutableArray* groupedScans;
@property (nonatomic, retain) NSMutableSet* blackedlistedUUIDs;
@property (nonatomic, retain) NSMutableDictionary* scanArgs;
@end


@implementation VMScanClient

@synthesize clientId, commandDelegate, peripherals, scanResultCallbackId, stateChangeCallbackId, centralManager, groupedScans;

const int firstParameterOffset = 1;

-(id)initClientId:(NSString*) theClientId withCommandDelegate:(id<CDVCommandDelegate>)theCommandDelegate
{
    if ((self = [super init])) {
        DEBUGLOG(@"VMScanClient initClientId: %@", theClientId);
        self.clientId = theClientId;
        self.commandDelegate = theCommandDelegate;
        self.centralManager = [[CBCentralManager alloc] initWithDelegate:self queue:nil];
        self.peripherals = [[NSMutableDictionary alloc] init];
        self.blackedlistedUUIDs = [[NSMutableSet alloc] init];

        // Default send all results immediately
        self.groupedScans = nil;
        _groupTimeout = 0;
        _groupSize = 1;
        _groupTimeoutScheduled = false;

        self.scanArgs = nil;
    }
    return self;
}

-(void)dispose
{
    DEBUGLOG(@"VMScanClient dispose: %@", self.clientId);
    self.clientId = nil;
    self.commandDelegate = nil;
    self.centralManager = nil;
    self.peripherals = nil;
    self.groupedScans = nil;
    self.blackedlistedUUIDs = nil;
    self.scanArgs = nil;
}

-(void)subscribeStateChange:(CDVInvokedUrlCommand*) command
{
    NSString* state = getCentralManagerStateName((CBCentralManagerState)centralManager.state);
    DEBUGLOG(@"subscribeStageChange %@ currently %@", clientId, state);
    self.stateChangeCallbackId = command.callbackId;

    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK messageAsString: state];
    [pluginResult setKeepCallbackAsBool:TRUE];
    [self.commandDelegate sendPluginResult: pluginResult callbackId: stateChangeCallbackId];
}

-(void)unsubscribeStateChange:(CDVInvokedUrlCommand*) command
{
    DEBUGLOG(@"unsubscribeStageChange %@", clientId);
    self.stateChangeCallbackId = nil;
}

-(NSArray*)getUUIDsFromStringArray:(NSArray*)uuidStrArray
{
    NSMutableArray* uuids = nil;
    if (uuidStrArray != nil && ![uuidStrArray isKindOfClass:[NSNull class]]) {
        uuids = [[NSMutableArray alloc] init];

        for(NSString* uuidStr in uuidStrArray) {
            CBUUID* uuid = [CBUUID UUIDWithString:uuidStr];
            [uuids addObject: uuid];
        }
    }
    return uuids;
}

-(void)scanWithArgs
{
    if (self.scanArgs == nil) {
        return;
    }
    DEBUGLOG(@"VMScanClient: %@ scanWithArgs", clientId);
    NSDictionary* options = [_scanArgs objectForKey:@"options"];
    NSArray* services = [_scanArgs objectForKey:@"services"];
    NSDictionary* arguments = [_scanArgs objectForKey:@"arguments"];

    [centralManager scanForPeripheralsWithServices:services options:options];

    // Instead of the Javascript client having to reissue the rescan we can do it here ..
    if (arguments != nil) {
        NSNumber* rescanTimeout = [arguments objectForKey:@"rescanTimeout"];
        if (rescanTimeout != nil) {
            [self performSelector:@selector(scanWithArgs) withObject:nil afterDelay:[rescanTimeout doubleValue] / 1000.0];
        }
    }
}

-(void)startScanning:(CDVInvokedUrlCommand*) command
{
    DEBUGLOG(@"VMScanClient: %@ StartScanning %@", clientId, command);
    NSMutableDictionary* options = [[NSMutableDictionary alloc] init];
    NSArray* services = nil;

    if (command.arguments.count >= firstParameterOffset + 1) {
        services = [self getUUIDsFromStringArray:[command.arguments objectAtIndex: firstParameterOffset + 0]];
    }

    NSDictionary* optionArg = nil;
    if (command.arguments.count >= firstParameterOffset + 2) {
        optionArg = [command.arguments objectAtIndex: firstParameterOffset + 1];
        NSNumber* allowDuplicate = [optionArg objectForKey:@"allowDuplicate"];
        if (allowDuplicate && [allowDuplicate boolValue]) {
            [options setObject:allowDuplicate forKey:CBCentralManagerScanOptionAllowDuplicatesKey];
        }

        NSNumber* groupTimeout = [optionArg objectForKey:@"groupTimeout"];
        if (groupTimeout) {
            _groupTimeout = [groupTimeout integerValue] / 1000.0;
        }

        // To make JS bridge more efficient - Group scan results to no more than
        NSNumber* groupSize = [optionArg objectForKey:@"groupSize"];
        if (groupSize) {
            _groupSize = [groupSize integerValue];
        }
    }

    if (_groupTimeout < 0.0) {
        _groupTimeout = 0.0;
    }

    // GroupSize of 0 = ignore groupSize and just use groupTimeout
    if (_groupSize < 0) {
        _groupSize = 0;
    }

    self.scanArgs = [[NSMutableDictionary alloc] init];
    [_scanArgs setObject:options forKey:@"options"];
    [_scanArgs setObject:services forKey:@"services"];
    [_scanArgs setObject:optionArg forKey:@"arguments"];
    [self scanWithArgs];

    self.scanResultCallbackId = command.callbackId;

    // Always callback
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK messageAsArray: nil];
    [pluginResult setKeepCallbackAsBool:TRUE];
    [self.commandDelegate sendPluginResult: pluginResult callbackId: scanResultCallbackId];
}

-(void)stopScanning:(CDVInvokedUrlCommand*) command
{
    DEBUGLOG(@"stopScanning %@", clientId);
    [centralManager stopScan];
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];

    self.scanArgs = nil;
    [NSObject cancelPreviousPerformRequestsWithTarget:self selector:@selector(scanWithArgs) object:nil];
}

- (void)centralManagerDidUpdateState:(CBCentralManager *)central
{
    NSString* state = getCentralManagerStateName((CBCentralManagerState)central.state);
    DEBUGLOG(@"VMScanClient:%@ Central Manager Update State %@", clientId, state);

    if (stateChangeCallbackId != nil) {
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK messageAsString: state];
        [pluginResult setKeepCallbackAsBool:TRUE];
        [self.commandDelegate sendPluginResult: pluginResult callbackId: stateChangeCallbackId];
    }
}

- (void)sendGroupScans
{
    if (groupedScans != nil) {
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK messageAsArray: groupedScans];
        [pluginResult setKeepCallbackAsBool:TRUE];
        [self.commandDelegate sendPluginResult: pluginResult callbackId: scanResultCallbackId];
    }
    // release the group now it is sent
    self.groupedScans = nil;
    _groupTimeoutScheduled = false;
}

- (void)centralManager:(CBCentralManager *)central
 didDiscoverPeripheral:(CBPeripheral *)peripheral
     advertisementData:(NSDictionary<NSString *,id> *)advertisementData
                  RSSI:(NSNumber *)RSSI
{
    // Check blacklist, ignore it if found
    NSString* uuid = peripheral.identifier.UUIDString;
    VMPeripheral* vmp = [peripherals objectForKey:uuid];
    if ([_blackedlistedUUIDs containsObject:uuid]) {
        if (vmp != nil) {
            [peripherals removeObjectForKey:uuid];
        }
        return;
    }
    if (vmp == nil) {
        vmp = [[VMPeripheral alloc] initWithPeripheral:peripheral];
        [peripherals setObject:vmp forKey:uuid];
    }
    peripheral.delegate = self;

    NSMutableDictionary* info = getPeripheralInfo(peripheral, advertisementData, RSSI);
    if (groupedScans == nil) {
        groupedScans = [[NSMutableArray alloc] initWithObjects:info, nil];
    } else {
        [groupedScans addObject:info];
    }

    // Note, groupSize of 0 means we ignore groupSize and send on groupTimeouts only
    if (_groupSize >= 1 && ([groupedScans count] >= _groupSize)) {
        [self sendGroupScans];

    } else if (!_groupTimeoutScheduled) {
        _groupTimeoutScheduled = true;
        [self performSelector:@selector(sendGroupScans) withObject:nil afterDelay:_groupTimeout];
    } // else a timeout is already scheduled
}

-(void)centralManager:(CBCentralManager *)central didConnectPeripheral:(CBPeripheral *)peripheral
{
    CDVPluginResult* pluginResult = nil;
    VMPeripheral* vmp = [peripherals objectForKey:peripheral.identifier.UUIDString];
    if (vmp != nil) {
        DEBUGLOG(@"BLE Connected %@", vmp.peripheral.identifier.UUIDString);
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK messageAsString:@"connect"];
        [pluginResult setKeepCallbackAsBool:TRUE];
        [self.commandDelegate sendPluginResult: pluginResult callbackId: [vmp callbackIdForKey:@"connect" remove:false]];
    }
}

-(void)centralManager:(CBCentralManager *)central didDisconnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error
{
    CDVPluginResult* pluginResult = nil;
    VMPeripheral* vmp = [peripherals objectForKey:peripheral.identifier.UUIDString];
    if (vmp != nil) {
        DEBUGLOG(@"BLE Disconnected %@ %@", vmp.peripheral.identifier.UUIDString, error.description);
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK messageAsString:@"disconnect"];
        [pluginResult setKeepCallbackAsBool:FALSE];     // We can get rid of this now
        [self.commandDelegate sendPluginResult: pluginResult callbackId: [vmp callbackIdForKey:@"connect" remove:true]];
    }
}

-(void)centralManager:(CBCentralManager *)central didFailToConnectPeripheral:(nonnull CBPeripheral *)peripheral error:(nullable NSError *)error
{
    CDVPluginResult* pluginResult = nil;
    VMPeripheral* vmp = [peripherals objectForKey:peripheral.identifier.UUIDString];
    if (vmp != nil) {
        DEBUGLOG(@"BLE Connect Failed %@ %@", vmp.peripheral.identifier.UUIDString, error.description);
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: error.description];
        [pluginResult setKeepCallbackAsBool:FALSE];     // We can get rid of this now
        [self.commandDelegate sendPluginResult: pluginResult callbackId: [vmp callbackIdForKey:@"connect" remove:true]];
    }
}

-(void)peripheralConnect:(CDVInvokedUrlCommand*) command
{
    CDVPluginResult* pluginResult;
    NSString* peripheralId = nil;
    NSMutableDictionary* options = [[NSMutableDictionary alloc] init];

    if (command.arguments.count >= firstParameterOffset + 1) {
        peripheralId = [command.arguments objectAtIndex: firstParameterOffset + 0];
    }

    if (command.arguments.count >= firstParameterOffset + 2) {
        NSDictionary* optionArg = [command.arguments objectAtIndex: firstParameterOffset + 1];
        NSNumber* notifyOnConnection = [optionArg objectForKey:@"notifyOnConnection"];
        if (notifyOnConnection && [notifyOnConnection boolValue]) {
            [options setObject:notifyOnConnection forKey:CBConnectPeripheralOptionNotifyOnConnectionKey];
        }
        NSNumber* notifyOnDisconnection = [optionArg objectForKey:@"notifyOnDisconnection"];
        if (notifyOnDisconnection && [notifyOnDisconnection boolValue]) {
            [options setObject:notifyOnDisconnection forKey:CBConnectPeripheralOptionNotifyOnDisconnectionKey];
        }
        NSNumber* notifyOnNotification = [optionArg objectForKey:@"notifyOnNotification"];
        if (notifyOnNotification && [notifyOnNotification boolValue]) {
            [options setObject:notifyOnNotification forKey:CBConnectPeripheralOptionNotifyOnNotificationKey];
        }
    }

    if (peripheralId == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'peripheralId'"];
    }

    if (pluginResult == nil) {
        VMPeripheral* vmp = [peripherals objectForKey:peripheralId];
        if (vmp == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Peripheral not found"];
        }

        if (pluginResult == nil) {
            DEBUGLOG(@"BLE Connect %@", vmp.peripheral.identifier.UUIDString);

            [vmp setCallbackId:command.callbackId forKey:@"connect"];
            [centralManager connectPeripheral:vmp.peripheral options:options];
        }
    }

    if (pluginResult != nil) {
        [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
    }
}

-(void)peripheralDisconnect:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    NSString* peripheralId = nil;

    if (command.arguments.count >= firstParameterOffset + 1) {
        peripheralId = [command.arguments objectAtIndex: firstParameterOffset + 0];
    }

    if (peripheralId == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'peripheralId'"];
    }

    if (pluginResult == nil) {
        VMPeripheral* vmp = [peripherals objectForKey:peripheralId];

        if (vmp == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Peripheral not found"];
        }

        if (pluginResult == nil) {
            // Yes we alter the connectCallback - this
            // allows the client to distinguish between a requested Disconnect and a spurious Disconnect
            // the requested Disconnect will call the clients disconnect(success) method
            // a spurious Disconnect will call the clients connect(success) method with "disconnect" as parameter

            DEBUGLOG(@"BLE Disconnect %@", vmp.peripheral.identifier.UUIDString);

            [vmp setCallbackId:command.callbackId forKey:@"connect"];
            [centralManager cancelPeripheralConnection:vmp.peripheral];
        }
    }

    if (pluginResult != nil) {
        [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
    }
}

-(void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error
{
    CDVPluginResult* pluginResult = nil;
    VMPeripheral* vmp = [peripherals objectForKey:peripheral.identifier.UUIDString];
    if (vmp != nil) {
        if (error == nil) {
            NSMutableArray* info = [[NSMutableArray alloc] init];
            for(CBService* service in peripheral.services) {
                [info addObject:service.UUID.UUIDString];
            }

            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK messageAsArray: info];
        } else {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString:error.description];
        }
        [self.commandDelegate sendPluginResult: pluginResult callbackId: [vmp callbackIdForKey:@"discoverServices" remove:true]];
    }
}

-(void)peripheralDiscoverServices:(CDVInvokedUrlCommand*) command
{
    CDVPluginResult* pluginResult;
    NSString* peripheralId = nil;
    NSArray* services = nil;

    if (command.arguments.count >= firstParameterOffset + 1) {
        peripheralId = [command.arguments objectAtIndex: firstParameterOffset + 0];
    }

    if (command.arguments.count >= firstParameterOffset + 2) {
        services = [self getUUIDsFromStringArray:[command.arguments objectAtIndex: firstParameterOffset + 1]];
    }

    if (peripheralId == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'peripheralId'"];
    } else {
        VMPeripheral* vmp = [peripherals objectForKey:peripheralId];
        if (vmp == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Peripheral not found"];
        }

        if (pluginResult == nil) {
            [vmp setCallbackId:command.callbackId forKey:@"discoverServices"];
            [vmp.peripheral discoverServices: services];
        }
    }
    if (pluginResult != nil) {
        [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
    }
}

-(void)peripheral:(CBPeripheral *)peripheral didDiscoverCharacteristicsForService:(CBService *)service error:(NSError *)error
{
    CDVPluginResult* pluginResult = nil;
    VMPeripheral* vmp = [peripherals objectForKey:peripheral.identifier.UUIDString];
    if (vmp != nil) {
        NSString* callbackKey = [@"discoverCharacteristicsForService:" stringByAppendingString:service.UUID.UUIDString];
        if (error == nil) {
            NSMutableArray* info = [[NSMutableArray alloc] init];
            for(CBCharacteristic* characteristic in service.characteristics) {
                [info addObject: getCharacteristicInfo(characteristic)];
            }

            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK messageAsArray: info];
        } else {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString:error.description];
        }
        [self.commandDelegate sendPluginResult: pluginResult callbackId: [vmp callbackIdForKey:callbackKey remove:true]];
    }
}

-(void)serviceDiscoverCharacteristics:(CDVInvokedUrlCommand*) command
{
    CDVPluginResult* pluginResult = nil;
    NSString* peripheralId = nil;
    CBUUID* serviceUUID = nil;
    NSArray* characteristicUUIDs = nil;

    if (command.arguments.count >= firstParameterOffset + 1) {
        peripheralId = [command.arguments objectAtIndex: firstParameterOffset + 0];
    }

    if (command.arguments.count >= firstParameterOffset + 2) {
        characteristicUUIDs = [self getUUIDsFromStringArray:[command.arguments objectAtIndex: firstParameterOffset + 1]];
    }

    if (command.arguments.count >= firstParameterOffset + 3) {
        NSString* str = [command.arguments objectAtIndex: firstParameterOffset + 2];
        serviceUUID = [CBUUID UUIDWithString: str];
    }

    if (peripheralId == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'peripheralId'"];
    }

    if (serviceUUID == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing or invalid argument 'serviceUUID'"];
    }

    if (pluginResult == nil) {
        VMPeripheral* vmp = [peripherals objectForKey:peripheralId];
        if (vmp == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Peripheral not found"];
        }

        CBService* service = nil;
        if (pluginResult == nil) {
            for(CBService* find in vmp.peripheral.services) {
                if ([[find UUID] isEqual: serviceUUID]) {
                    service = find;
                    break;
                }
            }
            if (service == nil) {
                pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Service UUID has not been discovered"];
            }
        }

        if (pluginResult == nil) {
            [vmp setCallbackId:command.callbackId forKey:[@"discoverCharacteristicsForService:" stringByAppendingString:service.UUID.UUIDString]];
            [vmp.peripheral discoverCharacteristics:characteristicUUIDs forService:service];
        }
    }

    if (pluginResult != nil) {
        [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
    }
}

-(void)peripheral:(CBPeripheral *)peripheral didWriteValueForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error
{
    CDVPluginResult* pluginResult = nil;
    VMPeripheral* vmp = [peripherals objectForKey:peripheral.identifier.UUIDString];
    if (vmp != nil) {
        NSString* callbackKey = [@"writeCharacteristic:" stringByAppendingString:characteristic.UUID.UUIDString];
        if (error == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK];
        } else {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString:error.description];
        }
        [self.commandDelegate sendPluginResult: pluginResult callbackId: [vmp callbackIdForKey:callbackKey remove:true]];
    }
}

-(void)characteristicWrite:(CDVInvokedUrlCommand*) command
{
    CDVPluginResult* pluginResult = nil;
    NSString* peripheralId = nil;
    CBUUID* serviceUUID = nil;
    CBUUID* characteristicUUID = nil;
    NSData* data = nil;
    CBCharacteristicWriteType writeType = CBCharacteristicWriteWithoutResponse;

    if (command.arguments.count >= firstParameterOffset + 1) {
        peripheralId = [command.arguments objectAtIndex: firstParameterOffset + 0];
    }

    if (command.arguments.count >= firstParameterOffset + 2) {
        serviceUUID = [CBUUID UUIDWithString: [command.arguments objectAtIndex: firstParameterOffset + 1]];
    }

    if (command.arguments.count >= firstParameterOffset + 3) {
        characteristicUUID = [CBUUID UUIDWithString: [command.arguments objectAtIndex: firstParameterOffset + 2]];
    }

    if (command.arguments.count >= firstParameterOffset + 4) {
        data = [command.arguments objectAtIndex: firstParameterOffset + 3];
    }

    if (command.arguments.count >= firstParameterOffset + 5) {
        writeType = [[command.arguments objectAtIndex: firstParameterOffset + 4] boolValue] ? CBCharacteristicWriteWithResponse : CBCharacteristicWriteWithoutResponse;
    }

    if (peripheralId == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'peripheralId'"];
    }

    if (data == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'data'"];
    }

    if (pluginResult == nil) {
        VMPeripheral* vmp = [peripherals objectForKey:peripheralId];
        if (vmp == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Peripheral not found"];
        }

        CBService* service = nil;
        if (pluginResult == nil) {
            for(CBService* find in vmp.peripheral.services) {
                if ([[find UUID] isEqual: serviceUUID]) {
                    service = find;
                    break;
                }
            }
            if (service == nil) {
                pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Service UUID has not been discovered"];
            }
        }

        CBCharacteristic* characteristic = nil;
        if (pluginResult == nil) {
            for(CBCharacteristic* find in service.characteristics) {
                if ([[find UUID] isEqual: characteristicUUID]) {
                    characteristic = find;
                    break;
                }
            }
            if (characteristic == nil) {
                pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Characteristic UUID has not been discovered"];
            }
        }

        if (pluginResult == nil) {
            [vmp setCallbackId:command.callbackId forKey:[@"writeCharacteristic:" stringByAppendingString:characteristic.UUID.UUIDString]];

            DEBUGLOG(@"characteristicWrite: %@ %@", clientId, characteristic.UUID.UUIDString);
            [vmp.peripheral writeValue: data forCharacteristic: characteristic type: writeType];
        }
    }

    if (pluginResult != nil) {
        [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
    }
}

-(void)peripheral:(CBPeripheral *)peripheral didUpdateValueForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error
{
    CDVPluginResult* pluginResult = nil;
    VMPeripheral* vmp = [peripherals objectForKey:peripheral.identifier.UUIDString];
    if (vmp != nil) {
        if (error == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_OK messageAsArrayBuffer:characteristic.value];
        } else {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString:error.description];
        }

        // If we have a one-off read then call it back, otherwise return data to the subscribed reader
        NSString* callback = [vmp callbackIdForKey:[@"readCharacteristic:" stringByAppendingString:characteristic.UUID.UUIDString] remove:true];
        if (callback == nil) {
            DEBUGLOG(@"subscribedCharacteristicReadValue: %@ %@", clientId, characteristic.UUID.UUIDString);

            callback = [vmp callbackIdForKey:[@"subscribeReadCharacteristic:" stringByAppendingString:characteristic.UUID.UUIDString] remove:false];
            if (callback != nil) {
                [pluginResult setKeepCallbackAsBool: true];
            }
        } else {
            DEBUGLOG(@"characteristicReadValue: %@ %@", clientId, characteristic.UUID.UUIDString);

            [pluginResult setKeepCallbackAsBool: false];
        }
        if (callback != nil) {
            [self.commandDelegate sendPluginResult: pluginResult callbackId: callback];
        }
    }
}

-(void)peripheral:(CBPeripheral *)peripheral didUpdateNotificationStateForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error
{
    DEBUGLOG(@"characteristicSetNotify: %@ %@", clientId, characteristic.UUID.UUIDString);
    VMPeripheral* vmp = [peripherals objectForKey:peripheral.identifier.UUIDString];
    if (vmp != nil) {
        // This callback is as a result of [subscribeCharacteristicRead], read the updated value ..
        [vmp.peripheral readValueForCharacteristic:characteristic];
    }
}

-(void)characteristicRead:(CDVInvokedUrlCommand*) command
{
    CDVPluginResult* pluginResult = nil;
    NSString* peripheralId = nil;
    CBUUID* serviceUUID = nil;
    CBUUID* characteristicUUID = nil;

    if (command.arguments.count >= firstParameterOffset + 1) {
        peripheralId = [command.arguments objectAtIndex: firstParameterOffset + 0];
    }

    if (command.arguments.count >= firstParameterOffset + 2) {
        serviceUUID = [CBUUID UUIDWithString: [command.arguments objectAtIndex: firstParameterOffset + 1]];
    }

    if (command.arguments.count >= firstParameterOffset + 3) {
        characteristicUUID = [CBUUID UUIDWithString: [command.arguments objectAtIndex: firstParameterOffset + 2]];
    }

    if (peripheralId == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'peripheralId'"];
    }

    DEBUGLOG(@"characteristicRead: %@ %@", clientId, characteristicUUID.UUIDString);

    if (pluginResult == nil) {
        VMPeripheral* vmp = [peripherals objectForKey:peripheralId];
        if (vmp == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Peripheral not found"];
        }

        CBService* service = nil;
        if (pluginResult == nil) {
            for(CBService* find in vmp.peripheral.services) {
                if ([[find UUID] isEqual: serviceUUID]) {
                    service = find;
                    break;
                }
            }
            if (service == nil) {
                pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Service UUID has not been discovered"];
            }
        }

        CBCharacteristic* characteristic = nil;
        if (pluginResult == nil) {
            for(CBCharacteristic* find in service.characteristics) {
                if ([[find UUID] isEqual: characteristicUUID]) {
                    characteristic = find;
                    break;
                }
            }
            if (characteristic == nil) {
                pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Characteristic UUID has not been discovered"];
            }
        }

        if (pluginResult == nil) {
            [vmp setCallbackId:command.callbackId forKey:[@"readCharacteristic:" stringByAppendingString:characteristic.UUID.UUIDString]];

            [vmp.peripheral readValueForCharacteristic:characteristic];
        }
    }

    if (pluginResult != nil) {
        [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
    }
}

-(void)blacklistUUIDs:(CDVInvokedUrlCommand*) command
{
    CDVPluginResult* pluginResult = nil;
    NSArray* uuids = nil;

    if (command.arguments.count >= firstParameterOffset + 1) {
        uuids = [command.arguments objectAtIndex: firstParameterOffset + 0];
    }

    if (uuids == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'UUIDs'"];
    } else {
        [_blackedlistedUUIDs addObjectsFromArray:uuids];
    }

    if (pluginResult == nil) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    }

    [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
}

-(void)subscribeCharacteristicRead:(CDVInvokedUrlCommand*) command
{
    CDVPluginResult* pluginResult = nil;
    NSString* peripheralId = nil;
    CBUUID* serviceUUID = nil;
    CBUUID* characteristicUUID = nil;

    if (command.arguments.count >= firstParameterOffset + 1) {
        peripheralId = [command.arguments objectAtIndex: firstParameterOffset + 0];
    }

    if (command.arguments.count >= firstParameterOffset + 2) {
        serviceUUID = [CBUUID UUIDWithString: [command.arguments objectAtIndex: firstParameterOffset + 1]];
    }

    if (command.arguments.count >= firstParameterOffset + 3) {
        characteristicUUID = [CBUUID UUIDWithString: [command.arguments objectAtIndex: firstParameterOffset + 2]];
    }

    if (peripheralId == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'peripheralId'"];
    }

    DEBUGLOG(@"subscribeCharacteristicRead: %@ %@", clientId, characteristicUUID.UUIDString);

    if (pluginResult == nil) {
        VMPeripheral* vmp = [peripherals objectForKey:peripheralId];
        if (vmp == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Peripheral not found"];
        }

        CBService* service = nil;
        if (pluginResult == nil) {
            for(CBService* find in vmp.peripheral.services) {
                if ([[find UUID] isEqual: serviceUUID]) {
                    service = find;
                    break;
                }
            }
            if (service == nil) {
                pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Service UUID has not been discovered"];
            }
        }

        CBCharacteristic* characteristic = nil;
        if (pluginResult == nil) {
            for(CBCharacteristic* find in service.characteristics) {
                if ([[find UUID] isEqual: characteristicUUID]) {
                    characteristic = find;
                    break;
                }
            }
            if (characteristic == nil) {
                pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Characteristic UUID has not been discovered"];
            }
        }

        if (pluginResult == nil) {
            [vmp setCallbackId:command.callbackId forKey:[@"subscribeReadCharacteristic:" stringByAppendingString:characteristic.UUID.UUIDString]];

            [vmp.peripheral setNotifyValue:true forCharacteristic:characteristic];
        }
    }

    if (pluginResult != nil) {
        [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
    }
}

-(void)unsubscribeCharacteristicRead:(CDVInvokedUrlCommand*) command
{
    CDVPluginResult* pluginResult = nil;
    NSString* peripheralId = nil;
    CBUUID* serviceUUID = nil;
    CBUUID* characteristicUUID = nil;

    if (command.arguments.count >= firstParameterOffset + 1) {
        peripheralId = [command.arguments objectAtIndex: firstParameterOffset + 0];
    }

    if (command.arguments.count >= firstParameterOffset + 2) {
        serviceUUID = [CBUUID UUIDWithString: [command.arguments objectAtIndex: firstParameterOffset + 1]];
    }

    if (command.arguments.count >= firstParameterOffset + 3) {
        characteristicUUID = [CBUUID UUIDWithString: [command.arguments objectAtIndex: firstParameterOffset + 2]];
    }

    if (peripheralId == nil) {
        pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Missing argument 'peripheralId'"];
    }

    DEBUGLOG(@"unsubscribeCharacteristicRead: %@ %@", clientId, characteristicUUID.UUIDString);

    if (pluginResult == nil) {
        VMPeripheral* vmp = [peripherals objectForKey:peripheralId];
        if (vmp == nil) {
            pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Peripheral not found"];
        }

        CBService* service = nil;
        if (pluginResult == nil) {
            for(CBService* find in vmp.peripheral.services) {
                if ([[find UUID] isEqual: serviceUUID]) {
                    service = find;
                    break;
                }
            }
            if (service == nil) {
                pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Service UUID has not been discovered"];
            }
        }

        CBCharacteristic* characteristic = nil;
        if (pluginResult == nil) {
            for(CBCharacteristic* find in service.characteristics) {
                if ([[find UUID] isEqual: characteristicUUID]) {
                    characteristic = find;
                    break;
                }
            }
            if (characteristic == nil) {
                pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString: @"Characteristic UUID has not been discovered"];
            }
        }

        if (pluginResult == nil) {
            [vmp callbackIdForKey:[@"subscribeReadCharacteristic:" stringByAppendingString:characteristic.UUID.UUIDString] remove:true];

            [vmp.peripheral setNotifyValue:false forCharacteristic:characteristic];
        }
    }

    if (pluginResult != nil) {
        [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
    }
}
@end



@interface VirtualManagerBLE : CDVPlugin {
}

@property (nonatomic, retain) NSMutableDictionary* clients;

- (void)pluginInitialize;
- (void)dispose;

@end


@implementation VirtualManagerBLE

-(void)pluginInitialize
{
    DEBUGLOG(@"pluginInitialize");
    [super pluginInitialize];

    self.clients = [[NSMutableDictionary alloc] init];
}

-(void)dispose
{
    DEBUGLOG(@"dispose");
    self.clients = nil;
    [super dispose];
}

-(void)getVersion:(CDVInvokedUrlCommand*) command
{
    NSMutableDictionary* result = [[NSMutableDictionary alloc] init];
    [result setObject:@"iOS" forKey:@"platform"];
    [result setObject:PLUGIN_VERSION forKey:@"version"];

    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:result];
    [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
}

-(VMScanClient*)getClientFromCommand:(CDVInvokedUrlCommand*) command
{
    // 1st parameter will be the clientId
    NSString* clientId = nil;
    if (command.arguments.count >= 1) {
        clientId = [command.arguments objectAtIndex:0];
    }

    // Find or create a new client ..
    VMScanClient* client = [_clients objectForKey:clientId];
    if (client == nil) {
        client = [[VMScanClient alloc] initClientId: clientId withCommandDelegate: self.commandDelegate];
        [_clients setObject:client forKey:clientId];
    }
    return client;
}

-(void)deleteClient:(CDVInvokedUrlCommand*) command
{
    CDVPluginResult* pluginResult = nil;
    NSString* clientId = nil;
    if (command.arguments.count >= 1) {
        clientId = [command.arguments objectAtIndex:0];
    }

    VMScanClient* client = [_clients objectForKey:clientId];
    if (client != nil) {
        [_clients removeObjectForKey: clientId];
        [client dispose];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString: @"Not found"];
    }

    [self.commandDelegate sendPluginResult: pluginResult callbackId: command.callbackId];
}

-(void)clientSubscribeStateChange:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client subscribeStateChange:command];
}

-(void)clientUnsubscribeStateChange:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client unsubscribeStateChange:command];
}

-(void)clientStartScanning:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client startScanning:command];
}

-(void)clientStopScanning:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client stopScanning:command];
}

-(void)clientBlacklistUUIDs:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client blacklistUUIDs:command];
}

-(void)peripheralConnect:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client peripheralConnect:command];
}

-(void)peripheralDisconnect:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client peripheralDisconnect:command];
}

-(void)peripheralDiscoverServices:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client peripheralDiscoverServices:command];
}

-(void)serviceDiscoverCharacteristics:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client serviceDiscoverCharacteristics:command];
}

-(void)characteristicWrite:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client characteristicWrite:command];
}

-(void)characteristicRead:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client characteristicRead:command];
}

-(void)subscribeCharacteristicRead:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client subscribeCharacteristicRead:command];
}

-(void)unsubscribeCharacteristicRead:(CDVInvokedUrlCommand*) command
{
    VMScanClient* client = [self getClientFromCommand:command];
    [client unsubscribeCharacteristicRead:command];
}

@end
