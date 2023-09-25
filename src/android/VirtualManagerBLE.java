package VirtualManagerBLE;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.pm.PackageManager;
import android.os.Build;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.telecom.Call;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;

import com.neovisionaries.bluetooth.ble.*;
import com.neovisionaries.bluetooth.ble.advertising.*;
import com.neovisionaries.bluetooth.ble.util.*;
import org.apache.cordova.PermissionHelper;

import static android.bluetooth.le.ScanSettings.*;
import static androidx.core.app.ActivityCompat.startActivityForResult;

/* This class implements a BLE receiver (does not yet support Connectable devices)
   Its JS API is a little perculiar in how it "munges" packet data - it is
   forming the JS packet data to be compatible with iOS VirtualManagerBLE plugin
   This is most evident with the "data" segment being a concatenation of all Manufacturing Data
   segments into 1 byte array - this is how iOS under the hood gives data to the VirtualManagerBLE iOS plugin
   so we are duplicating that behaviour here
 */
public class VirtualManagerBLE extends CordovaPlugin {
	private static final String PLUGIN_VERSION = "1.6.0";

	private static final String LOGTAG = "VirtualManagerBLE";

	// permissions
	private static final String ACCESS_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"; // API 29
	private static final String BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"; // API 31
	private static final String BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"; // API 31
	private static final String ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"; // API 30
	private BluetoothAdapter _bluetoothAdapter;
	private HashMap<String, VMScanClient> _clients = new HashMap<String, VMScanClient>();

	private static int _msgId = 0;

	private final static String BASE_UUID = "00000000-0000-1000-8000-00805F9B34FB";
	private static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR_UUID = UUID
			.fromString("00002902-0000-1000-8000-00805f9b34fb");

	public static ParcelUuid parseUUID(String uuid) {
		String uuidStr = uuid;
		if (uuid.length() == 4) { // 16 bit UUID
			uuidStr = "0000" + uuid + BASE_UUID.substring(8);
		} else if (uuid.length() == 8) { // 32 bit UUID
			uuidStr = uuid + BASE_UUID.substring(8);
		}
		try {
			return ParcelUuid.fromString(uuidStr);
		} catch (IllegalArgumentException iae) {
			return null;
		}
	}

	public static String getBluetoothAdapterStateName(BluetoothAdapter adapter) {
		switch (adapter.getState()) {
			case BluetoothAdapter.STATE_OFF:
				return "PoweredOff";

			case BluetoothAdapter.STATE_ON:
				return "PoweredOn";

			case BluetoothAdapter.STATE_TURNING_OFF:
				return "TurningOff";

			case BluetoothAdapter.STATE_TURNING_ON:
				return "TurningOn";
		}
		return "Invalid State";
	}

	// We supress MissingPermission here since it is checked at the "execute" method
	@SuppressLint("MissingPermission")
	public static JSONObject getPeripheralInfo(ScanResult scanResult) throws JSONException {
		JSONObject jobj = new JSONObject();
		BluetoothDevice bd = scanResult.getDevice();
		jobj.put("msgId", _msgId++);
		jobj.put("id", bd.getAddress());
		if (bd.getName() != null) {
			jobj.put("name", bd.getName());
		}
		jobj.put("rssi", scanResult.getRssi());
		ScanRecord record = scanResult.getScanRecord();
		if (record != null) {
			JSONObject advertisementInfo = new JSONObject();
			List<ADStructure> structures = ADPayloadParser.getInstance().parse(record.getBytes());

			// "data" under iOS is all manufacturer data segments concat together, 1st
			// segment has the CompanyId
			// others do not
			byte[] iOSStyleManufacturerData = new byte[record.getBytes().length];
			int iOSStyleManufacturerDataIndex = 0;

			JSONObject serviceDataInfo = new JSONObject();
			JSONArray uuidsInfo = new JSONArray();
			JSONArray solicitedUUIDsInfo = new JSONArray();
			for (ADStructure structure : structures) {
				if (structure instanceof ServiceData) {
					ServiceData serviceData = (ServiceData) structure;
					String uuidStr = serviceData.getServiceUUID().toString().toUpperCase();
					switch (serviceData.getType()) {
						case 0x16: // 16bit ServiceData
							serviceDataInfo.put(uuidStr.substring(4, 8), Base64.encodeToString(structure.getData(), 2,
									structure.getData().length - 2, Base64.NO_WRAP));
							break;
						case 0x20: // 32bit ServiceData
							serviceDataInfo.put(uuidStr.substring(0, 8), Base64.encodeToString(structure.getData(), 4,
									structure.getData().length - 4, Base64.NO_WRAP));
							break;
						case 0x21: // 128bit ServiceData
							serviceDataInfo.put(uuidStr, Base64.encodeToString(structure.getData(), 16,
									structure.getData().length - 16, Base64.NO_WRAP));
							break;
					}

				} else if (structure instanceof UUIDs) {
					UUIDs uuids = (UUIDs) structure;
					for (UUID uuid : uuids.getUUIDs()) {
						String uuidStr = uuid.toString().toUpperCase();
						switch (uuids.getType()) {
							case 0x02: // 16bit UUID (Incomplete list)
							case 0x03: // 16bit UUID (Complete list)
								uuidsInfo.put(uuidStr.substring(4, 8));
								break;
							case 0x04: // 32bit UUID (Incomplete list)
							case 0x05: // 32bit UUID (Complete list)
								uuidsInfo.put(uuidStr.substring(0, 8));
								break;
							case 0x06: // 128bit UUID (Incomplete list)
							case 0x07: // 128bit UUID (Complete list)
								uuidsInfo.put(uuidStr);
								break;
							case 0x14: // 16bit Service Solicited UUID
								solicitedUUIDsInfo.put(uuidStr.substring(4, 8));
								break;
							case 0x15: // 128bit Service Solicited UUID
								solicitedUUIDsInfo.put(uuidStr);
								break;
							case 0x1F: // 32bit Service Solicited UUID
								solicitedUUIDsInfo.put(uuidStr.substring(0, 8));
								break;
						}
					}

				} else if (structure instanceof ADManufacturerSpecific) {
					ADManufacturerSpecific ms = (ADManufacturerSpecific) structure;

					if (iOSStyleManufacturerDataIndex == 0) {
						System.arraycopy(ms.getData(), 0, iOSStyleManufacturerData, iOSStyleManufacturerDataIndex,
								ms.getData().length);
						iOSStyleManufacturerDataIndex += ms.getData().length;
					} else {
						System.arraycopy(ms.getData(), 2, iOSStyleManufacturerData, iOSStyleManufacturerDataIndex,
								ms.getData().length - 2);
						iOSStyleManufacturerDataIndex += ms.getData().length - 2;
					}
				} else if (structure instanceof TxPowerLevel) {
					TxPowerLevel txPowerLevel = (TxPowerLevel) structure;
					advertisementInfo.put("txPower", txPowerLevel.getLevel());
				} else if (structure instanceof LocalName) {
					LocalName localName = (LocalName) structure;
					String s = localName.getLocalName();
					int n = s.indexOf('\0');
					if (n >= 0) {
						s = s.substring(0, n);
					}
					jobj.put("name", s);
				}
			}
			if (iOSStyleManufacturerDataIndex > 0) {
				String b64 = Base64.encodeToString(iOSStyleManufacturerData, 0, iOSStyleManufacturerDataIndex,
						Base64.NO_WRAP);
				advertisementInfo.put("data", b64);
			}
			if (uuidsInfo.length() > 0) {
				advertisementInfo.put("UUIDS", uuidsInfo);
			}
			if (solicitedUUIDsInfo.length() > 0) {
				advertisementInfo.put("solicitedUUIDS", solicitedUUIDsInfo);
			}
			if (serviceDataInfo.length() > 0) {
				advertisementInfo.put("serviceData", serviceDataInfo);
			}

			advertisementInfo.put("connectable", scanResult.isConnectable());

			jobj.put("advertisement", advertisementInfo);
		}

		return jobj;
	}

	public class VMPeripheral extends BluetoothGattCallback {
		private final CordovaInterface _cordova;
		public final String Id;
		public final BluetoothDevice Device;
		private BluetoothGatt _gatt;
		private ArrayList<String> _discoverServices;

		private HashMap<String, CallbackContext> _callbacks = new HashMap();

		public void dispose() {
			if (_gatt != null) {
				_gatt.disconnect();
				_gatt.close();
				_gatt = null;
			}

			if (_discoverServices != null) {
				_discoverServices.clear();
				_discoverServices = null;
			}

			if (_callbacks != null) {
				_callbacks.clear();
				_callbacks = null;
			}
		}

		private CallbackContext getCallback(String name, boolean remove) {
			CallbackContext callback = _callbacks.get(name);
			if (callback != null && remove) {
				_callbacks.remove(name);
			}
			return callback;
		}

		private void setCallback(String name, CallbackContext callback) {
			_callbacks.put(name, callback);
		}

		public VMPeripheral(CordovaInterface cordova, BluetoothDevice device) {
			_cordova = cordova;
			Device = device;
			Id = device.getAddress();
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public PluginResult connect(CallbackContext callback) {
			setCallback("connect", callback);
			Device.connectGatt(_cordova.getActivity().getApplicationContext(), false, this);
			return null; // We will Connect or Fail, nothing to callback with yet
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public PluginResult disconnect(CallbackContext callback) {
			if (_gatt == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Not connected");
			}
			// Note, intentionally re-assign the ConnectCallback to the Disconnect callback
			// (we are already Connected)
			// If the client deliberately Disconnects the peripheral then we call its
			// Disconnect(success) method
			// If the peripheral disconnects unexpectedly then we call the Connect(success)
			// method
			// with "disconnect"
			setCallback("connect", callback);
			_gatt.disconnect();
			_gatt.close();
			_gatt = null;

			return null;
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public PluginResult requestMtu(int mtu, CallbackContext callback) {
			if (_gatt == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Not connected");
			}
			setCallback("requestMtu", callback);
			_gatt.requestMtu(mtu);
			return null;
		}

		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
			CallbackContext callback = getCallback("requestMtu", true);
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if (callback != null) {
					callback.success(mtu);
				}
			} else {
				String errorMessage = "requestMtu error: " + status;
				LOG.e(LOGTAG, errorMessage);
				if (callback != null) {
					callback.error(errorMessage);
				}
			}
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public PluginResult discoverServices(ArrayList<String> discoverServices, CallbackContext callback) {
			if (_gatt == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Not connected");
			}

			_discoverServices = discoverServices;
			setCallback("discoverServices", callback);
			_gatt.discoverServices();

			return null;
		}

		public PluginResult discoverServiceCharacteristics(UUID serviceUUID, ArrayList<String> discoverCharacteristics,
				CallbackContext callback) throws JSONException {
			if (_gatt == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Not connected");
			}

			BluetoothGattService service = _gatt.getService(serviceUUID);
			if (service == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Service UUID has not been discovered");
			}

			JSONArray jArray = new JSONArray();
			List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
			for (BluetoothGattCharacteristic characteristic : characteristics) {
				JSONObject jObj = new JSONObject();
				String characteristicUUID = characteristic.getUuid().toString().toUpperCase();
				if (discoverCharacteristics.isEmpty() || discoverCharacteristics.contains(characteristicUUID)) {
					jObj.put("uuid", characteristicUUID);
					jObj.put("properties", characteristic.getProperties());
					jArray.put(jObj);
				}
			}

			return new PluginResult(PluginResult.Status.OK, jArray);
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public PluginResult writeCharacteristic(UUID serviceUUID, UUID characteristicUUID, byte[] data,
				boolean writeWithResponse, CallbackContext callback) {
			if (_gatt == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Not connected");
			}

			BluetoothGattService service = _gatt.getService(serviceUUID);
			if (service == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Service UUID has not been discovered");
			}

			BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
			if (characteristic == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Characteristic UUID has not been discovered");
			}

			characteristic.setWriteType(writeWithResponse ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
					: BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
			characteristic.setValue(data);

			setCallback("writeCharacteristic:" + characteristicUUID.toString(), callback);
			_gatt.writeCharacteristic(characteristic);

			return null;
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public PluginResult readCharacteristic(UUID serviceUUID, UUID characteristicUUID, CallbackContext callback) {
			if (_gatt == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Not connected");
			}

			BluetoothGattService service = _gatt.getService(serviceUUID);
			if (service == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Service UUID has not been discovered");
			}

			BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
			if (characteristic == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Characteristic UUID has not been discovered");
			}

			setCallback("readCharacteristic:" + characteristicUUID.toString(), callback);
			_gatt.readCharacteristic(characteristic);

			return null;
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public PluginResult subscribeReadCharacteristic(UUID serviceUUID, UUID characteristicUUID,
				CallbackContext callback) {
			if (_gatt == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Not connected");
			}

			BluetoothGattService service = _gatt.getService(serviceUUID);
			if (service == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Service UUID has not been discovered");
			}

			BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
			if (characteristic == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Characteristic UUID has not been discovered");
			}

			setCallback("subscribeReadCharacteristic:" + characteristicUUID.toString(), callback);
			_gatt.setCharacteristicNotification(characteristic, true);
			BluetoothGattDescriptor descriptor = characteristic
					.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR_UUID);

			// Simulate what iOS does which is to use Notifications if the characteristic
			// supports it, otherwise use
			// Indications instead
			if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
				descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
			} else if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
				descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
			} else {
				return new PluginResult(PluginResult.Status.ERROR, "Characteristic does not support subscribeRead");
			}

			_gatt.writeDescriptor(descriptor);

			return null;
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public PluginResult unsubscribeReadCharacteristic(UUID serviceUUID, UUID characteristicUUID,
				CallbackContext callback) {
			if (_gatt == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Not connected");
			}

			BluetoothGattService service = _gatt.getService(serviceUUID);
			if (service == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Service UUID has not been discovered");
			}

			BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
			if (characteristic == null) {
				return new PluginResult(PluginResult.Status.ERROR, "Characteristic UUID has not been discovered");
			}

			getCallback("subscribeReadCharacteristic:" + characteristicUUID.toString(), true);
			_gatt.setCharacteristicNotification(characteristic, false);
			BluetoothGattDescriptor descriptor = characteristic
					.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR_UUID);

			descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);

			_gatt.writeDescriptor(descriptor);

			return null;
		}

		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				_gatt = gatt;
				CallbackContext callback = getCallback("connect", false);
				if (callback != null) {
					PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, "connect");
					pluginResult.setKeepCallback(true);
					callback.sendPluginResult(pluginResult);
				}
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				// Note, intentionally calling the ConnectCallback for a Disconnect ..
				// If the client deliberately Disconnects the peripheral then we call its
				// Disconnect(success) method
				// If the peripheral disconnects unexpectedly then we call the Connect(success)
				// method
				// with "disconnect"
				CallbackContext callback = getCallback("connect", true);
				if (callback != null) {
					PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, "disconnect");
					pluginResult.setKeepCallback(false); // We can kill the Connect callback now
					callback.sendPluginResult(pluginResult);
				}
			}
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			CallbackContext callback = getCallback("discoverServices", true);
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if (callback != null) {
					List<BluetoothGattService> services = gatt.getServices();
					JSONArray servicesUUIDs = new JSONArray();

					for (BluetoothGattService service : services) {
						String serviceUUID = service.getUuid().toString().toUpperCase();
						if (_discoverServices.isEmpty() || _discoverServices.contains(serviceUUID)) {
							servicesUUIDs.put(serviceUUID);
						}
					}

					callback.success(servicesUUIDs);
				}
			} else {
				String errorMessage = "discoverServices error: " + status;
				LOG.e(LOGTAG, errorMessage);
				if (callback != null) {
					callback.error(errorMessage);
				}
			}
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			CallbackContext callback = getCallback("writeCharacteristic:" + characteristic.getUuid().toString(), true);
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if (callback != null) {
					callback.success();
				}
			} else {
				String errorMessage = "writeCharacteristic error: " + status;
				LOG.e(LOGTAG, errorMessage);
				if (callback != null) {
					callback.error(errorMessage);
				}
			}
		}

		@Override
		public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			CallbackContext callback = getCallback("readCharacteristic:" + characteristic.getUuid().toString(), true);
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if (callback != null) {
					byte[] data = characteristic.getValue();
					callback.success(data);
				}
			} else {
				String errorMessage = "readCharacteristic error: " + status;
				LOG.e(LOGTAG, errorMessage);
				if (callback != null) {
					callback.error(errorMessage);
				}
			}
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			CallbackContext callback = getCallback("subscribeReadCharacteristic:" + characteristic.getUuid().toString(),
					false);
			if (callback != null) {
				byte[] value = characteristic.getValue();
				PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, value);
				pluginResult.setKeepCallback(true); // Keep the callback for more updates
				callback.sendPluginResult(pluginResult);
			}
		}
	}

	public class VMScanClient extends ScanCallback {
		private final CordovaInterface _cordova;
		public final String ClientId;
		private final BluetoothAdapter _adapter;
		private BluetoothLeScanner _scanner;

		private int _groupSize = -1;
		private long _groupTimeout = 0;

		private CallbackContext _scanResultCallbackId;
		private CallbackContext _stateChangeCallbackId;
		private ArrayList<JSONObject> _groupedScans = new ArrayList<JSONObject>();
		private HashSet<String> _blacklistedUUIDS = new HashSet<String>();
		private HashMap<String, VMPeripheral> _peripherals = new HashMap();

		public VMScanClient(CordovaInterface cordova, String clientId, BluetoothAdapter bluetoothAdapter) {
			_cordova = cordova;
			_adapter = bluetoothAdapter;
			// scanner = bluetoothAdapter.getBluetoothLeScanner();
			ClientId = clientId;
		}

		private BluetoothLeScanner scanner() {
			if (_scanner == null) {
				synchronized (this) {
					if (_scanner == null) {
						_scanner = _adapter.getBluetoothLeScanner();
					}
				}
			}
			return _scanner;
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public void Dispose() {
			synchronized (this) {
				if (_scanner != null) {
					_scanner.stopScan(this);
					_scanner = null;
				}
				_scanResultCallbackId = null;
				_stateChangeCallbackId = null;
				_groupedScans = null;
				_blacklistedUUIDS = null;

				if (_peripherals != null) {
					for (VMPeripheral p : _peripherals.values()) {
						p.dispose();
					}
					_peripherals.clear();
					_peripherals = null;
				}
			}
		}

		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			synchronized (this) {
				if (_scanResultCallbackId != null) {
					JSONArray array = new JSONArray();
					try {
						BluetoothDevice device = result.getDevice();
						String peripheralId = device.getAddress();
						if (_blacklistedUUIDS.contains(peripheralId)) {
							return;
						}

						VMPeripheral peripheral = _peripherals.get(peripheralId);
						if (peripheral == null) {
							peripheral = new VMPeripheral(_cordova, device);
							_peripherals.put(peripheralId, peripheral);
						}

						if (callbackType != CALLBACK_TYPE_MATCH_LOST) {
							JSONObject jobj = getPeripheralInfo(result);
							array.put(jobj);
						}
					} catch (JSONException je) {
						LOG.e(LOGTAG, "onScanResult threw " + je.toString());
					}

					if (array.length() > 0) {
						PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, array);
						pluginResult.setKeepCallback(true);

						_scanResultCallbackId.sendPluginResult(pluginResult);
					}
				}
			}
		}

		/**
		 * Callback when batch results are delivered.
		 *
		 * @param results List of scan results that are previously scanned.
		 */
		@Override
		public void onBatchScanResults(List<ScanResult> results) {
			synchronized (this) {
				if (results.size() > 0 && _scanResultCallbackId != null) {
					JSONArray array = new JSONArray();
					for (ScanResult sr : results) {
						try {
							BluetoothDevice device = sr.getDevice();
							String peripheralId = device.getAddress();
							if (_blacklistedUUIDS.contains(peripheralId)) {
								continue;
							}

							VMPeripheral peripheral = _peripherals.get(peripheralId);
							if (peripheral == null) {
								peripheral = new VMPeripheral(_cordova, device);
								_peripherals.put(peripheralId, peripheral);
							}

							JSONObject jobj = getPeripheralInfo(sr);
							array.put(jobj);
						} catch (JSONException je) {
							LOG.e(LOGTAG, "onBatchScanResults threw " + je.toString());
						}
					}

					if (array.length() > 0) {
						PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, array);
						pluginResult.setKeepCallback(true);

						_scanResultCallbackId.sendPluginResult(pluginResult);
					}
				}
			}
		}

		/**
		 * Callback when scan could not be started.
		 *
		 * @param errorCode Error code (one of SCAN_FAILED_*) for scan failure.
		 */
		@Override
		public void onScanFailed(int errorCode) {
			synchronized (this) {
				if (_scanResultCallbackId != null) {
					JSONObject jError = new JSONObject();
					try {
						jError.put("errorCode", errorCode);
					} catch (JSONException je) {
						Log.e(LOGTAG, "onScanFailed threw " + je.toString());
					}
					PluginResult result = new PluginResult(PluginResult.Status.ERROR, jError);
					result.setKeepCallback(true);
					_scanResultCallbackId.sendPluginResult(result);
				}
			}
		}

		public void subscribeStateChange(JSONArray args, CallbackContext callbackContext) {
			synchronized (this) {
				this._stateChangeCallbackId = callbackContext;

				final String state = getBluetoothAdapterStateName(_adapter);
				PluginResult result = new PluginResult(PluginResult.Status.OK, state);
				result.setKeepCallback(true);

				callbackContext.sendPluginResult(result);
			}
		}

		public void unsubscribeStateChange(JSONArray args, CallbackContext callbackContext) {
			synchronized (this) {
				this._stateChangeCallbackId = null;
			}
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public void startScanning(JSONArray args, CallbackContext callbackContext) throws JSONException {
			synchronized (this) {
				this._scanResultCallbackId = callbackContext;

				List<ScanFilter> filters = null;
				if (args.length() >= 2) {
					filters = new ArrayList<ScanFilter>();
					JSONArray serviceUUIDs = args.getJSONArray(1);
					for (int i = 0; i < serviceUUIDs.length(); i++) {
						String serviceUUID = serviceUUIDs.getString(i);
						ParcelUuid uuid = parseUUID(serviceUUID);
						if (uuid != null) {
							final ScanFilter.Builder builder = new ScanFilter.Builder();
							builder.setServiceUuid(parseUUID(serviceUUID));
							filters.add(builder.build());
						}
					}
					if (filters.size() == 0) {
						filters = null;
					}
				}

				ScanSettings settings = null;
				if (args.length() >= 3) {
					JSONObject options = args.getJSONObject(2);
					final ScanSettings.Builder builder = new ScanSettings.Builder();

					if (options.has("groupTimeout")) {
						_groupTimeout = options.getLong("groupTimeout");
						builder.setReportDelay(_groupTimeout);
					}
					if (options.has("scanMode")) {
						builder.setScanMode(options.getInt("scanMode"));
					}

					if (Build.VERSION.SDK_INT >= 23) {
						if (options.has("callbackType")) {
							int callbackType = options.getInt("callbackType");
							builder.setCallbackType(callbackType);
						}
						if (options.has("matchMode")) {
							int matchMode = options.getInt("matchMode");
							builder.setMatchMode(matchMode);
						}
						if (options.has("numOfMatches")) {
							int numOfMatches = options.getInt("numOfMatches");
							builder.setNumOfMatches(numOfMatches);
						}
					}
					settings = builder.build();

					if (options.has("groupSize")) {
						_groupSize = options.getInt("groupSize");
					}
				}

				// Stop scan first, harmless if not already scanning but stops startScan from
				// failing if we are already scanning
				scanner().stopScan(this);

				scanner().startScan(filters, settings, this);

				PluginResult result = new PluginResult(PluginResult.Status.OK, (String) null);
				result.setKeepCallback(true);

				callbackContext.sendPluginResult(result);
			}
		}

		// We supress MissingPermission here since it is checked at the "execute" method
		@SuppressLint("MissingPermission")
		public void stopScanning(JSONArray args, CallbackContext callbackContext) {
			synchronized (this) {
				// Only stop scanning if we are currently scanning
				if (_scanResultCallbackId != null) {
					_scanResultCallbackId = null;
					scanner().stopScan(this);
				}
				callbackContext.success();
			}
		}

		public void blacklistUUIDs(JSONArray args, CallbackContext callbackContext) throws JSONException {
			synchronized (this) {
				if (args.length() >= 2) {
					JSONArray uuids = args.getJSONArray(1);

					for (int i = 0; i < uuids.length(); i++) {
						String uuid = uuids.getString(i);
						_blacklistedUUIDS.add(uuid);
					}
					callbackContext.success();
				} else {
					callbackContext.error("Missing argument 'UUIDs'");
				}
			}
		}

		public void peripheralConnect(JSONArray args, CallbackContext callbackContext) throws JSONException {
			String peripheralId = null;
			PluginResult pluginResult = null;

			if (args.length() >= 1) {
				peripheralId = args.getString(1);
			}

			if (peripheralId == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'peripheralId'");
			}

			if (pluginResult == null) {
				VMPeripheral peripheral = _peripherals.get(peripheralId);
				if (peripheral == null) {
					pluginResult = new PluginResult(PluginResult.Status.ERROR, "Peripheral not found");
				}

				if (pluginResult == null) {
					pluginResult = peripheral.connect(callbackContext);
				}
			}

			if (pluginResult != null) {
				callbackContext.sendPluginResult(pluginResult);
			}
		}

		public void peripheralDisconnect(JSONArray args, CallbackContext callbackContext) throws JSONException {
			String peripheralId = null;
			PluginResult pluginResult = null;

			if (args.length() >= 1) {
				peripheralId = args.getString(1);
			}

			if (peripheralId == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'peripheralId'");
			}

			if (pluginResult == null) {
				VMPeripheral peripheral = _peripherals.get(peripheralId);
				if (peripheral == null) {
					pluginResult = new PluginResult(PluginResult.Status.ERROR, "Peripheral not found");
				}

				if (pluginResult == null) {
					pluginResult = peripheral.disconnect(callbackContext);
				}
			}

			if (pluginResult != null) {
				callbackContext.sendPluginResult(pluginResult);
			}
		}

		public void peripheralRequestMtu(JSONArray args, CallbackContext callbackContext) throws JSONException {
			String peripheralId = null;
			int mtu = -1;
			PluginResult pluginResult = null;

			if (args.length() >= 1) {
				peripheralId = args.getString(1);
			}
			if (args.length() >= 2) {
				mtu = args.getInt(2);
			} else {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'mtu'");
			}

			if (pluginResult == null) {
				VMPeripheral peripheral = _peripherals.get(peripheralId);
				if (peripheral == null) {
					pluginResult = new PluginResult(PluginResult.Status.ERROR, "Peripheral not found");
				}

				if (pluginResult == null) {
					pluginResult = peripheral.requestMtu(mtu, callbackContext);
				}
			}

			if (pluginResult != null) {
				callbackContext.sendPluginResult(pluginResult);
			}
		}

		public void peripheralDiscoverServices(JSONArray args, CallbackContext callbackContext) throws JSONException {
			String peripheralId = null;
			ArrayList<String> serviceUUIDs = null;
			PluginResult pluginResult = null;

			if (args.length() >= 1) {
				peripheralId = args.getString(1);
			}
			if (args.length() >= 2) {
				serviceUUIDs = new ArrayList<>();
				JSONArray uuids = args.getJSONArray(2);
				for (int i = 0; i < uuids.length(); i++) {
					String uuid = uuids.getString(i).toUpperCase();
					serviceUUIDs.add(uuid);
				}
			}

			if (peripheralId == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'peripheralId'");
			}

			if (pluginResult == null) {
				VMPeripheral peripheral = _peripherals.get(peripheralId);
				if (peripheral == null) {
					pluginResult = new PluginResult(PluginResult.Status.ERROR, "Peripheral not found");
				}

				if (pluginResult == null) {
					pluginResult = peripheral.discoverServices(serviceUUIDs, callbackContext);
				}
			}

			if (pluginResult != null) {
				callbackContext.sendPluginResult(pluginResult);
			}
		}

		public void serviceDiscoverCharacteristics(JSONArray args, CallbackContext callbackContext)
				throws JSONException {
			String peripheralId = null;
			UUID serviceUUID = null;
			ArrayList<String> characteristicUUIDs = null;
			PluginResult pluginResult = null;

			if (args.length() >= 1) {
				peripheralId = args.getString(1);
			}
			if (args.length() >= 2) {
				characteristicUUIDs = new ArrayList<>();
				JSONArray uuids = args.getJSONArray(2);
				for (int i = 0; i < uuids.length(); i++) {
					String uuid = uuids.getString(i).toUpperCase();
					characteristicUUIDs.add(uuid);
				}
			}
			if (args.length() >= 3) {
				serviceUUID = UUID.fromString(args.getString(3));
			}

			if (peripheralId == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'peripheralId'");
			}

			if (serviceUUID == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'serviceUUID'");
			}

			if (pluginResult == null) {
				VMPeripheral peripheral = _peripherals.get(peripheralId);
				if (peripheral == null) {
					pluginResult = new PluginResult(PluginResult.Status.ERROR, "Peripheral not found");
				}

				if (pluginResult == null) {
					pluginResult = peripheral.discoverServiceCharacteristics(serviceUUID, characteristicUUIDs,
							callbackContext);
				}
			}

			if (pluginResult != null) {
				callbackContext.sendPluginResult(pluginResult);
			}
		}

		public void characteristicWrite(JSONArray args, CallbackContext callbackContext) throws JSONException {
			String peripheralId = null;
			UUID serviceUUID = null;
			UUID characteristicUUID = null;
			PluginResult pluginResult = null;
			byte[] data = null;
			boolean writeWithResponse = false;

			if (args.length() >= 1) {
				peripheralId = args.getString(1);
			}
			if (args.length() >= 2) {
				serviceUUID = UUID.fromString(args.getString(2));
			}
			if (args.length() >= 3) {
				characteristicUUID = UUID.fromString(args.getString(3));
			}
			if (args.length() >= 4) {
				String b64 = args.getString(4);
				data = Base64.decode(b64, Base64.DEFAULT);
			}
			if (args.length() >= 5) {
				writeWithResponse = args.getBoolean(5);
			}

			if (peripheralId == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'peripheralId'");
			}
			if (serviceUUID == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Service UUID has not been discovered");
			}
			if (characteristicUUID == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR,
						"Characteristic UUID has not been discovered");
			}
			if (data == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'data'");
			}

			if (pluginResult == null) {
				VMPeripheral peripheral = _peripherals.get(peripheralId);
				if (peripheral == null) {
					pluginResult = new PluginResult(PluginResult.Status.ERROR, "Peripheral not found");
				}

				if (pluginResult == null) {
					pluginResult = peripheral.writeCharacteristic(serviceUUID, characteristicUUID, data,
							writeWithResponse, callbackContext);
				}
			}

			if (pluginResult != null) {
				callbackContext.sendPluginResult(pluginResult);
			}
		}

		public void characteristicRead(JSONArray args, CallbackContext callbackContext) throws JSONException {
			String peripheralId = null;
			UUID serviceUUID = null;
			UUID characteristicUUID = null;
			PluginResult pluginResult = null;

			if (args.length() >= 1) {
				peripheralId = args.getString(1);
			}
			if (args.length() >= 2) {
				serviceUUID = UUID.fromString(args.getString(2));
			}
			if (args.length() >= 3) {
				characteristicUUID = UUID.fromString(args.getString(3));
			}

			if (peripheralId == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'peripheralId'");
			}
			if (serviceUUID == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Service UUID has not been discovered");
			}
			if (characteristicUUID == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR,
						"Characteristic UUID has not been discovered");
			}

			if (pluginResult == null) {
				VMPeripheral peripheral = _peripherals.get(peripheralId);
				if (peripheral == null) {
					pluginResult = new PluginResult(PluginResult.Status.ERROR, "Peripheral not found");
				}

				if (pluginResult == null) {
					pluginResult = peripheral.readCharacteristic(serviceUUID, characteristicUUID, callbackContext);
				}
			}

			if (pluginResult != null) {
				callbackContext.sendPluginResult(pluginResult);
			}
		}

		public void subscribeCharacteristicRead(JSONArray args, CallbackContext callbackContext) throws JSONException {
			String peripheralId = null;
			UUID serviceUUID = null;
			UUID characteristicUUID = null;
			PluginResult pluginResult = null;

			if (args.length() >= 1) {
				peripheralId = args.getString(1);
			}
			if (args.length() >= 2) {
				serviceUUID = UUID.fromString(args.getString(2));
			}
			if (args.length() >= 3) {
				characteristicUUID = UUID.fromString(args.getString(3));
			}

			if (peripheralId == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'peripheralId'");
			}
			if (serviceUUID == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Service UUID has not been discovered");
			}
			if (characteristicUUID == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR,
						"Characteristic UUID has not been discovered");
			}

			if (pluginResult == null) {
				VMPeripheral peripheral = _peripherals.get(peripheralId);
				if (peripheral == null) {
					pluginResult = new PluginResult(PluginResult.Status.ERROR, "Peripheral not found");
				}

				if (pluginResult == null) {
					pluginResult = peripheral.subscribeReadCharacteristic(serviceUUID, characteristicUUID,
							callbackContext);
				}
			}

			if (pluginResult != null) {
				callbackContext.sendPluginResult(pluginResult);
			}
		}

		public void unsubscribeCharacteristicRead(JSONArray args, CallbackContext callbackContext)
				throws JSONException {
			String peripheralId = null;
			UUID serviceUUID = null;
			UUID characteristicUUID = null;
			PluginResult pluginResult = null;

			if (args.length() >= 1) {
				peripheralId = args.getString(1);
			}
			if (args.length() >= 2) {
				serviceUUID = UUID.fromString(args.getString(2));
			}
			if (args.length() >= 3) {
				characteristicUUID = UUID.fromString(args.getString(3));
			}

			if (peripheralId == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Missing argument 'peripheralId'");
			}
			if (serviceUUID == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR, "Service UUID has not been discovered");
			}
			if (characteristicUUID == null) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR,
						"Characteristic UUID has not been discovered");
			}

			if (pluginResult == null) {
				VMPeripheral peripheral = _peripherals.get(peripheralId);
				if (peripheral == null) {
					pluginResult = new PluginResult(PluginResult.Status.ERROR, "Peripheral not found");
				}

				if (pluginResult == null) {
					pluginResult = peripheral.unsubscribeReadCharacteristic(serviceUUID, characteristicUUID,
							callbackContext);
				}
			}

			if (pluginResult != null) {
				callbackContext.sendPluginResult(pluginResult);
			}
		}
	}

	@Override
	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);

		final Context context = cordova.getActivity().getApplicationContext();
		final BluetoothManager bluetoothManager = (BluetoothManager) context
				.getSystemService(Context.BLUETOOTH_SERVICE);

		_bluetoothAdapter = bluetoothManager.getAdapter();

	}

	private VMScanClient getClientFromCommand(String clientId) {
		VMScanClient client = null;
		if (!_clients.containsKey(clientId)) {
			client = new VMScanClient(this.cordova, clientId, _bluetoothAdapter);
			_clients.put(clientId, client);
		} else {
			client = _clients.get(clientId);
		}
		return client;
	}

	private void deleteClient(String clientId, CallbackContext callbackContext) {
		if (!_clients.containsKey(clientId)) {
			callbackContext.error("Not found");
		} else {
			VMScanClient client = _clients.get(clientId);
			client.Dispose();
			_clients.remove(clientId);
			callbackContext.success();
		}
	}

	private class ExecuteHandler {
		String action;
		JSONArray args;
		CallbackContext callbackContext;

		public ExecuteHandler(String action, JSONArray args, CallbackContext callbackContext) {
			this.action = action;
			this.args = args;
			this.callbackContext = callbackContext;
		}

		public boolean execute() throws JSONException {
			if (args.length() >= 1) {
				final String clientId = args.getString(0);
				final VMScanClient client = getClientFromCommand(clientId);

				if (action.equals("clientSubscribeStateChange")) {
					client.subscribeStateChange(args, callbackContext);
					return true;
				} else if (action.equals("clientUnsubscribeStateChange")) {
					client.unsubscribeStateChange(args, callbackContext);
					return true;
				} else if (action.equals("clientStartScanning")) {
					client.startScanning(args, callbackContext);
					return true;
				} else if (action.equals("clientStopScanning")) {
					client.stopScanning(args, callbackContext);
					return true;
				} else if (action.equals("clientBlacklistUUIDs")) {
					client.blacklistUUIDs(args, callbackContext);
					return true;
				} else if (action.equals("peripheralConnect")) {
					client.peripheralConnect(args, callbackContext);
					return true;
				} else if (action.equals("peripheralDisconnect")) {
					client.peripheralDisconnect(args, callbackContext);
					return true;
				} else if (action.equals("peripheralRequestMtu")) {
					client.peripheralRequestMtu(args, callbackContext);
					return true;
				} else if (action.equals("peripheralDiscoverServices")) {
					client.peripheralDiscoverServices(args, callbackContext);
					return true;
				} else if (action.equals("serviceDiscoverCharacteristics")) {
					client.serviceDiscoverCharacteristics(args, callbackContext);
					return true;
				} else if (action.equals("characteristicWrite")) {
					client.characteristicWrite(args, callbackContext);
					return true;
				} else if (action.equals("characteristicRead")) {
					client.characteristicRead(args, callbackContext);
					return true;
				} else if (action.equals("subscribeCharacteristicRead")) {
					client.subscribeCharacteristicRead(args, callbackContext);
					return true;
				} else if (action.equals("unsubscribeCharacteristicRead")) {
					client.unsubscribeCharacteristicRead(args, callbackContext);
					return true;
				}
			} else {
				callbackContext.error("ClientId required");
			}
			return false;
		}
	}

	private HashMap<Integer, ExecuteHandler> _executeHandlers = new HashMap<Integer, ExecuteHandler>();
	private static final int REQUEST_ENABLE_BT = 300472;

	@Override
	public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults)
			throws JSONException {
		ExecuteHandler handler = null;
		synchronized (_executeHandlers) {
			handler = _executeHandlers.remove(requestCode);
		}
		if (handler != null) {
			String denied = "";
			for (int i = 0; i < permissions.length; i++) {
				if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
					if (denied.length() != 0) {
						denied += ", ";
					}
					denied += permissions[i];
				}
			}
			if (denied.length() > 0) {
				String msg = "Permissions denied " + denied;
				handler.callbackContext.error(msg);

				Toast.makeText(this.cordova.getActivity().getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
			} else {
				handler.execute();
			}
		}
	}

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		// Any action which touches BLUETOOTH permissions will be done through
		// ExecuteHandler to check permissions
		ExecuteHandler handler = null;
		if (action.equals("getVersion")) {
			JSONObject msg = new JSONObject();
			msg.put("platform", "Android");
			msg.put("version", PLUGIN_VERSION);
			callbackContext.success(msg);
			return true;
		} else if (action.equals("deleteClient")) {
			if (args.length() >= 1) {
				final String clientId = args.getString(0);
				deleteClient(clientId, callbackContext);
				return true;
			} else {
				callbackContext.error("ClientId required");
				return false;
			}
		} else {
			if (_bluetoothAdapter == null) {
				callbackContext.error("BLE not Enabled");
			} else {
				if (action.equals("clientSubscribeStateChange")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("clientUnsubscribeStateChange")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("clientStartScanning")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("clientStopScanning")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("clientBlacklistUUIDs")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("peripheralConnect")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("peripheralDisconnect")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("peripheralRequestMtu")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("peripheralDiscoverServices")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("serviceDiscoverCharacteristics")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("characteristicWrite")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("characteristicRead")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("subscribeCharacteristicRead")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				} else if (action.equals("unsubscribeCharacteristicRead")) {
					handler = new ExecuteHandler(action, args, callbackContext);
				}
			}
		}
		if (handler != null) {
			/*
			 * if (!_bluetoothAdapter.isEnabled()) {
			 * Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			 * startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
			 * } else {
			 */
			ArrayList<String> missingPermissions = new ArrayList<>();
			int targetVersion = cordova.getContext().getApplicationInfo().targetSdkVersion;
			if (targetVersion >= Build.VERSION_CODES.R && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				missingPermissions.add(ACCESS_FINE_LOCATION);
			}
			if (targetVersion >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				missingPermissions.add(BLUETOOTH_SCAN);
				missingPermissions.add(BLUETOOTH_CONNECT);
			}
			if (missingPermissions.size() > 0) {
				ArrayList<String> requiredPermissions = new ArrayList<>();
				for (String permission : missingPermissions) {
					if (!PermissionHelper.hasPermission(this, permission)) {
						requiredPermissions.add(permission);
					}
				}
				if (requiredPermissions.size() == 0) {
					return handler.execute();
				} else {
					int requestCode = 0;
					synchronized (_executeHandlers) {
						requestCode = _executeHandlers.size();
						_executeHandlers.put(requestCode, handler);
					}
					PermissionHelper.requestPermissions(this, requestCode, requiredPermissions.toArray(new String[0]));
					return true;
				}
			} else {
				return handler.execute();
			}
			// }
		}
		return false;
	}
}
