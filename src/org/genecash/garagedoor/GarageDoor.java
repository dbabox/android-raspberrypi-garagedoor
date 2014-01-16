package org.genecash.garagedoor;

import java.net.InetAddress;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdManager.DiscoveryListener;
import android.net.nsd.NsdManager.ResolveListener;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;
import android.widget.TextView;

public class GarageDoor extends Activity {
	private String networkName = "Kill All Atheists";
	private String zeroconfService = "_ssh._tcp";
	private String target = "raspberrypi";

	private BroadcastReceiver broadcastReceiver;
	private GarageDoor parent;
	private NsdManager mNsdManager;
	private DiscoveryListener mDiscoveryListener;
	private ResolveListener mResolveListener;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		parent = this;
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		// register for wi-fi network state changes
		IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

		mNsdManager = (NsdManager) this.getSystemService(Context.NSD_SERVICE);

		// this runs when the wi-fi connection state changes
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				final String action = intent.getAction();
				parent.message("onReceive");
				if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
					LinearLayout bg = (LinearLayout) parent.findViewById(R.id.background);
					TextView fg = (TextView) parent.findViewById(R.id.msg);
					NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
					if (info.isConnected()) {
						// new connection
						parent.message("new connection");
						if (info.getType() == ConnectivityManager.TYPE_WIFI) {
							parent.message("wi-fi");
							// check wi-fi network name
							parent.message(info.getExtraInfo());
							if (info.getExtraInfo().equals("\"" + networkName + "\"")) {
								// this is our home network
								bg.setBackgroundColor(Color.WHITE);
								fg.setTextColor(Color.BLACK);
								// look for our host
								mNsdManager.discoverServices(zeroconfService, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
							}
						}
					} else {
						// wifi connection was lost
						parent.message("connection lost");
						bg.setBackgroundColor(Color.BLACK);
						fg.setTextColor(Color.WHITE);
					}
					parent.message("");
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
				parent.message("resolving: " + serviceInfo);
				int port = serviceInfo.getPort();
				InetAddress host = serviceInfo.getHost();
				parent.message("resolved: " + host + " " + port);
			}
		};

		// this handles avahi/rendezvous/zeroconf/bonjour/nsd/whatever-the-fuck-its-called-today to find network services
		mDiscoveryListener = new NsdManager.DiscoveryListener() {
			@Override
			public void onDiscoveryStarted(String arg0) {
				parent.message("discovery started: " + arg0);
			}

			@Override
			public void onDiscoveryStopped(String serviceType) {
				parent.message("service stopped: " + serviceType);
			}

			@Override
			public void onServiceFound(NsdServiceInfo serviceInfo) {
				parent.message("service found: " + serviceInfo);
				if (serviceInfo.getServiceName().equals(target)) {
					mNsdManager.resolveService(serviceInfo, mResolveListener);
				}
			}

			@Override
			public void onServiceLost(NsdServiceInfo serviceInfo) {
				parent.message("service lost: " + serviceInfo);
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

		// Kick off!!! Touch your battlefoot to the Pain Ellipsoid and let the cycle of violence begin anew!!!!!!
		registerReceiver(broadcastReceiver, intentFilter);
		message("started");

		// set up exit button
		findViewById(R.id.exit).setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				finish();
			}
		});
	}

	@Override
	// clean shutdown
	protected void onDestroy() {
		unregisterReceiver(broadcastReceiver);
		mNsdManager.stopServiceDiscovery(mDiscoveryListener);
		super.onDestroy();
	};

	// BroadcastReceiver stuff happens in a different thread from the UI
	public void message(final String msg) {
		final TextView tv = (TextView) findViewById(R.id.msg);
		tv.post(new Runnable() {
			public void run() {
				tv.setText(tv.getText() + msg + "\n");
			}
		});
	}
}
