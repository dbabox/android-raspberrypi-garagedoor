package org.genecash.garagedoor;

/*
 * General logic:
 * 
 * User is out of range of home wi-fi, and starts program
 * 
 * We acquire locks on the wi-fi to keep it running, and on the cpu to keep us running
 * 
 * We register a callback to handle connecting to a network, and the results of network scans
 * 
 * We kick off a network scan
 * 
 * When the scan finishes, our callback waits a short while and kicks off another network scan
 * 
 * If the scan results in finding and connecting to a network, we start network service discovery to find the "garagedoor" service
 * advertised by the Raspberry Pi. We stop the network scanning.
 * 
 * If we do find the service we want, then we try to resolve it to an IP address and port
 * 
 * If the resolution is successful, we connect to it, send the command to open the door, and look for the result. We stop the network
 * scanning.
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
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

public class GarageDoor extends Activity {
	// this is the service that the Raspberry Pi advertises
	private String zeroconfService = "_garagedoor._tcp";

	private BroadcastReceiver broadcastReceiver;
	private GarageDoor parent;
	private NsdManager mNsdManager;
	private WifiManager mWifiManager;
	private DiscoveryListener mDiscoveryListener;
	private ResolveListener mResolveListener;
	private WifiLock wifiLock;
	private WakeLock cpuLock;
	private int port;
	private InetAddress host;
	private boolean opened = false;
	private boolean silence = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		parent = this;
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		parent.message("onCreate");

		mNsdManager = (NsdManager) this.getSystemService(Context.NSD_SERVICE);
		mWifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);

		// this runs when the wi-fi connection state changes or scan results are available
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				parent.message("onReceive");
				if (silence) {
					parent.message("silent");
					return;
				}
				String action = intent.getAction();
				if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
					NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
					if (info.isConnected()) {
						parent.message("isConnected");
						// new connection
						if (info.getType() == ConnectivityManager.TYPE_WIFI) {
							parent.message("TYPE_WIFI");
							// must shut down or NSD doesn't work
							silence = true;
							// look for our host
							mNsdManager.discoverServices(zeroconfService, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
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
					parent.message("startScan");
					// kick off another scan
					mWifiManager.startScan();
				}
			}
		};

		// resolve IP address/port when we find the service we're looking for
		mResolveListener = new NsdManager.ResolveListener() {
			@Override
			public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
				parent.message("resolving: " + serviceInfo);
				parent.message("resolution failed - error code: " + errorCode);
			}

			@Override
			public void onServiceResolved(NsdServiceInfo serviceInfo) {
				parent.message("onServiceResolved");
				// now we actually know who to ask to open the door
				host = serviceInfo.getHost();
				port = serviceInfo.getPort();
				new OpenDoor().execute();
				// parent.finish();
			}
		};

		// this handles avahi/rendezvous/zeroconf/bonjour/nsd/whatever-the-fuck-its-called-today to find network services
		mDiscoveryListener = new NsdManager.DiscoveryListener() {
			@Override
			public void onDiscoveryStarted(String arg0) {
				parent.message("onDiscoveryStarted");
			}

			@Override
			public void onDiscoveryStopped(String serviceType) {
				parent.message("onDiscoveryStopped");
			}

			@Override
			public void onServiceFound(NsdServiceInfo serviceInfo) {
				parent.message("onServiceFound");
				// we found our service, now we need to resolve it to an IP address & port
				mNsdManager.resolveService(serviceInfo, mResolveListener);
			}

			@Override
			public void onServiceLost(NsdServiceInfo serviceInfo) {
				parent.message("onServiceLost");
			}

			@Override
			public void onStartDiscoveryFailed(String serviceType, int errorCode) {
				parent.message("discovery failed - error code: " + errorCode);
			}

			@Override
			public void onStopDiscoveryFailed(String serviceType, int errorCode) {
				parent.message("discovery failed - error code: " + errorCode);
			}
		};

		parent.message("Starting");

		// acquire a wi-fi lock that allows for scan event callbacks to happen
		wifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GarageWifiLock");
		wifiLock.acquire();
		parent.message("wifiLock acquired");

		// acquire cpu lock so we stay awake to do work
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GarageCPULock");
		cpuLock.acquire();
		parent.message("cpuLock acquired");

		// register for wi-fi network state changes
		registerReceiver(broadcastReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));

		// register for wi-fi network scan results
		registerReceiver(broadcastReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

		// set up exit button
		findViewById(R.id.exit).setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				finish();
			}
		});

		// start the network scans
		mWifiManager.startScan();
	}

	// request the garage door opening
	class OpenDoor extends AsyncTask<Void, String, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			String cmd = "OPEN\n";
			if (!opened) {
				try {
					publishProgress("TILT");
					Socket sock = new Socket(host, port);
					sock.getOutputStream().write(cmd.getBytes());
					BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF8"));
					opened = br.readLine().equals("DONE");
					sock.close();
					cleanShutdown();
				} catch (Exception e) {
					publishProgress(e.getMessage());
				}
			}
			return null;
		}

		// display exception message
		@Override
		protected void onProgressUpdate(String... values) {
			parent.message(values[0]);
		}
	}

	void cleanShutdown() {
		if (broadcastReceiver != null) {
			unregisterReceiver(broadcastReceiver);
			broadcastReceiver = null;
		}
		if (mDiscoveryListener != null) {
			mNsdManager.stopServiceDiscovery(mDiscoveryListener);
			mDiscoveryListener = null;
		}
	}

	@SuppressLint("Wakelock")
	@Override
	protected void onDestroy() {
		parent.message("onDestroy");
		if (cpuLock.isHeld()) {
			cpuLock.release();
		}
		if (wifiLock.isHeld()) {
			wifiLock.release();
		}
		cleanShutdown();
		super.onDestroy();
	};

	// receiver & listener stuff happens in a different thread from the UI
	public void message(final String msg) {
		final TextView tv = (TextView) findViewById(R.id.msg);
		tv.post(new Runnable() {
			public void run() {
				tv.setText(tv.getText() + msg + "\n");
			}
		});
	}
}
