package org.genecash.garagedoor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

import android.app.IntentService;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

public class GarageDoorService extends IntentService {
	private static final int ONGOING_NOTIFICATION_ID = 1;
	private static final String TAG = "GarageDoorService";

	private BroadcastReceiver broadcastReceiver;
	private WifiManager wifiManager;
	private WifiLock wifiLock;
	private WakeLock cpuLock;

	private boolean done = false;

	private Notification notification;
	private Builder notifyBuilder;
	private NotificationManager notificationManager;
	private int ctrAttempts = 0;
	private String network;
	private String host;
	private int port;

	// the usual weird Java bullshit goin' on here
	public GarageDoorService() {
		super("GarageDoorService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		Log.i(TAG, "Starting");

		// pull host address & port from preferences
		SharedPreferences sSettings = getSharedPreferences(GarageSettings.PREFS_NAME, MODE_PRIVATE);
		network = sSettings.getString(GarageSettings.PREFS_NETWORK, "");
		host = sSettings.getString(GarageSettings.PREFS_EXT_IP, "");
		port = sSettings.getInt(GarageSettings.PREFS_EXT_PORT, 0);

		// start in foreground so we don't get killed
		notifyBuilder =
				new Notification.Builder(this).setContentTitle("Garage Door Opener").setContentText("Waiting to connect")
						.setSmallIcon(R.drawable.toggle_app);
		notification = notifyBuilder.build();
		startForeground(ONGOING_NOTIFICATION_ID, notification);

		wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		// this runs when scan results are available
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				Log.i(TAG, "onReceive");
				String action = intent.getAction();
				if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
					for (ScanResult i : wifiManager.getScanResults()) {
						if (i.SSID.equals(network)) {
							// start trying to connect to the Raspberry Pi
							notifyBuilder.setNumber(ctrAttempts++);
							notificationManager.notify(ONGOING_NOTIFICATION_ID, notifyBuilder.build());
							Log.i(TAG, "TILT");
							done = true;
							new OpenDoor().execute();
							break;
						}
					}
					if (!done) {
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) {
						}
						// kick off another scan
						Log.i(TAG, "startScan");
						wifiManager.startScan();
					}
				}
			}
		};

		Log.i(TAG, "Acquiring locks");

		// acquire a wi-fi lock that allows for scan event callbacks to happen
		wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_SCAN_ONLY, "GarageWifiLock");
		wifiLock.setReferenceCounted(true);
		wifiLock.acquire();
		Log.i(TAG, "wifiLock acquired");

		// acquire cpu lock so we stay awake to do work
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GarageCPULock");
		cpuLock.setReferenceCounted(true);
		cpuLock.acquire();
		Log.i(TAG, "cpuLock acquired");

		// register for wi-fi network scan results
		registerReceiver(broadcastReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

		// start the network scans
		Log.i(TAG, "initial startScan");
		wifiManager.startScan();

		// busy work
		while (!done) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
		}

		Log.i(TAG, "exiting");
	}

	@Override
	public void onDestroy() {
		Log.i(TAG, "onDestroy");
		cleanShutdown();
		if (wifiLock.isHeld()) {
			wifiLock.release();
		}
		// must be done as the very last piece of code
		if (cpuLock.isHeld()) {
			cpuLock.release();
		}
		super.onDestroy();
	}

	void cleanShutdown() {
		Log.i(TAG, "cleanShutdown");
		try {
			unregisterReceiver(broadcastReceiver);
			Log.i(TAG, "unregisterReceiver");
		} catch (Exception e) {
		}
	}

	// talk to the network in a separate thread
	class OpenDoor extends AsyncTask<Void, String, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			String cmd = "TOGGLE\n";
			try {
				Socket sock = new Socket(host, port);
				sock.setSoTimeout(2000);
				BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ASCII"));
				if (br.readLine().equals("GARAGEDOOR")) {
					sock.getOutputStream().write(cmd.getBytes());
					Log.i(TAG, "opened");
				}
				sock.close();
			} catch (Exception e) {
				Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
			}
			return null;
		}
	}
}
