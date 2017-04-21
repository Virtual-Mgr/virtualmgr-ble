var exec = require('cordova/exec');

var _module = "VirtualManagerBLE";

function Characteristic(characteristicInfo, service) {
	Object.assign(this, characteristicInfo);
	// Parent reference via function prevents JSON.stringify circular reference loops
	this.service = function() { return service; }
}

Characteristic.prototype.write = function(data, success, error) {
	if (data instanceof Array) {
        // assuming array of integer
        data = new Uint8Array(data).buffer;
    } else if (data instanceof Uint8Array) {
        data = data.buffer;
    }

    // !!success is true if success function is defined, and indicates a response to the write is required
	var service = this.service();
	var peripheral = service.peripheral();
	var client = peripheral.client();
	exec(success, error, _module, "characteristicWrite", [client.id, peripheral.id, service.uuid, this.uuid, data, !!success]);
}

Characteristic.prototype.read = function(success, error) {
	var self = this;
	var service = this.service();
	var peripheral = service.peripheral();
	var client = peripheral.client();
	exec(function(value) {
		value = new Uint8Array(value);
		self.lastRead = value;
		success(value);
	}, error, _module, "characteristicRead", [client.id, peripheral.id, service.uuid, this.uuid]);
}

function Service(uuid, peripheral) {
	this.uuid = uuid;
	// Parent reference via function prevents JSON.stringify circular reference loops
	this.peripheral = function() { return peripheral; }
	this.characteristics = Object.create(null);
}

Service.prototype.discoverCharacteristics = function(characteristicUUIDs, success, error) {
	var self = this;
	var peripheral = this.peripheral();
	var client = peripheral.client();
	exec(function(result) {
		var characteristics = [];
		result.forEach(function(c) {
			var characteristic = self.characteristics[c.uuid];
			if (characteristic === undefined) {
				characteristic = new Characteristic(c, self);
				self.characteristics[c.uuid] = characteristic;
			}
			characteristics.push(characteristic);
		});
		success(characteristics);
	}, error, _module, "serviceDiscoverCharacteristics", [client.id, peripheral.id, characteristicUUIDs, self.uuid]);
}

function Peripheral(scanResult, client) {
	Object.assign(this, scanResult);
	// Parent reference via function prevents JSON.stringify circular reference loops
	this.client = function() { return client; }
	this.connected = false;
	this.services = Object.create(null);
}

Peripheral.prototype.discoverServices = function(serviceUUIDs, success, error) {
	var self = this;
	var client = this.client();
	exec(function(result) {
		var services = [];
		result.forEach(function(uuid) {
			var service = self.services[uuid];
			if (service === undefined) {
				service = new Service(uuid, self);
				self.services[uuid] = service;
			}
			services.push(service);
		});
		success(services);
	}, error, _module, "peripheralDiscoverServices", [client.id, this.id, serviceUUIDs]);
}

Peripheral.prototype.connect = function(success, error) {
	var self = this;
	var client = this.client();
	exec(function(msg) {
		if (msg == 'connect') {
			self.connected = true;
		} else if (msg == 'disconnect') {
			self.connected = false;
		}
		success(msg);
	}, error, _module, "peripheralConnect", [client.id, this.id]);
}

Peripheral.prototype.disconnect = function(success, error) {
	var self = this;
	var client = this.client();
	exec(function() {
		self.connected = false;
	}, error, _module, "peripheralDisconnect", [client.id, this.id]);
}

function Client(id) {
	this.id = id;
	this.peripherals = Object.create(null);
}

Client.prototype.startScanning = function (serviceUUIDs, options, success, error) {
	var self = this;
	exec(function(scanResult) {
		if (scanResult) {
			var peripherals = [];
			scanResult.forEach(function(sr) {
				var peripheral = self.peripherals[sr.id];
				if (peripheral === undefined) {
					var peripheral = new Peripheral(sr, self);
					self.peripherals[sr.id] = peripheral;
				} else {
					// Update the scan result
					Object.assign(peripheral, sr);
				}
				peripherals.push(peripheral);
			})
			success(peripherals);
		} else {
			success(scanResult);
		}
	}, error, _module, "clientStartScanning", [this.id, serviceUUIDs, options]);
}

Client.prototype.stopScanning = function (success, error) {
	exec(success, error, _module, "clientStopScanning", [this.id]);
}

Client.prototype.subscribeStateChange = function (success, error) {
	exec(success, error, _module, "clientSubscribeStateChange", [this.id]);
}

Client.prototype.unsubscribeStateChange = function (success, error) {
	exec(success, error, _module, "clientUnsubscribeStateChange", [this.id]);
}

// Updated API - clientXXX functions allow multiple JS clients to have their own BLE comms
exports.Client = Client;

// Old API - only a single JS client can use this, note this API does not create Peripherals or hang onto them
var _defaultClientId = "Default";

exports.startScanning = function(serviceUUIDs, options, success, error) {
	exec(success, error, _module, "clientStartScanning", [_defaultClientId, serviceUUIDs, options]);
}

exports.stopScanning = function(success, error) {
	exec(success, error, _module, "clientStopScanning", [_defaultClientId]);
}

exports.subscribeStateChange = function(success, error) {
	exec(success, error, _module, "clientSubscribeStateChange", [_defaultClientId]);
}

exports.unsubscribeStateChange = function(success, error) {
	exec(success, error, _module, "clientUnsubscribeStateChange", [_defaultClientId]);
}



