package org.genecash.garagedoor;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import net.sbbi.upnp.impls.InternetGatewayDevice;
import net.sbbi.upnp.messages.UPNPResponseException;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdManager.DiscoveryListener;
import android.net.nsd.NsdManager.ResolveListener;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class GarageSettings extends Activity {
	// setting preferences
	static final String PREFS_NAME = "GarageDoorPrefs";
	static final String PREFS_LOCAL_IP = "Local_IP";
	static final String PREFS_LOCAL_PORT = "Local_Port";
	static final String PREFS_EXT_IP = "Ext_IP";
	static final String PREFS_EXT_PORT = "Ext_Port";
	static final String PREFS_NETWORK = "Network_Name";
	static final String PREFS_DATA = "Manage_Data";

	// network service resolution
	static final String SERVICE_TYPE = "_garagedoor._tcp";
	protected static final String TAG = "GARAGEDOOR";

	private NsdManager nsdManager;
	private ResolveListener resolveListener;
	private DiscoveryListener discoveryListener;

	// display widgets
	private EditText edHost;
	private EditText edHostPort;
	private EditText edExtIP;
	private EditText edExtIPPort;
	private EditText edNetwork;
	private TextView tvWifiNetwork;
	private CheckBox cbData;
	private ListView lvServiceList;
	private ListView lvRoutersList;
	private ArrayAdapter<PrintableService> adapterServices;
	private ArrayAdapter<PrintableRouter> adapterRouters;

	private Context ctx = this;
	private List<PrintableService> listServices = new ArrayList<PrintableService>();
	private List<PrintableRouter> listRouters = new ArrayList<PrintableRouter>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings);

		SharedPreferences sSettings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

		nsdManager = (NsdManager) this.getSystemService(Context.NSD_SERVICE);

		discoveryListener = new NsdManager.DiscoveryListener() {
			@Override
			public void onDiscoveryStarted(String serviceType) {
			}

			@Override
			public void onDiscoveryStopped(String serviceType) {
			}

			@Override
			public void onServiceFound(NsdServiceInfo serviceInfo) {
				Log.i(TAG, "Service Found " + serviceInfo);
				nsdManager.resolveService(serviceInfo, resolveListener);
			}

			@Override
			public void onServiceLost(NsdServiceInfo serviceInfo) {
				Log.i(TAG, "Service Lost " + serviceInfo);
				final String name = serviceInfo.getServiceName();

				// remove button in a thread-safe manner
				lvServiceList.post(new Runnable() {
					@Override
					public void run() {
						for (PrintableService i : listServices) {
							if (i.name.equals(name))
								listServices.remove(i);
						}
						adapterServices.notifyDataSetChanged();
					}
				});
			}

			@Override
			public void onStartDiscoveryFailed(String serviceType, int errorCode) {
				Log.i(TAG, "StartDiscovery failed. Error: " + errorCode);
				Toast.makeText(ctx, "StartDiscovery failed. Error: " + errorCode, Toast.LENGTH_LONG).show();
				nsdManager.stopServiceDiscovery(this);
			}

			@Override
			public void onStopDiscoveryFailed(String serviceType, int errorCode) {
				Log.i(TAG, "StopDiscovery failed. Error: " + errorCode);
				Toast.makeText(ctx, "StopDiscovery failed. Error: " + errorCode, Toast.LENGTH_LONG).show();
				nsdManager.stopServiceDiscovery(this);
			}
		};

		resolveListener = new NsdManager.ResolveListener() {
			@Override
			public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
				Log.i(TAG, "Resolve failed Error: " + errorCode + " - " + serviceInfo);
				Toast.makeText(ctx, "Resolve failed. Error: " + errorCode + " - " + serviceInfo, Toast.LENGTH_LONG).show();
			}

			@Override
			public void onServiceResolved(NsdServiceInfo serviceInfo) {
				final String type = serviceInfo.getServiceType();
				final String name = serviceInfo.getServiceName();
				final InetAddress host = serviceInfo.getHost();
				final int port = serviceInfo.getPort();
				Log.i(TAG, "Service resolved: " + type + "-" + host + "-" + port);

				// create a button in a thread-safe manner
				lvServiceList.post(new Runnable() {
					@Override
					public void run() {
						listServices.add(new PrintableService(name, host, port));
						adapterServices.notifyDataSetChanged();
					}
				});
			}
		};

		// find fields
		edHost = (EditText) findViewById(R.id.host);
		edHostPort = (EditText) findViewById(R.id.host_port);
		edExtIP = (EditText) findViewById(R.id.external_ip);
		edExtIPPort = (EditText) findViewById(R.id.external_ip_port);
		edNetwork = (EditText) findViewById(R.id.network);
		cbData = (CheckBox) findViewById(R.id.check);

		// populate fields from current settings
		edHost.setText(sSettings.getString(PREFS_LOCAL_IP, ""));
		edHostPort.setText("" + sSettings.getInt(PREFS_LOCAL_PORT, 0));
		edExtIP.setText(sSettings.getString(PREFS_EXT_IP, ""));
		edExtIPPort.setText("" + sSettings.getInt(PREFS_EXT_PORT, 0));
		edNetwork.setText(sSettings.getString(PREFS_NETWORK, ""));
		cbData.setChecked(sSettings.getBoolean(PREFS_DATA, true));

		// set up list of services found
		lvServiceList = (ListView) findViewById(R.id.list_svcs);
		adapterServices = new ArrayAdapter<PrintableService>(this, android.R.layout.simple_list_item_1, listServices);
		lvServiceList.setAdapter(adapterServices);

		// set up list of routers found
		lvRoutersList = (ListView) findViewById(R.id.list_routers);
		adapterRouters = new ArrayAdapter<PrintableRouter>(this, android.R.layout.simple_list_item_1, listRouters);
		lvRoutersList.setAdapter(adapterRouters);

		// find network name
		WifiManager wifiMgr = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
		WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
		String name = wifiInfo.getSSID();
		// strip any surrounding quotes
		name = name.replaceAll("^\"|\"$", "");
		tvWifiNetwork = (TextView) findViewById(R.id.wifi_network);
		tvWifiNetwork.setText(name);

		// populate fields from the item that the user selected
		lvServiceList.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				PrintableService s = listServices.get(position);
				edHost.setText(s.host);
				edHostPort.setText(s.port);
			}
		});
		lvRoutersList.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				PrintableRouter r = listRouters.get(position);
				edExtIP.setText(r.ip);
			}
		});
		tvWifiNetwork.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				edNetwork.setText(tvWifiNetwork.getText());
			}
		});

		// "save" button.
		findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, 0).edit();
				editor.putString(PREFS_NETWORK, edNetwork.getText().toString().trim());
				editor.putString(PREFS_LOCAL_IP, edHost.getText().toString().trim());
				editor.putString(PREFS_EXT_IP, edExtIP.getText().toString().trim());
				editor.putBoolean(PREFS_DATA, cbData.isChecked());
				try {
					editor.putInt(PREFS_LOCAL_PORT, Integer.parseInt(edHostPort.getText().toString()));
					editor.putInt(PREFS_EXT_PORT, Integer.parseInt(edExtIPPort.getText().toString()));
				} catch (NumberFormatException e) {
					Toast.makeText(ctx, "Ports must be numeric", Toast.LENGTH_LONG).show();
					return;
				}
				editor.commit();
				finish();
			}
		});

		// "cancel" button.
		findViewById(R.id.cancel).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				finish();
			}
		});
	}

	@Override
	protected void onResume() {
		// populate list of services
		nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
		// populate list of routers
		new GetExternalIP().execute();
		super.onResume();
	}

	@Override
	protected void onPause() {
		try {
			nsdManager.stopServiceDiscovery(discoveryListener);
		} catch (Exception e) {
		}
		super.onPause();
	}

	// try to discover external IP addresses of routers via UPnP
	// requires upnplib-mobile.jar from http://sourceforge.net/projects/upnplibmobile/
	class GetExternalIP extends AsyncTask<Void, String, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			publishProgress("msg", "Scanning routers");
			try {
				InternetGatewayDevice[] IGDs = InternetGatewayDevice.getDevices(5000);
				if (IGDs != null) {
					for (InternetGatewayDevice igd : IGDs) {
						publishProgress("data", igd.getIGDRootDevice().getModelDescription(), igd.getExternalIPAddress());
					}
				} else {
					publishProgress("msg", "Unable to find router on your network\nIs UPnP disabled on your router?");
				}
			} catch (IOException e) {
				publishProgress("msg", "UPnP error: " + e.getMessage());
			} catch (UPNPResponseException respEx) {
				publishProgress("msg", "UPNP error: " + respEx.getDetailErrorCode() + " " + respEx.getDetailErrorDescription());
			}
			publishProgress("msg", "Router scan complete");
			return null;
		}

		// display exception message
		@Override
		protected void onProgressUpdate(String... values) {
			if (values[0].equals("msg")) {
				Toast.makeText(ctx, values[1], Toast.LENGTH_LONG).show();
			}
			if (values[0].equals("data")) {
				listRouters.add(new PrintableRouter(values[1], values[2]));
				adapterRouters.notifyDataSetChanged();
			}
		}
	}
}
