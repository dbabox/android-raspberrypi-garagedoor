package org.genecash.garagedoor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

import android.app.IntentService;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.PendingIntent;
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

	private Notification notification;
	private Builder notifyBuilder;
	private String notificationAction = "org.genecash.garagedoor.EXIT";

	private String network;
	private String host;
	private int port;

	private boolean done = false;

	// the usual weird Java bullshit goin' on here
	public GarageDoorService() {
		super("GarageDoorService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		// start in foreground so we don't get killed
		// it also happens to provide an easy way to terminate by clicking the notification
		notifyBuilder =
				new Notification.Builder(this)
						.setContentTitle("Garage Door Opener")
						.setContentText("Waiting to connect")
						.setSmallIcon(R.drawable.toggle_app)
						.setContentIntent(
								PendingIntent.getBroadcast(this, 0, new Intent(notificationAction), PendingIntent.FLAG_UPDATE_CURRENT));
		notification = notifyBuilder.build();
		startForeground(ONGOING_NOTIFICATION_ID, notification);

		// pull locate network name, host address & port from preferences
		SharedPreferences sSettings = getSharedPreferences(GarageSettings.PREFS_NAME, MODE_PRIVATE);
		network = sSettings.getString(GarageSettings.PREFS_NETWORK, "");
		host = sSettings.getString(GarageSettings.PREFS_EXT_IP, "");
		port = sSettings.getInt(GarageSettings.PREFS_EXT_PORT, 0);

		wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);

		// this runs when scan results are available, or the notification gets clicked
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();
				if (action.equals(notificationAction)) {
					// exit when notification clicked
					done = true;
				}
				if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
					for (ScanResult i : wifiManager.getScanResults()) {
						if (i.SSID.equals(network)) {
							// tell the Raspberry Pi to open the door
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
						wifiManager.startScan();
					}
				}
			}
		};

		// acquire a wi-fi lock that allows for scan event callbacks to happen
		wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_SCAN_ONLY, "GarageWifiLock");
		wifiLock.setReferenceCounted(true);
		wifiLock.acquire();

		// acquire cpu lock so we stay awake to do work
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GarageCPULock");
		cpuLock.setReferenceCounted(true);
		cpuLock.acquire();

		// register for wi-fi network scan results
		registerReceiver(broadcastReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

		// register for notification clicks
		registerReceiver(broadcastReceiver, new IntentFilter(notificationAction));

		// start the network scans
		wifiManager.startScan();

		// busy work
		while (!done) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
		}
	}

	@Override
	public void onDestroy() {
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
		try {
			unregisterReceiver(broadcastReceiver);
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
				}
				sock.close();
			} catch (Exception e) {
				Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
			}
			return null;
		}
	}
}
