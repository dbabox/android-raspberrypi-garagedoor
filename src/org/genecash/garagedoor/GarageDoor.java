package org.genecash.garagedoor;

import java.net.InetAddress;
import java.net.Socket;

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
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

public class GarageDoor extends Activity {
	private String zeroconfService = "_garagedoor._tcp";

	private BroadcastReceiver broadcastReceiver;
	private GarageDoor parent;
	private NsdManager mNsdManager;
	private DiscoveryListener mDiscoveryListener;
	private ResolveListener mResolveListener;
	private int port;
	private InetAddress host;

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
				if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
					NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
					if (info.isConnected()) {
						// new connection
						if (info.getType() == ConnectivityManager.TYPE_WIFI) {
							// look for our host
							mNsdManager.discoverServices(zeroconfService, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
						}
					}
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
				// now we actually know who to ask to open the door
				port = serviceInfo.getPort();
				host = serviceInfo.getHost();
				new OpenDoor().execute();
				parent.finish();
			}
		};

		// this handles avahi/rendezvous/zeroconf/bonjour/nsd/whatever-the-fuck-its-called-today to find network services
		mDiscoveryListener = new NsdManager.DiscoveryListener() {
			@Override
			public void onDiscoveryStarted(String arg0) {
			}

			@Override
			public void onDiscoveryStopped(String serviceType) {
			}

			@Override
			public void onServiceFound(NsdServiceInfo serviceInfo) {
				// we found our service, now we need to resolve it to an IP address & port
				mNsdManager.resolveService(serviceInfo, mResolveListener);
			}

			@Override
			public void onServiceLost(NsdServiceInfo serviceInfo) {
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

		// set up exit button
		findViewById(R.id.exit).setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				finish();
			}
		});
	}

	// request the garage door opening
	class OpenDoor extends AsyncTask<Void, String, Integer> {
		@Override
		protected Integer doInBackground(Void... params) {
			String line1 = "OPEN\n";

			try {
				Socket sock = new Socket(host, port);
				sock.getOutputStream().write(line1.getBytes());
				sock.close();
			} catch (Exception e) {
				publishProgress(e.getMessage());
				return 1;
			}

			return 0;
		}

		// display exception message
		@Override
		protected void onProgressUpdate(String... values) {
			parent.message(values[0]);
		}
	}

	@Override
	// clean shutdown
	protected void onDestroy() {
		unregisterReceiver(broadcastReceiver);
		mNsdManager.stopServiceDiscovery(mDiscoveryListener);
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
