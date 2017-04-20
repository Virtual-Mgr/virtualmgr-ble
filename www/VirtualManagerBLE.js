var exec = require('cordova/exec');

var _module = "VirtualManagerBLE";

function Client(clientId) {
	this._clientId = clientId;
}

Client.prototype.startScanning = function (serviceUUIDs, options, success, error) {
	exec(success, error, _module, "clientStartScanning", [this._clientId, serviceUUIDs, options]);
}

Client.prototype.stopScanning = function (success, error) {
	exec(success, error, _module, "clientStopScanning", [this._clientId]);
}

Client.prototype.subscribeStateChange = function (success, error) {
	exec(success, error, _module, "clientSubscribeStateChange", [this._clientId]);
}

Client.prototype.unsubscribeStateChange = function (success, error) {
	exec(success, error, _module, "clientUnsubscribeStateChange", [this._clientId]);
}

Client.prototype.peripheral_getServices = function (peripheral_id, success, error) {
	exec(success, error, _module, "clientPeripheral_getServices", [this._clientId, peripheral_id]);
}

// Updated API - clientXXX functions allow multiple JS clients to have their own BLE comms
exports.Client = Client;

// Old API - only a single JS client can use this 
var _defaultClient = new Client("Default");

exports.startScanning = function(serviceUUIDs, options, success, error) {
	_defaultClient.startScanning(serviceUUIDs, options, success, error);
}

exports.stopScanning = function(success, error) {
	_defaultClient.stopScanning(success, error);
}

exports.subscribeStateChange = function(success, error) {
	_defaultClient.subscribeStateChange(success, error);
}

exports.unsubscribeStateChange = function(success, error) {
	_defaultClient.unsubscribeStateChange(success, error);
}

exports.peripheral_getServices = function(peripheral_id, success, error) {
	_defaultClient.peripheral_getServices(peripheral_id, success, error);
}


