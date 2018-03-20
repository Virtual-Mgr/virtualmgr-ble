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

import android.bluetooth.BluetoothAdapter;
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


/**
 * This class echoes a string called from JavaScript.
 */
public class VirtualManagerBLE extends CordovaPlugin {
	private final String PLUGIN_VERSION = "1.2.0";

	private final String LOGTAG = "VirtualManagerBLE";

	private BluetoothAdapter _bluetoothAdapter;
	private CallbackContext _callbackContext;
	private HashMap<String, VMScanClient> _clients = new HashMap<String, VMScanClient>();

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
		jobj.put("id", scanResult.getDevice().getAddress());
		if (scanResult.getDevice().getName() != null) {
			jobj.put("name", scanResult.getDevice().getName());
		}
		jobj.put("rssi", scanResult.getRssi());
		ScanRecord record = scanResult.getScanRecord();
		if (record != null) {
			JSONObject advertisementInfo = new JSONObject();
			SparseArray<byte[]> mfds = record.getManufacturerSpecificData();

			if (record.getBytes() != null && mfds != null) {
				byte[] data = new byte[record.getBytes().length];
				int p = 0;
				for (int i = 0; i < mfds.size(); i++) {
					int key = mfds.keyAt(i);
					byte[] mfd = mfds.valueAt(i);
					if (mfd != null) {
						data[p++] = (byte) ((key >> 0) & 0xFF);
						data[p++] = (byte) ((key >> 8) & 0xFF);
						System.arraycopy(mfd, 0, data, p, mfd.length);
						p += mfd.length;
					}
				}
				advertisementInfo.put("data", Base64.encodeToString(data, 0, p, Base64.DEFAULT));
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
			_scanResultCallbackId = null;
			_stateChangeCallbackId = null;
			_groupedScans = null;
			_blacklistedUUIDS = null;
		}

		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			Log.d(LOGTAG, "onScanResult " + callbackType + " = " + result.toString());
		}

		/**
		 * Callback when batch results are delivered.
		 *
		 * @param results List of scan results that are previously scanned.
		 */
		@Override
		public void onBatchScanResults(List<ScanResult> results) {
			if (results.size() > 0 && _scanResultCallbackId != null) {
				JSONArray array = new JSONArray();
				for(ScanResult sr : results) {
					try {
						JSONObject jobj = getPeripheralInfo(sr);
						array.put(jobj);
					} catch (JSONException je) {
						LOG.e(LOGTAG, "onBatchScanResults threw " + je.toString());
					}
				}

				PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, array);
				pluginResult.setKeepCallback(true);

				_scanResultCallbackId.sendPluginResult(pluginResult);
			}
		}

		/**
		 * Callback when scan could not be started.
		 *
		 * @param errorCode Error code (one of SCAN_FAILED_*) for scan failure.
		 */
		@Override
		public void onScanFailed(int errorCode) {
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

		public void subscribeStateChange(JSONArray args, CallbackContext callbackContext) {
			this._stateChangeCallbackId = callbackContext;

			final String state = getBluetoothAdapterStateName(_adapter);
			PluginResult result = new PluginResult(PluginResult.Status.OK, state);
			result.setKeepCallback(true);

			callbackContext.sendPluginResult(result);
		}

		public void unsubscribeStateChange(JSONArray args, CallbackContext callbackContext) {
			this._stateChangeCallbackId = null;
		}

		public void startScanning(JSONArray args, CallbackContext callbackContext) throws JSONException {
			this._scanResultCallbackId = callbackContext;

			List<ScanFilter> filters = null;
			if (args.length() >= 2) {
				filters = new ArrayList<ScanFilter>();
				JSONArray serviceUUIDs = args.getJSONArray(1);
				for(int i = 0 ; i < serviceUUIDs.length() ; i++) {
					String serviceUUID = serviceUUIDs.getString(i);
					final ScanFilter.Builder builder = new ScanFilter.Builder();
					builder.setServiceUuid(parseUUID(serviceUUID));
					filters.add(builder.build());
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

				settings = builder.build();

				if (options.has("groupSize")) {
					_groupSize = options.getInt("groupSize");
				}
			}

			// Stop scan first, harmless if not already scanning but stops startScan from failing if we are already scanning
			_scanner.stopScan(this);

			_scanner.startScan(filters, settings, this);

			PluginResult result = new PluginResult(PluginResult.Status.OK, (String)null);
			result.setKeepCallback(true);

			callbackContext.sendPluginResult(result);
		}

		public void stopScanning(JSONArray args, CallbackContext callbackContext) {
			_scanResultCallbackId = null;
			_scanner.stopScan(this);
			callbackContext.success();
		}

		public void blacklistUUIDs(JSONArray args, CallbackContext callbackContext) throws JSONException {
			if (args.length() >= 2) {
				JSONArray uuids = args.getJSONArray(1);

				for(int i = 0 ; i < uuids.length() ; i++) {
					String uuid = uuids.getString(i);
					_blacklistedUUIDS.add(uuid);
				}
				callbackContext.success();
			} else {
				callbackContext.error("Missing argument 'UUIDs'");
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
