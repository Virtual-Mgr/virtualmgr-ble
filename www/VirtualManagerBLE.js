var exec = require('cordova/exec');

var _module = "VirtualManagerBLE";

function clientStartScanning(clientId, serviceUUIDs, options, success, error) {
	exec(success, error, _module, "clientStartScanning", [clientId, serviceUUIDs, options]);
}

function clientStopScanning(clientId, success, error) {
	exec(success, error, _module, "clientStopScanning", [clientId]);
}

function clientSubscribeStateChange(clientId, success, error) {
	exec(success, error, _module, "clientSubscribeStateChange", [clientId]);
}

function clientUnsubscribeStateChange(clientId, success, error) {
	exec(success, error, _module, "clientUnsubscribeStateChange", [clientId]);
}

function clientPeripheral_getServices(clientId, peripheral_id, success, error) {
	exec(success, error, _module, "clientPeripheral_getServices", [clientId, peripheral_id]);
}

// Updated API - clientXXX functions allow multiple JS clients to have their own BLE comms
exports.clientStartScanning = clientStartScanning;
exports.clientStopScanning = clientStopScanning;
exports.clientSubscribeStateChange = clientSubscribeStateChange;
exports.clientUnsubscribeStateChange = clientUnsubscribeStateChange;
exports.clientPeripheral_getServices = clientPeripheral_getServices;

// Old API - only a single JS client can use this 
var _defaultClientId = "Default";

exports.startScanning = function(serviceUUIDs, options, success, error) {
	clientStartScanning(_defaultClientId, serviceUUIDs, options, success, error);
}

exports.stopScanning = function(success, error) {
	clientStopScanning(_defaultClientId, success, error);
}

exports.subscribeStateChange = function(success, error) {
	clientSubscribeStateChange(_defaultClientId, success, error);
}

exports.unsubscribeStateChange = function(success, error) {
	clientUnsubscribeStateChange(_defaultClientId, success, error);
}

exports.peripheral_getServices = function(peripheral_id, success, error) {
	clientPeripheral_getServices(_defaultClientId, peripheral_id, success, error);
}


