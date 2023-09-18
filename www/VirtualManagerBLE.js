var _exec = require('cordova/exec');

// Change to true to get detailed plugin logs
var debugExec = false;

function log(msg) {
	var dt = new Date();
	msg = dt.toISOString() + ' - ' + msg;
	console.log(msg);
}

function JSONSerialize(o) {
	return JSON.stringify(o, function (key, value) {
		if (value instanceof Uint8Array) {
			return Array.from(value);
		} else if (value instanceof ArrayBuffer) {
			return Array.from(new Uint8Array(value));
		} else {
			return value;
		}
	});
}

var exec = _exec;
if (debugExec) {
	exec = function (success, error, module, method, args) {
		log(`exec ${module} ${method} ${JSONSerialize(args)}`);
		var successWrapper = function (result) {
			window.setTimeout(function () {
				log(`exec ${module} ${method} success ${JSONSerialize(result)}`);
				if (success) {
					success(result);
				}
			});
		};
		var errorWrapper = function (result) {
			window.setTimeout(function () {
				log(`exec ${module} ${method} error ${JSONSerialize(result)}`);
				if (error) {
					error(result);
				}
			});
		};
		_exec(successWrapper, errorWrapper, module, method, args);
	}
}

var _module = "VirtualManagerBLE";

exports.supports = {
	rescanTimeout: true,
	subscribedReads: true,
	requestMtu: true,
}

function Characteristic(characteristicInfo, service) {
	Object.assign(this, characteristicInfo);
	// Parent reference via function prevents JSON.stringify circular reference loops
	this.service = function () { return service; }
}

Characteristic.prototype.write = function (data, success, error) {
	var self = this;
	if (data instanceof Array) {
		// assuming array of integer
		data = new Uint8Array(data).buffer;
	} else if (data instanceof Uint8Array) {
		data = data.buffer;
	}

	// !!success is true if success function is defined, and indicates a response to the write is required
	var service = self.service();
	var peripheral = service.peripheral();
	var client = peripheral.client();
	exec(success, error, _module, "characteristicWrite", [client.id, peripheral.id, service.uuid, self.uuid, data, !!success]);
}

Characteristic.prototype.read = function (success, error) {
	var self = this;
	var service = self.service();
	var peripheral = service.peripheral();
	var client = peripheral.client();

	exec(function (value) {
		value = new Uint8Array(value);
		self.lastRead = value;

		if (success) {
			success(value);
		}
	}, error, _module, "characteristicRead", [client.id, peripheral.id, service.uuid, self.uuid]);
}

Characteristic.prototype.subscribeRead = function (success, error) {
	var self = this;
	var service = self.service();
	var peripheral = service.peripheral();
	var client = peripheral.client();

	exec(function (value) {
		value = new Uint8Array(value);
		self.lastRead = value;

		if (success) {
			success(value);
		}
	}, error, _module, "subscribeCharacteristicRead", [client.id, peripheral.id, service.uuid, self.uuid]);
}

Characteristic.prototype.unsubscribeRead = function (success, error) {
	var self = this;
	var service = self.service();
	var peripheral = service.peripheral();
	var client = peripheral.client();

	exec(success, error, _module, "unsubscribeCharacteristicRead", [client.id, peripheral.id, service.uuid, self.uuid]);
}

function Service(uuid, peripheral) {
	this.uuid = uuid;
	// Parent reference via function prevents JSON.stringify circular reference loops
	this.peripheral = function () { return peripheral; }
	this.characteristics = Object.create(null);
}

Service.prototype.discoverCharacteristics = function (characteristicUUIDs, success, error) {
	var self = this;
	var peripheral = this.peripheral();
	var client = peripheral.client();
	exec(function (result) {
		var characteristics = [];
		result.forEach(function (c) {
			var characteristic = self.characteristics[c.uuid];
			if (characteristic === undefined) {
				characteristic = new Characteristic(c, self);
				self.characteristics[c.uuid] = characteristic;
			}
			characteristics.push(characteristic);
		});
		if (success) {
			success(characteristics);
		}
	}, error, _module, "serviceDiscoverCharacteristics", [client.id, peripheral.id, characteristicUUIDs, self.uuid]);
}

function Peripheral(scanResult, client) {
	Object.assign(this, scanResult);
	// Parent reference via function prevents JSON.stringify circular reference loops
	this.client = function () { return client; }
	this.connected = false;
	this.services = Object.create(null);
}

Peripheral.prototype.discoverServices = function (serviceUUIDs, success, error) {
	var self = this;
	var client = this.client();
	exec(function (result) {
		var services = [];
		result.forEach(function (uuid) {
			var service = self.services[uuid];
			if (service === undefined) {
				service = new Service(uuid, self);
				self.services[uuid] = service;
			}
			services.push(service);
		});
		if (success) {
			success(services);
		}
	}, error, _module, "peripheralDiscoverServices", [client.id, self.id, serviceUUIDs]);
}

Peripheral.prototype.connect = function (success, error) {
	var self = this;
	var client = this.client();
	exec(function (msg) {
		if (msg == 'connect') {
			self.connected = true;
		} else if (msg == 'disconnect') {
			self.connected = false;
		}
		if (success) {
			success(msg);
		}
	}, error, _module, "peripheralConnect", [client.id, self.id]);
}

Peripheral.prototype.disconnect = function (success, error) {
	var self = this;
	var client = this.client();
	exec(function () {
		self.connected = false;
		if (success) {
			success();
		}
	}, error, _module, "peripheralDisconnect", [client.id, self.id]);
}

Peripheral.prototype.requestMtu = function (mtu, success, error) {
	var self = this;
	var client = this.client();
	exec(success, error, _module, "peripheralRequestMtu", [client.id, self.id, mtu]);
}

function Client(id, options) {
	var self = this;
	this.id = id;
	this.peripherals = Object.create(null);
	this.options = Object.assign({
		keepPeripherals: false,
		makePeripherals: false,
		deleteExistingClient: false
	}, options);
	this.supports = exports.supports;

	if (this.options.deleteExistingClient) {
		exec(null, null, _module, "deleteClient", [this.id]);
	}

	if (this.options.keepPeripherals) {
		this.translateScanResult = function (sr) {
			var peripheral = self.peripherals[sr.id];
			if (typeof peripheral === 'undefined') {
				peripheral = new Peripheral(sr, self);
				self.peripherals[sr.id] = peripheral;
			} else {
				// Update the scan result
				Object.assign(peripheral, sr);
			}
			return peripheral;
		};
	} else if (this.options.makePeripherals) {
		this.translateScanResult = function (sr) {
			return new Peripheral(sr, self);
		};
	} else {
		this.translateScanResult = function (sr) {
			return sr;
		};
	}
}

Client.prototype.startScanning = function (serviceUUIDs, options, success, error) {
	var self = this;
	exec(function (scanResult) {
		if (success) {
			if (scanResult) {
				scanResult = scanResult.map(self.translateScanResult);
			}
			success(scanResult);
		}
	}, error, _module, "clientStartScanning", [self.id, serviceUUIDs, options]);
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

Client.prototype.blacklist = function (peripherals, success, error) {
	var self = this;
	var uuids = [];
	peripherals.forEach(function (peripheral) {
		uuids.push(peripheral.id);
		delete self.peripherals[peripheral.id];
	});
	exec(success, error, _module, "clientBlacklistUUIDs", [this.id, uuids])
}

// Updated API - clientXXX functions allow multiple JS clients to have their own BLE comms
exports.Client = Client;

// Old API - only a single JS client can use this, note this API does not create Peripherals or hang onto them
var _defaultClientId = "Default";

exports.startScanning = function (serviceUUIDs, options, success, error) {
	exec(success, error, _module, "clientStartScanning", [_defaultClientId, serviceUUIDs, options]);
}

exports.stopScanning = function (success, error) {
	exec(success, error, _module, "clientStopScanning", [_defaultClientId]);
}

exports.subscribeStateChange = function (success, error) {
	exec(success, error, _module, "clientSubscribeStateChange", [_defaultClientId]);
}

exports.unsubscribeStateChange = function (success, error) {
	exec(success, error, _module, "clientUnsubscribeStateChange", [_defaultClientId]);
}

exports.getVersion = function (success, error) {
	exec(success, error, _module, "getVersion");
}

