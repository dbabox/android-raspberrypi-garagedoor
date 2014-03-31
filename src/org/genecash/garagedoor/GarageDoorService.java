package org.genecash.garagedoor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

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

	private BroadcastReceiver broadcastReceiver = null;
	private WifiManager wifiManager;
	private WifiLock wifiLock;
	private WakeLock cpuLock;

	// notifications
	private Notification notification;
	private Builder notifyBuilder;
	private String notificationAction = "org.genecash.garagedoor.exit";

	// settings
	private String network;
	private String host;
	private int port;

	// data connection management
	private boolean turnoff_data;
	private boolean turnoff_wifi;
	Object iConnectivityManager;
	Method getMobileDataEnabledMethod;
	Method setMobileDataEnabledMethod;

	private boolean done = true;
	private SSLSocketFactory sslSocketFactory;
	public SSLSocket sock;
	public BufferedReader buffRdr;

	// our own logfile
	public static final Logger logger = Logger.getLogger("logger");

	// the usual weird Java things goin' on here
	public GarageDoorService() {
		super("GarageDoorService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		// go through the pain of setting up our own logging
		try {
			Handler h = new FileHandler(getExternalFilesDir(null) + "/log%g%u.txt", 256 * 1024, 25);
			h.setFormatter(new CustomFormatter());
			logger.addHandler(h);
			logger.setUseParentHandlers(false);
		} catch (Exception e) {
			Log.e("garagedoorservice", "something went to shit with the logging\n" + Log.getStackTraceString(e));
			return;
		}

		log("service started");
		done = false;

		// pull locate network name, host address & port from preferences
		SharedPreferences sSettings = getSharedPreferences(GarageSettings.PREFS_NAME, MODE_PRIVATE);
		network = sSettings.getString(GarageSettings.PREFS_NETWORK, "");
		host = sSettings.getString(GarageSettings.PREFS_EXT_IP, "");
		port = sSettings.getInt(GarageSettings.PREFS_EXT_PORT, 0);
		turnoff_data = sSettings.getBoolean(GarageSettings.PREFS_DATA, false);
		turnoff_wifi = sSettings.getBoolean(GarageSettings.PREFS_WIFI, false);

		wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);

		// abort if we're already home
		String ssid = wifiManager.getConnectionInfo().getSSID();
		if (ssid.equals("\"" + network + "\"")) {
			log("aborting");
			return;
		}

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

		// turn wi-fi on
		wifiManager.setWifiEnabled(true);

		// set up the ability to set data connection
		// since we're not really allowed to use this, we've got to use reflection to dig it out
		try {
			ConnectivityManager conman = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
			Class<?> conmanClass = Class.forName(conman.getClass().getName());

			Field iConnectivityManagerField = conmanClass.getDeclaredField("mService");
			iConnectivityManagerField.setAccessible(true);
			iConnectivityManager = iConnectivityManagerField.get(conman);
			Class<?> iConnectivityManagerClass = Class.forName(iConnectivityManager.getClass().getName());

			getMobileDataEnabledMethod = iConnectivityManagerClass.getDeclaredMethod("getMobileDataEnabled");
			getMobileDataEnabledMethod.setAccessible(true);

			setMobileDataEnabledMethod = iConnectivityManagerClass.getDeclaredMethod("setMobileDataEnabled", Boolean.TYPE);
			setMobileDataEnabledMethod.setAccessible(true);
		} catch (Exception e) {
			turnoff_data = false;
		}

		// this runs when scan results are available, or the notification gets clicked
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				// log("onReceive");
				String action = intent.getAction();
				if (action.equals(notificationAction)) {
					// exit when notification clicked
					log("exit when notification clicked");
					done = true;
				}
				if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
					// log("scan results available");
					for (ScanResult i : wifiManager.getScanResults()) {
						if (i.SSID.equals(network)) {
							log("network found");
							// tell the Raspberry Pi to open the door
							doTask(new OpenDoor());
							break;
						}
					}
					if (!done) {
						sleep(500);
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

		// connect to Raspberry Pi over mobile data
		doTask(new Connect());

		log("start loop");
		// busy work
		int ctr = 0;
		while (!done) {
			if (ctr++ > 20) {
				doTask(new Ping());
				ctr = 0;
			}
			sleep(500);
		}

		// turn off things according to user's wishes
		if (turnoff_data) {
			setDataEnabled(false);
		}
		if (turnoff_wifi) {
			wifiManager.setWifiEnabled(false);
		}
		log("service done");
	}

	// we do this a lot...
	public void doTask(AsyncTask<Void, String, Integer> task) {
		try {
			task.execute();
			task.get();
		} catch (Exception e) {
			log("doTask Exception: " + Log.getStackTraceString(e));
		}
	}

	// this will keep multiple startups from being enqueued
	@Override
	public void onStart(Intent intent, int startId) {
		log("onStart");
		if (!done) {
			log("onStart dropped");
			return;
		}
		super.onStart(intent, startId);
	}

	@Override
	public void onDestroy() {
		log("onDestroy");
		if (broadcastReceiver != null) {
			unregisterReceiver(broadcastReceiver);
		}

		if (wifiLock != null && wifiLock.isHeld()) {
			wifiLock.release();
		}

		// close logging file handlers to get rid of "lck" turdlets
		for (Handler h : logger.getHandlers()) {
			h.close();
		}

		// must be done as the very last piece of code
		if (cpuLock != null && cpuLock.isHeld()) {
			cpuLock.release();
		}

		super.onDestroy();
	}

	// sleep w/o the stupid useless exception crap
	public void sleep(long time) {
		try {
			Thread.sleep(time);
		} catch (InterruptedException e) {
		}
	}

	// SSL connection takes a very long time (3 or 4 seconds) so we do it at startup
	class Connect extends AsyncTask<Void, String, Integer> {
		@Override
		protected Integer doInBackground(Void... params) {
			boolean connected = false;
			log("Connect doInBackground");

			// turn on cell data if necessary
			if (!getDataEnabled()) {
				setDataEnabled(true);
				sleep(2 * 1000);
			}
			while (!connected && !done) {
				try {
					log("connecting");
					sock = (SSLSocket) sslSocketFactory.createSocket(host, port);
					sock.setSoTimeout(2000);
					buffRdr = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ASCII"));
					connected = buffRdr.readLine().equals("GARAGEDOOR");
				} catch (Exception e) {
					// we will normally have a couple exceptions before the network comes completely up
					sleep(2 * 1000);
				}
			}
			log("Connect done");
			return 0;
		}
	}

	// bang out the command to open the door
	class OpenDoor extends AsyncTask<Void, String, Integer> {
		@Override
		protected Integer doInBackground(Void... params) {
			log("OpenDoor doInBackground");
			String cmd = "OPEN\n";
			String s;

			try {
				log("opening");
				sock.getOutputStream().write(cmd.getBytes());
				// ignore extraneous ping responses
				while ((s = buffRdr.readLine()).equals("PONG"))
					log("pong");
				done = s.equals("DONE");
				if (!done) {
					log("got: " + s);
				}
			} catch (Exception e) {
				log("OpenDoor Exception: " + Log.getStackTraceString(e));
			}
			log("OpenDoor done: " + done);
			return 0;
		}
	}

	// keep the data connection awake
	class Ping extends AsyncTask<Void, String, Integer> {
		@Override
		protected Integer doInBackground(Void... params) {
			boolean connected = false;
			log("Ping doInBackground");
			String cmd = "PING\n";

			while (!connected && !done) {
				try {
					log("pinging");
					sock.getOutputStream().write(cmd.getBytes());
					buffRdr.readLine();
					connected = true;
				} catch (SocketTimeoutException e) {
					log("Ping timeout");
				} catch (Exception e) {
					log("Ping Exception: " + Log.getStackTraceString(e));
					sleep(500);
					try {
						sock.close();
					} catch (IOException e1) {
					}
					doTask(new Connect());
				}
			}
			log("Ping done");
			return 0;
		}
	}

	// test mobile data connection
	public boolean getDataEnabled() {
		log("getDataEnabled");
		try {
			return (Boolean) getMobileDataEnabledMethod.invoke(iConnectivityManager);
		} catch (Exception e) {
		}
		return false;
	}

	// bring mobile data connection up or down
	public void setDataEnabled(boolean value) {
		log("setDataEnabled");
		try {
			setMobileDataEnabledMethod.invoke(iConnectivityManager, value);
		} catch (Exception e) {
		}
	}

	// log to our own file so that messages don't get lost
	public void log(String msg) {
		Log.i("garagedoorservice", msg);
		if (logger != null) {
			logger.info(msg);
		}
	}
}
