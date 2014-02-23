package org.genecash.garagedoor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import android.app.IntentService;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
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

	// notifications
	private Notification notification;
	private Builder notifyBuilder;
	private String notificationAction = "org.genecash.garagedoor.EXIT";

	// settings
	private String network;
	private String host;
	private int port;

	// data connection management
	private boolean data;
	Object iConnectivityManager;
	Method getMobileDataEnabledMethod;
	Method setMobileDataEnabledMethod;

	private boolean done = true;
	private SSLSocketFactory sslSocketFactory;
	public SSLSocket sock;
	public BufferedReader buffRdr;

	// the usual weird Java bullshit goin' on here
	public GarageDoorService() {
		super("GarageDoorService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		Log.i(TAG, "service started");
		done = false;

		// start in foreground so we don't get killed
		// it also happens to provide an easy way to terminate by clicking the notification
		notifyBuilder =
				new Notification.Builder(this)
						.setContentTitle("Garage Door Opener")
						.setContentText("Waiting to connect")
						.setSmallIcon(R.drawable.open_app)
						.setContentIntent(
								PendingIntent.getBroadcast(this, 0, new Intent(notificationAction), PendingIntent.FLAG_UPDATE_CURRENT));
		notification = notifyBuilder.build();
		startForeground(ONGOING_NOTIFICATION_ID, notification);

		// initialize SSL
		sslSocketFactory = Utilities.initSSL(this);

		// pull locate network name, host address & port from preferences
		SharedPreferences sSettings = getSharedPreferences(GarageSettings.PREFS_NAME, MODE_PRIVATE);
		network = sSettings.getString(GarageSettings.PREFS_NETWORK, "");
		host = sSettings.getString(GarageSettings.PREFS_EXT_IP, "");
		port = sSettings.getInt(GarageSettings.PREFS_EXT_PORT, 0);
		data = sSettings.getBoolean(GarageSettings.PREFS_DATA, false);

		// set up the ability to test/set data connection
		// since we're not really allowed to use this, we've got to use reflection to dig it out
		try {
			ConnectivityManager conman = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
			Class<?> conmanClass = Class.forName(conman.getClass().getName());

			Field iConnectivityManagerField = conmanClass.getDeclaredField("mService");
			iConnectivityManagerField.setAccessible(true);
			iConnectivityManager = iConnectivityManagerField.get(conman);
			Class<?> iConnectivityManagerClass = Class.forName(iConnectivityManager.getClass().getName());

			setMobileDataEnabledMethod = iConnectivityManagerClass.getDeclaredMethod("setMobileDataEnabled", Boolean.TYPE);
			setMobileDataEnabledMethod.setAccessible(true);

			getMobileDataEnabledMethod = iConnectivityManagerClass.getDeclaredMethod("getMobileDataEnabled");
			getMobileDataEnabledMethod.setAccessible(true);
		} catch (Exception e) {
			data = false;
		}

		wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);

		// this runs when scan results are available, or the notification gets clicked
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				Log.i(TAG, "onReceive");
				String action = intent.getAction();
				if (action.equals(notificationAction)) {
					// exit when notification clicked
					Log.i(TAG, "exit when notification clicked");
					done = true;
				}
				if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
					Log.i(TAG, "scan results available");
					for (ScanResult i : wifiManager.getScanResults()) {
						if (i.SSID.equals(network)) {
							Log.i(TAG, "network found");
							// tell the Raspberry Pi to open the door
							doTask(new OpenDoor());
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

		// turn on data if the user wants it done automatically
		if (data && !getDataEnabled()) {
			setDataEnabled(true);
		}

		// connect to Raspberry Pi over mobile data
		doTask(new Connect());

		Log.i(TAG, "start loop");
		// busy work
		while (!done) {
			doTask(new Ping());
			try {
				Thread.sleep(10 * 1000);
			} catch (InterruptedException e) {
			}
		}

		// turn off data if we turned it on
		if (data && getDataEnabled()) {
			setDataEnabled(false);
		}
		Log.i(TAG, "service done");
	}

	public void doTask(AsyncTask<Void, String, Integer> task) {
		try {
			task.execute();
			task.get();
		} catch (Exception e) {
			Log.e(TAG, "doTask Exception: " + Log.getStackTraceString(e));
		}
	}

	// this will keep multiple startups from being enqueued
	@Override
	public void onStart(Intent intent, int startId) {
		Log.i(TAG, "onStart");
		if (!done) {
			Log.i(TAG, "onStart dropped");
			return;
		}
		super.onStart(intent, startId);
	}

	@Override
	public void onDestroy() {
		unregisterReceiver(broadcastReceiver);

		if (wifiLock.isHeld()) {
			wifiLock.release();
		}

		// must be done as the very last piece of code
		if (cpuLock.isHeld()) {
			cpuLock.release();
		}

		super.onDestroy();
	}

	// SSL connection takes a very long time (3 or 4 seconds) so we do it at startup
	// a nice side-effect is that it keeps the data connection awake
	class Connect extends AsyncTask<Void, String, Integer> {
		@Override
		protected Integer doInBackground(Void... params) {
			boolean connected = false;
			Log.i(TAG, "Connect doInBackground");
			while (!connected && !done) {
				try {
					sock = (SSLSocket) sslSocketFactory.createSocket(host, port);
					sock.setSoTimeout(2000);
					buffRdr = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ASCII"));
					connected = buffRdr.readLine().equals("GARAGEDOOR");
				} catch (Exception e) {
					// we will normally have a couple exceptions before the network comes completely up
					try {
						Thread.sleep(2 * 1000);
					} catch (InterruptedException e1) {
					}
				}
			}
			Log.i(TAG, "Connect done");
			return 0;
		}
	}

	// bang out the command to open the door
	class OpenDoor extends AsyncTask<Void, String, Integer> {
		@Override
		protected Integer doInBackground(Void... params) {
			Log.i(TAG, "OpenDoor doInBackground");
			String cmd = "OPEN\n";
			try {
				sock.getOutputStream().write(cmd.getBytes());
				done = buffRdr.readLine().equals("DONE");
				sock.close();
			} catch (Exception e) {
				Log.e(TAG, "OpenDoor Exception: " + Log.getStackTraceString(e));
				try {
					Thread.sleep(500);
				} catch (InterruptedException e1) {
				}
			}
			Log.i(TAG, "OpenDoor done: " + done);
			return 0;
		}
	}

	// keep the data connection awake
	class Ping extends AsyncTask<Void, String, Integer> {
		@Override
		protected Integer doInBackground(Void... params) {
			Log.i(TAG, "Ping doInBackground");
			String cmd = "PING\n";
			try {
				sock.getOutputStream().write(cmd.getBytes());
			} catch (Exception e) {
				Log.e(TAG, "Ping Exception: " + Log.getStackTraceString(e));
				try {
					Thread.sleep(500);
					doTask(new Connect());
				} catch (InterruptedException e1) {
				}
			}
			Log.i(TAG, "Ping done: " + done);
			return 0;
		}
	}

	public boolean getDataEnabled() {
		try {
			return (Boolean) getMobileDataEnabledMethod.invoke(iConnectivityManager);
		} catch (Exception e) {
			Log.e(TAG, "getDataEnabled Exception: " + Log.getStackTraceString(e));
		}
		return false;
	}

	public void setDataEnabled(boolean value) {
		try {
			setMobileDataEnabledMethod.invoke(iConnectivityManager, value);
		} catch (Exception e) {
			Log.e(TAG, "setDataEnabled Exception: " + Log.getStackTraceString(e));
		}
	}
}
