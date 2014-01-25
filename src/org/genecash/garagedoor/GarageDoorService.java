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
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

public class GarageDoorService extends IntentService {
	private static final int ONGOING_NOTIFICATION_ID = 1;
	private static final String TAG = "GarageDoorService";
	private static final String cmd = "TOGGLE\n";

	private BroadcastReceiver broadcastReceiver;
	private WifiManager wifiManager;
	private ConnectivityManager connManager;
	private WifiLock wifiLock;
	private WakeLock cpuLock;
	private NetworkInfo netInfo;

	private boolean done = false;

	private Notification notification;
	private Builder notifyBuilder;
	private NotificationManager notificationManager;
	private int ctrAttempts = 0;
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
		host = sSettings.getString(GarageSettings.PREFS_HOST, "");
		port = sSettings.getInt(GarageSettings.PREFS_PORT, 0);

		// start in foreground so we don't get killed
		notifyBuilder =
				new Notification.Builder(this).setContentTitle("Garage Door Opener").setContentText("Waiting to connect")
						.setSmallIcon(R.drawable.toggle_app);
		notification = notifyBuilder.build();
		startForeground(ONGOING_NOTIFICATION_ID, notification);

		wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
		connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		// this runs when the wi-fi connection state changes or scan results are available
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				Log.i(TAG, "onReceive");
				String action = intent.getAction();
				if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
					netInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
					if (!netInfo.isConnected()) {
						Log.i(TAG, "!isConnected");
						// new connection
						if (netInfo.getType() == ConnectivityManager.TYPE_WIFI) {
							Log.i(TAG, "TYPE_WIFI");
							// start scanning for networks
							wifiManager.startScan();
						}
					}
				} else if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
					// scan is done... if we don't wait for the scan results, and start another scan before the last one finishes, then
					// this receiver doesn't fire
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
					}
					// kick off another scan if necessary
					netInfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
					if (!netInfo.isConnected()) {
						Log.i(TAG, "startScan");
						notifyBuilder.setNumber(++ctrAttempts);
						notificationManager.notify(ONGOING_NOTIFICATION_ID, notifyBuilder.build());
						wifiManager.startScan();
					}
				}
			}
		};

		Log.i(TAG, "Acquiring locks");

		// acquire a wi-fi lock that allows for scan event callbacks to happen
		wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GarageWifiLock");
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

		// register for wi-fi network state changes
		registerReceiver(broadcastReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));

		// start the network scans if necessary
		netInfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		if (!netInfo.isConnected()) {
			Log.i(TAG, "initial startScan");
			wifiManager.startScan();
		}

		// start trying to connect to the Raspberry Pi
		while (!done) {
			netInfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
			if (netInfo.isConnected()) {
				notifyBuilder.setNumber(ctrAttempts++);
				notificationManager.notify(ONGOING_NOTIFICATION_ID, notifyBuilder.build());
				try {
					Socket sock = new Socket(host, port);
					Log.i(TAG, "TILT");
					sock.setSoTimeout(2000);
					BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ASCII"));
					if (br.readLine().equals("GARAGEDOOR")) {
						sock.getOutputStream().write(cmd.getBytes());
						Log.i(TAG, "opened");
					}
					sock.close();
				} catch (java.net.ConnectException e) {
					// ignore - this just means we didn't find it
				} catch (Exception e) {
					Log.e(TAG, e.getStackTrace().toString());
				}
			}

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
		// must be done as the very last code
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
}
