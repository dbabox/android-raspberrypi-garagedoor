package org.genecash.garagedoor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import net.sbbi.upnp.impls.InternetGatewayDevice;
import net.sbbi.upnp.messages.UPNPResponseException;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdManager.DiscoveryListener;
import android.net.nsd.NsdManager.ResolveListener;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
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
	static final String CERT_FILE = "client.p12";

	// setting preferences
	static final String PREFS_NAME = "GarageDoorPrefs";
	static final String PREFS_LOCAL_IP = "Local_IP";
	static final String PREFS_LOCAL_PORT = "Local_Port";
	static final String PREFS_EXT_IP = "Ext_IP";
	static final String PREFS_EXT_PORT = "Ext_Port";
	static final String PREFS_NETWORK = "Network_Name";
	static final String PREFS_DATA = "Manage_Data";
	static final String PREFS_WIFI = "Manage_WiFi";

	// network service resolution
	static final String SERVICE_TYPE = "_garagedoor._tcp";
	protected static final String TAG = "GARAGEDOOR";

	static final String SSL_COMMON_NAME = "Garage Door Opener";

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
	private CheckBox cbWifi;
	private ListView lvServiceList;
	private ListView lvRoutersList;
	private ArrayAdapter<PrintableService> adapterServices;
	private ArrayAdapter<PrintableRouter> adapterRouters;

	private List<PrintableService> listServices = new ArrayList<PrintableService>();
	private List<PrintableRouter> listRouters = new ArrayList<PrintableRouter>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		log("onCreate");
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
				Toast.makeText(getApplicationContext(), "StartDiscovery failed. Error: " + errorCode, Toast.LENGTH_LONG).show();
				nsdManager.stopServiceDiscovery(this);
			}

			@Override
			public void onStopDiscoveryFailed(String serviceType, int errorCode) {
				Log.i(TAG, "StopDiscovery failed. Error: " + errorCode);
				Toast.makeText(getApplicationContext(), "StopDiscovery failed. Error: " + errorCode, Toast.LENGTH_LONG).show();
				nsdManager.stopServiceDiscovery(this);
			}
		};

		resolveListener = new NsdManager.ResolveListener() {
			@Override
			public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
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
		cbData = (CheckBox) findViewById(R.id.check_data);
		cbWifi = (CheckBox) findViewById(R.id.check_wifi);

		// populate fields from current settings
		edHost.setText(sSettings.getString(PREFS_LOCAL_IP, ""));
		edHostPort.setText("" + sSettings.getInt(PREFS_LOCAL_PORT, 0));
		edExtIP.setText(sSettings.getString(PREFS_EXT_IP, ""));
		edExtIPPort.setText("" + sSettings.getInt(PREFS_EXT_PORT, 0));
		edNetwork.setText(sSettings.getString(PREFS_NETWORK, ""));
		cbData.setChecked(sSettings.getBoolean(PREFS_DATA, true));
		cbWifi.setChecked(sSettings.getBoolean(PREFS_WIFI, true));

		// set up list of services found
		lvServiceList = (ListView) findViewById(R.id.list_svcs);
		adapterServices = new ArrayAdapter<PrintableService>(this, android.R.layout.simple_list_item_1, listServices);
		lvServiceList.setAdapter(adapterServices);

		// set up list of routers found
		listRouters.add(new PrintableRouter(null, "Scanning for routers..."));
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
				editor.putBoolean(PREFS_WIFI, cbWifi.isChecked());
				try {
					editor.putInt(PREFS_LOCAL_PORT, Integer.parseInt(edHostPort.getText().toString()));
					editor.putInt(PREFS_EXT_PORT, Integer.parseInt(edExtIPPort.getText().toString()));
				} catch (NumberFormatException e) {
					Toast.makeText(getApplicationContext(), "Ports must be numeric", Toast.LENGTH_LONG).show();
					return;
				}
				editor.commit();
				Toast.makeText(getApplicationContext(), "Settings saved!", Toast.LENGTH_LONG).show();
			}
		});

		// "fetch certificate" button.
		findViewById(R.id.fetch).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				fetchCert();
			}
		});

		// "view log" button.
		findViewById(R.id.log).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				Intent i = new Intent(getApplicationContext(), ViewLog.class);
				startActivity(i);
			}
		});

		log("onCreate setup done");

		// populate list of routers
		new GetExternalIP().execute();

		// populate list of services
		discoverServices();

		log("onCreate done");
	}

	@Override
	protected void onRestart() {
		log("onRestart");
		super.onRestart();
		discoverServices();
		log("onRestart done");
	}

	@Override
	protected void onStop() {
		log("onStop");
		super.onStop();
		try {
			nsdManager.stopServiceDiscovery(discoveryListener);
		} catch (Exception e) {
		}
		log("onStop done");
	}

	// fetch certificate from external storage (sdcard) and move it to protected directory
	void fetchCert() {
		log("fetchCert");
		FileChannel src = null;
		FileChannel dst = null;
		File f = new File(Environment.getExternalStorageDirectory(), CERT_FILE);

		// open source if we can
		try {
			src = new FileInputStream(f).getChannel();
		} catch (FileNotFoundException e) {
			Toast.makeText(this, "Opening certificate file failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
			return;
		}

		// copy & delete file
		try {
			dst = openFileOutput(CERT_FILE, Context.MODE_PRIVATE).getChannel();
			dst.transferFrom(src, 0, src.size());
			src.close();
			dst.close();
			f.delete();
			Toast.makeText(this, "Certificate fetched!", Toast.LENGTH_LONG).show();
		} catch (Exception e) {
			e.printStackTrace();
		}
		log("fetchCert done");
	}

	// we have to bang on this door multiple times
	void discoverServices() {
		log("discoverServices");
		if (adapterServices.getCount() == 0) {
			nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
		}
		log("discoverServices done");
	}

	// try to discover external IP addresses of routers via UPnP
	// requires upnplib-mobile.jar from http://sourceforge.net/projects/upnplibmobile/
	// thanks to "suggam" for this, and to "sbbi" for the original UPNPLib project, whoever they are!
	class GetExternalIP extends AsyncTask<Void, String, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			try {
				InternetGatewayDevice[] IGDs = InternetGatewayDevice.getDevices(5000);
				if (IGDs != null) {
					for (InternetGatewayDevice igd : IGDs) {
						publishProgress(igd.getIGDRootDevice().getModelDescription(), igd.getExternalIPAddress());
					}
				} else {
					publishProgress(null, "Unable to find a router on your network\nUPnP must be enabled on your router");
				}
			} catch (IOException e) {
				publishProgress(null, "UPnP error: " + e.getMessage());
			} catch (UPNPResponseException respEx) {
				publishProgress(null, "UPnP error: " + respEx.getDetailErrorCode() + "\n" + respEx.getDetailErrorDescription());
			}
			return null;
		}

		// display exception message
		@Override
		protected void onProgressUpdate(String... values) {
			if (listRouters.get(0).name == null) {
				listRouters.remove(0);
			}
			listRouters.add(new PrintableRouter(values[0], values[1]));
			adapterRouters.notifyDataSetChanged();
			discoverServices();
		}
	}

	// log to our own file so that messages don't get lost
	public void log(String msg) {
		Log.i("garagedoorsettings", msg);
	}

}
