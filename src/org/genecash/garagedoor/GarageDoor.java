package org.genecash.garagedoor;

/*
 * General logic:
 * 
 * We acquire locks on the wi-fi to keep it running, and on the cpu to keep us running.
 * 
 * We register a callback to handle connecting to a network, and the results of network scans.
 * 
 * We start network service discovery to find the "garagedoor" service advertised by the Raspberry Pi.
 * 
 * If we do find the service we want, then we try to resolve it to an IP address and port.
 * 
 * If the resolution is successful, we connect to it, send the command to open the door, and look for the result. We stop any network
 * scanning.
 * 
 * Now we need to handle scanning for our wi-fi network:
 * * If we're not connected to wi-fi, or we lose connection, we kick off a network scan.
 * * When the scan finishes, if we're still not connected, we wait a short while and kick off another network scan.
 * 
 * This handles the case where we're already on our home network and we just want to open/close the garage door. We never start
 * scanning for networks.
 * It also handles the case where we connect to another wi-fi network, but it's not the home network and doesn't have the Raspberry Pi
 * on it. We don't scan for networks until we leave that foreign network, but once we do, we start scanning.
 */

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdManager.DiscoveryListener;
import android.net.nsd.NsdManager.ResolveListener;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;

public class GarageDoor extends Activity {

	// this is the service that the Raspberry Pi advertises
	private String zeroconfService = "_garagedoor._tcp";

	public static final String TAG = "GarageDoor";

	private BroadcastReceiver broadcastReceiver;
	private GarageDoor parent;
	private NsdManager mNsdManager;
	private WifiManager mWifiManager;
	private ConnectivityManager mConnManager;
	private DiscoveryListener mDiscoveryListener;
	private ResolveListener mResolveListener;
	private WifiLock wifiLock;
	private WakeLock cpuLock;
	private NetworkInfo mWifi;
	private int port;
	private InetAddress host;
	private boolean debug = true;
	private boolean opened = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		parent = this;
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		Log.i(TAG, "onCreate");

		mNsdManager = (NsdManager) this.getSystemService(Context.NSD_SERVICE);
		mWifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
		mConnManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

		// this runs when the wi-fi connection state changes or scan results are available
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				Log.i(TAG, "onReceive");
				String action = intent.getAction();
				if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
					NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
					if (!info.isConnected()) {
						Log.i(TAG, "!isConnected");
						// new connection
						if (info.getType() == ConnectivityManager.TYPE_WIFI) {
							Log.i(TAG, "TYPE_WIFI");
							// start scanning for networks
							mWifiManager.startScan();
						}
					}
				} else if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
					// scan is done... if we don't wait for the scan results, and start another scan before the last one finishes, then
					// this receiver doesn't fire
					try {
						// wait a short while
						Thread.sleep(500);
					} catch (InterruptedException e) {
					}
					Log.i(TAG, "startScan");
					// kick off another scan if necessary
					mWifi = mConnManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
					if (!mWifi.isConnected()) {
						mWifiManager.startScan();
					}
				}
			}
		};

		// resolve IP address/port when we find the service we're looking for
		mResolveListener = new NsdManager.ResolveListener() {
			@Override
			public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
				Log.e(TAG, "resolution failed - error code: " + errorCode + " - " + serviceInfo);
			}

			@Override
			public void onServiceResolved(NsdServiceInfo serviceInfo) {
				Log.i(TAG, "onServiceResolved: " + serviceInfo);
				// now we actually know who to ask to open the door
				host = serviceInfo.getHost();
				port = serviceInfo.getPort();
				new OpenDoor().execute();
				parent.finish();
			}
		};

		// this handles avahi/rendezvous/zeroconf/bonjour/nsd/whatever-the-fuck-its-called-today to find network services
		mDiscoveryListener = new NsdManager.DiscoveryListener() {
			@Override
			public void onDiscoveryStarted(String arg0) {
				Log.i(TAG, "onDiscoveryStarted: " + arg0);
			}

			@Override
			public void onDiscoveryStopped(String serviceType) {
				Log.i(TAG, "onDiscoveryStopped: " + serviceType);
			}

			@Override
			public void onServiceFound(NsdServiceInfo serviceInfo) {
				Log.i(TAG, "onServiceFound: " + serviceInfo);
				// we found our service, now we need to resolve it to an IP address & port
				mNsdManager.resolveService(serviceInfo, mResolveListener);
			}

			@Override
			public void onServiceLost(NsdServiceInfo serviceInfo) {
				Log.i(TAG, "onServiceLost: " + serviceInfo);
			}

			@Override
			public void onStartDiscoveryFailed(String serviceType, int errorCode) {
				Log.e(TAG, "discovery failed - error code: " + errorCode + " - " + serviceType);
				mNsdManager.stopServiceDiscovery(this);
			}

			@Override
			public void onStopDiscoveryFailed(String serviceType, int errorCode) {
				Log.e(TAG, "discovery failed - error code: " + errorCode + " - " + serviceType);
				mNsdManager.stopServiceDiscovery(this);
			}
		};

		Log.i(TAG, "Starting");

		// acquire a wi-fi lock that allows for scan event callbacks to happen
		wifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GarageWifiLock");
		wifiLock.acquire();
		Log.i(TAG, "wifiLock acquired");

		// acquire cpu lock so we stay awake to do work
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GarageCPULock");
		cpuLock.acquire();
		Log.i(TAG, "cpuLock acquired");

		// register for wi-fi network scan results
		registerReceiver(broadcastReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

		// register for wi-fi network state changes
		registerReceiver(broadcastReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));

		// start looking for the "garagedoor" service
		mNsdManager.discoverServices(zeroconfService, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);

		// set up exit button
		findViewById(R.id.exit).setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				finish();
			}
		});

		// start the network scans if necessary
		mWifi = mConnManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		if (!mWifi.isConnected()) {
			mWifiManager.startScan();
		}
	}

	// request the garage door open/close
	class OpenDoor extends AsyncTask<Void, String, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			String cmd = "TOGGLE\n";
			if (!opened) {
				try {
					Log.i(TAG, "TILT");
					if (!debug) {
						Socket sock = new Socket(host, port);
						sock.setSoTimeout(1000);
						sock.getOutputStream().write(cmd.getBytes());
						BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF8"));
						opened = br.readLine().equals("DONE");
						Log.i(TAG, "opened: " + opened);
						sock.close();
					}
					cleanShutdown();
				} catch (Exception e) {
					Log.e(TAG, e.getStackTrace().toString());
				}
			}
			return null;
		}
	}

	void cleanShutdown() {
		try {
			unregisterReceiver(broadcastReceiver);
		} catch (Exception e) {
		}
		try {
			mNsdManager.stopServiceDiscovery(mDiscoveryListener);
		} catch (Exception e) {
		}
	}

	// no matter how we exit, we clean things up
	@SuppressLint("Wakelock")
	@Override
	protected void onDestroy() {
		Log.i(TAG, "onDestroy");
		if (cpuLock.isHeld()) {
			cpuLock.release();
		}
		if (wifiLock.isHeld()) {
			wifiLock.release();
		}
		cleanShutdown();
		super.onDestroy();
	};
}
