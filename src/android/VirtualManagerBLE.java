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
import com.neovisionaries.bluetooth.ble.*;
import com.neovisionaries.bluetooth.ble.advertising.*;
import com.neovisionaries.bluetooth.ble.util.*;

import static android.bluetooth.le.ScanSettings.*;

/* This class implements a BLE receiver (does not yet support Connectable devices)
   Its JS API is a little perculiar in how it "munges" packet data - it is
   forming the JS packet data to be compatible with iOS VirtualManagerBLE plugin
   This is most evident with the "data" segment being a concatenation of all Manufacturing Data
   segments into 1 byte array - this is how iOS under the hood gives data to the VirtualManagerBLE iOS plugin
   so we are duplicating that behaviour here
 */
public class VirtualManagerBLE extends CordovaPlugin {
	private static final String PLUGIN_VERSION = "1.4.0";

	private static final String LOGTAG = "VirtualManagerBLE";

	private BluetoothAdapter _bluetoothAdapter;
	private CallbackContext _callbackContext;
	private HashMap<String, VMScanClient> _clients = new HashMap<String, VMScanClient>();

	private static int _msgId = 0;

	private final static String BASE_UUID = "00000000-0000-1000-8000-00805F9B34FB";
	public static ParcelUuid parseUUID(String uuid) {
		String uuidStr = uuid;
		if (uuid.length() == 4) {			// 16 bit UUID
			uuidStr = "0000" + uuid + BASE_UUID.substring(8);
		} else if (uuid.length() == 8) {    // 32 bit UUID
			uuidStr = uuid + BASE_UUID.substring(8);
		}
		try {
			return ParcelUuid.fromString(uuidStr);
		} catch (IllegalArgumentException iae) {
			return null;
		}
	}

	public static String getBluetoothAdapterStateName(BluetoothAdapter adapter) {
		switch(adapter.getState()) {
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

			// "data" under iOS is all manufacturer data segments concat together, 1st segment has the CompanyId
			// others do not
			byte[] iOSStyleManufacturerData = new byte[record.getBytes().length];
			int iOSStyleManufacturerDataIndex = 0;

			JSONObject serviceDataInfo = new JSONObject();
			JSONArray uuidsInfo = new JSONArray();
			JSONArray solicitedUUIDsInfo = new JSONArray();
			for (ADStructure structure : structures) {
				if (structure instanceof ServiceData) {
					ServiceData serviceData = (ServiceData)structure;
					String uuidStr = serviceData.getServiceUUID().toString().toUpperCase();
					switch (serviceData.getType()) {
						case 0x16:		// 16bit ServiceData
							serviceDataInfo.put(uuidStr.substring(4, 8), Base64.encodeToString(structure.getData(), 2, structure.getData().length - 2, Base64.NO_WRAP));
							break;
						case 0x20:		// 32bit ServiceData
							serviceDataInfo.put(uuidStr.substring(0, 8), Base64.encodeToString(structure.getData(), 4, structure.getData().length - 4, Base64.NO_WRAP));
							break;
						case 0x21:		// 128bit ServiceData
							serviceDataInfo.put(uuidStr, Base64.encodeToString(structure.getData(), 16, structure.getData().length - 16, Base64.NO_WRAP));
							break;
					}

				} else if (structure instanceof UUIDs) {
					UUIDs uuids = (UUIDs)structure;
					for (UUID uuid : uuids.getUUIDs()) {
						String uuidStr = uuid.toString().toUpperCase();
						switch(uuids.getType()) {
							case 0x02:	// 16bit UUID (Incomplete list)
							case 0x03:	// 16bit UUID (Complete list)
								uuidsInfo.put(uuidStr.substring(4, 8));
								break;
							case 0x04:	// 32bit UUID (Incomplete list)
							case 0x05:	// 32bit UUID (Complete list)
								uuidsInfo.put(uuidStr.substring(0, 8));
								break;
							case 0x06:	// 128bit UUID (Incomplete list)
							case 0x07:	// 128bit UUID (Complete list)
								uuidsInfo.put(uuidStr);
								break;
							case 0x14:	// 16bit Service Solicited UUID
								solicitedUUIDsInfo.put(uuidStr.substring(4, 8));
								break;
							case 0x15:	// 128bit Service Solicited UUID
								solicitedUUIDsInfo.put(uuidStr);
								break;
							case 0x1F:	// 32bit Service Solicited UUID
								solicitedUUIDsInfo.put(uuidStr.substring(0, 8));
								break;
						}
					}

				} else if (structure instanceof ADManufacturerSpecific) {
					ADManufacturerSpecific ms = (ADManufacturerSpecific)structure;

					if (iOSStyleManufacturerDataIndex == 0) {
						System.arraycopy(ms.getData(), 0, iOSStyleManufacturerData, iOSStyleManufacturerDataIndex, ms.getData().length);
						iOSStyleManufacturerDataIndex += ms.getData().length;
					} else {
						System.arraycopy(ms.getData(), 2, iOSStyleManufacturerData, iOSStyleManufacturerDataIndex, ms.getData().length - 2);
						iOSStyleManufacturerDataIndex += ms.getData().length - 2;
					}
				} else if (structure instanceof TxPowerLevel) {
					TxPowerLevel txPowerLevel = (TxPowerLevel)structure;
					advertisementInfo.put("txPower", txPowerLevel.getLevel());
				}
			}
			if (iOSStyleManufacturerDataIndex > 0) {
				String b64 = Base64.encodeToString(iOSStyleManufacturerData, 0, iOSStyleManufacturerDataIndex, Base64.NO_WRAP);
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
			jobj.put("advertisement", advertisementInfo);
		}

		return jobj;
	}

	public class VMScanClient extends ScanCallback {
		public final String ClientId;
		private final BluetoothAdapter _adapter;
		private final BluetoothLeScanner _scanner;
		private final CallbackContext _callbackContext;

		private int _groupSize = -1;
		private long _groupTimeout = 0;

		private CallbackContext _scanResultCallbackId;
		private CallbackContext _stateChangeCallbackId;
		private ArrayList<JSONObject> _groupedScans = new ArrayList<JSONObject>();
		private HashSet<String> _blacklistedUUIDS = new HashSet<String>();

		public VMScanClient(String clientId, CallbackContext callbackContext, BluetoothAdapter bluetoothAdapter) {
			_adapter = bluetoothAdapter;
			_scanner = bluetoothAdapter.getBluetoothLeScanner();
			ClientId = clientId;
			_callbackContext = callbackContext;
		}

		public void Dispose() {
			synchronized (this) {
				_scanResultCallbackId = null;
				_stateChangeCallbackId = null;
				_groupedScans = null;
				_blacklistedUUIDS = null;
			}
		}

		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			synchronized (this) {
				if (_scanResultCallbackId != null) {
					JSONArray array = new JSONArray();
					try {
						if (_blacklistedUUIDS.contains(result.getDevice().getAddress())) {
							return;
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
							if (_blacklistedUUIDS.contains(sr.getDevice().getAddress())) {
								continue;
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

				// Stop scan first, harmless if not already scanning but stops startScan from failing if we are already scanning
				_scanner.stopScan(this);

				_scanner.startScan(filters, settings, this);

				PluginResult result = new PluginResult(PluginResult.Status.OK, (String) null);
				result.setKeepCallback(true);

				callbackContext.sendPluginResult(result);
			}
		}

		public void stopScanning(JSONArray args, CallbackContext callbackContext) {
			synchronized (this) {
				_scanResultCallbackId = null;
				_scanner.stopScan(this);
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

		public void peripheralConnect(JSONArray args, CallbackContext callbackContext) {

		}

		public void peripheralDisconnect(JSONArray args, CallbackContext callbackContext) {
		}

		public void peripheralDiscoverServices(JSONArray args, CallbackContext callbackContext) {
		}

		public void serviceDiscoverCharacteristics(JSONArray args, CallbackContext callbackContext) {
		}

		public void characteristicWrite(JSONArray args, CallbackContext callbackContext) {
		}

		public void characteristicRead(JSONArray args, CallbackContext callbackContext) {
		}
	}

	@Override
	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);

		final Context context = cordova.getActivity().getApplicationContext();
		final BluetoothManager bluetoothManager =
				(BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

		_bluetoothAdapter = bluetoothManager.getAdapter();

		// Ensures Bluetooth is available on the device and it is enabled. If not,
		// displays a dialog requesting user permission to enable Bluetooth.
		if (_bluetoothAdapter == null || !_bluetoothAdapter.isEnabled()) {
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			cordova.getActivity().startActivityForResult(enableBtIntent, 1);
		}
	}

	private VMScanClient getClientFromCommand(String clientId)
	{
		VMScanClient client = null;
		if (!_clients.containsKey(clientId))
		{
			client = new VMScanClient(clientId, _callbackContext, _bluetoothAdapter);
			_clients.put(clientId, client);
		}
		else
		{
			client = _clients.get(clientId);
		}
		return client;
	}

	private void deleteClient(String clientId, CallbackContext callbackContext)
	{
		if (!_clients.containsKey(clientId)) {
			callbackContext.error("Not found");
		} else {
			VMScanClient client = _clients.get(clientId);
			client.Dispose();
			_clients.remove(clientId);
			callbackContext.success();
		}
	}

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
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
					}
				} else {
					callbackContext.error("ClientId required");
				}
			}
		}
		return false;
	}
}
