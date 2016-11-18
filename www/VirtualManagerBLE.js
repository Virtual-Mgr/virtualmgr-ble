var exec = require('cordova/exec');

var _module = "VirtualManagerBLE";

exports.startScanning = function(serviceUUIDs, options, success, error) {
	exec(success, error, _module, "startScanning", [serviceUUIDs, options]);
}

exports.stopScanning = function(success, error) {
	exec(success, error, _module, "stopScanning");
}

exports.subscribeStateChange = function(success, error) {
	exec(success, error, _module, "subscribeStateChange");
}

exports.unsubscribeStateChange = function(success, error) {
	exec(success, error, _module, "unsubscribeStateChange");
}

exports.peripheral_getServices = function(peripheral_id, success, error) {
	exec(success, error, _module, "peripheral_getServices", [peripheral_id]);
}