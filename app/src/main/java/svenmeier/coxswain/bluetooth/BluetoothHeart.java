package svenmeier.coxswain.bluetooth;

import android.Manifest;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.widget.Toast;

import propoid.util.content.Preference;
import svenmeier.coxswain.Coxswain;
import svenmeier.coxswain.Heart;
import svenmeier.coxswain.R;
import svenmeier.coxswain.gym.Measurement;
import svenmeier.coxswain.util.PermissionBlock;

/**
 * {@link Heart} reading from a connected Bluetooth device.
 */
public class BluetoothHeart extends Heart {

	private static final int CONNECT_TIMEOUT_MILLIS = 15000;

	private Handler handler = new Handler();

	private Connection connection;

	private Preference<String> devicePreference;

	private Preference<Boolean> devicePreferenceRemember;

	public BluetoothHeart(Context context, Measurement measurement, Callback callback) {
		super(context, measurement, callback);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
			toast(context.getString(R.string.bluetooth_heart_no_bluetooth));
			return;
		}

		devicePreference = Preference.getString(context, R.string.preference_bluetooth_heart_device);
		devicePreferenceRemember = Preference.getBoolean(context, R.string.preference_bluetooth_heart_device_remember);

		connect(new Permissions());
	}

	@Override
	public void destroy() {
		if (connection != null) {
			connection.close();
			connection = null;
		}
	}

	private void toast(final String text) {
		handler.post(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(context, text, Toast.LENGTH_LONG).show();
			}
		});
	}

	private void connect(Connection connection) {
		if (this.connection != null) {
			this.connection.close();;
		}

		this.connection = connection;
		
		connection.open();
	}

	private interface Connection {

		void open();

		void close();
	}

	private class Permissions extends PermissionBlock implements Connection {

		public Permissions() {
			super(context);
		}

		@Override
		public void open() {
			acquirePermissions(Manifest.permission.ACCESS_FINE_LOCATION);
		}

		@Override
		public void close() {
			abortPermissions();
		}

		@Override
		protected void onPermissionsApproved() {
			connect(new LocationServices());
		}
	}

	private class LocationServices extends BroadcastReceiver implements Connection {

		private boolean registered;

		public void open() {
			if (isRequired()) {
				if (isEnabled() == false) {
					toast(context.getString(R.string.bluetooth_heart_no_location));

					Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					context.startActivity(intent);

					IntentFilter filter = new IntentFilter();
					filter.addAction(LocationManager.MODE_CHANGED_ACTION);
					context.registerReceiver(this, filter);
					registered = true;

					return;
				}
			}

			proceed();
		}

		/**
		 * Location services must be enabled for Apps built for M and running on M or later.
		 */
		private boolean isRequired() {
			boolean builtForM = context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.M;
			boolean runningOnM = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;

			return builtForM && runningOnM;
		}

		private boolean isEnabled() {
			LocationManager manager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);

			Criteria criteria = new Criteria();
			criteria.setAccuracy(Criteria.ACCURACY_COARSE);
			criteria.setAltitudeRequired(false);
			criteria.setBearingRequired(false);
			criteria.setCostAllowed(true);
			criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);

			String provider = manager.getBestProvider(criteria, true);

			return provider != null && LocationManager.PASSIVE_PROVIDER.equals(provider) == false;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if (connection != this) {
				return;
			}

			if (isEnabled()) {
				proceed();
			}
		}

		private void proceed() {
			connect(new Bluetooth());
		}

		@Override
		public void close() {
			if (registered) {
				context.unregisterReceiver(this);
				registered = false;
			}
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
	private class Bluetooth extends BroadcastReceiver implements Connection {

		private BluetoothAdapter adapter;

		private boolean registered;

		@Override
		public void open() {
			BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
			adapter = manager.getAdapter();

			if (adapter == null) {
				toast(context.getString(R.string.bluetooth_heart_no_bluetooth));
				return;
			}

			if (adapter.isEnabled() == false) {
				IntentFilter filter = new IntentFilter();
				filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
				filter.addAction(LocationManager.MODE_CHANGED_ACTION);
				context.registerReceiver(this, filter);
				registered = true;

				adapter.enable();
				return;
			}

			proceed();
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if (connection != this) {
				return;
			}

			int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
			if (state == BluetoothAdapter.STATE_ON) {
				proceed();
			}
		}

		private void proceed() {
			connect(new SelectionConnection());
		}

		@Override
		public void close() {
			if (registered) {
				context.unregisterReceiver(this);
				registered = false;
			}

			adapter = null;
		}
	}

	private class SelectionConnection extends BroadcastReceiver implements Connection {

		@Override
		public void open() {
			String address = devicePreference.get();
			if (address != null && devicePreferenceRemember.get()) {
				proceed(address);
				return;
			}

			String name = context.getString(R.string.bluetooth_heart);
			IntentFilter filter = BluetoothActivity.start(context, name, BlueUtils.SERVICE_HEART_RATE.toString());
			context.registerReceiver(this, filter);
		}

		@Override
		public final void onReceive(Context context, Intent intent) {
			if (connection == this) {
				String address = intent.getStringExtra(BluetoothActivity.DEVICE_ADDRESS);

				devicePreference.set(address);
				devicePreferenceRemember.set(intent.getBooleanExtra(BluetoothActivity.DEVICE_REMEMBER, false));

				proceed(address);
			}
		}

		private void proceed(String address) {
			connect(new GattConnection(address));
		}

		@Override
		public void close() {
			context.unregisterReceiver(this);
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
	private class GattConnection extends BluetoothGattCallback implements Connection, Runnable {

		private final String address;

		private BluetoothAdapter adapter;

		private BluetoothGatt connected;

		private BluetoothGattCharacteristic heartRateMeasurement;

		private GattConnection(String address) {
			this.address = address;
		}

		private void select() {
			devicePreference.set(null);
			devicePreferenceRemember.set(false);

			connect(new SelectionConnection());
		}

		@Override
		public synchronized void open() {
			BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
			adapter = manager.getAdapter();

			try {
				toast(context.getString(R.string.bluetooth_heart_connecting, address));

				BluetoothDevice device = adapter.getRemoteDevice(address);
				Log.d(Coxswain.TAG, "bluetooth heart connecting " + device.getAddress());
				connected = device.connectGatt(context, false, this);

				handler.removeCallbacks(this);
				handler.postDelayed(this, CONNECT_TIMEOUT_MILLIS);
			} catch (IllegalArgumentException invalid) {
				select();
			}
		}

		@Override
		public synchronized void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			if (adapter == null) {
				return;
			}

			String address = gatt.getDevice().getAddress();

			if (newState == BluetoothProfile.STATE_CONNECTED) {
				Log.d(Coxswain.TAG, "bluetooth heart connected " + address);

				connected = gatt;
				connected.discoverServices();
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED ) {
				if (connected != null && connected.getDevice().getAddress().equals(address)) {
					Log.d(Coxswain.TAG, "bluetooth heart disconnected " + address);

					if (adapter.isEnabled()) {
						toast(context.getString(R.string.bluetooth_heart_disconnected, address));

						select();
					} else {
						close();
					}
				}
			}
		}

		public synchronized void close() {
			if (connected != null) {
				connected.close();
				connected = null;
			}

			heartRateMeasurement = null;

			adapter = null;
		}

		@Override
		public synchronized void onServicesDiscovered(BluetoothGatt gatt, int status) {
			if (connected == null) {
				return;
			}

			BluetoothGattService service = gatt.getService(BlueUtils.SERVICE_HEART_RATE);
			if (service == null) {
				Log.d(Coxswain.TAG, "bluetooth no heart rate");
			} else {
				heartRateMeasurement = service.getCharacteristic(BlueUtils.CHARACTERISTIC_HEART_RATE_MEASUREMENT);
				if (heartRateMeasurement == null) {
					Log.d(Coxswain.TAG, "bluetooth no heart rate measurement");
				} else {
					if (BlueUtils.enableNotification(gatt, heartRateMeasurement)) {
						toast(context.getString(R.string.bluetooth_heart_connected, gatt.getDevice().getAddress()));
						return;
					}
					Log.d(Coxswain.TAG, "bluetooth no heart rate measurement notification");
				}
			}

			heartRateMeasurement = null;
			toast(context.getString(R.string.bluetooth_heart_not_found, gatt.getDevice().getAddress()));

			select();
		}
		
		@Override
		@WorkerThread
		public synchronized void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			if (connected == null) {
				return;
			}

			int heartRate;
			Fields fields = new Fields(characteristic, Fields.UINT8);
			if (fields.flag(0)) {
				heartRate = fields.get(Fields.UINT16);
			} else {
				heartRate = fields.get(Fields.UINT8);
			}

			onHeartRate(heartRate);
		}

		/**
		 * @see BluetoothHeart#CONNECT_TIMEOUT_MILLIS
		 */
		@Override
		public void run() {
			if (connected != null && heartRateMeasurement == null) {
				toast(context.getString(R.string.bluetooth_heart_failed, connected.getDevice().getAddress()));

				select();
			}
		}
	}
}
