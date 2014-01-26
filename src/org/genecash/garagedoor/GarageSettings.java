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
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class GarageSettings extends Activity {
	// setting preferences
	static final String PREFS_NAME = "GarageDoorPrefs";
	static final String PREFS_HOST = "Host";
	static final String PREFS_PORT = "Port";
	static final String PREFS_EXT_IP = "Ext_IP";

	// network service resolution
	static final String SERVICE_TYPE = "_garagedoor._tcp";
	protected static final String TAG = "GARAGEDOOR";

	private NsdManager nsdManager;
	private ResolveListener resolveListener;
	private DiscoveryListener discoveryListener;

	// display widgets
	private TextView tvHost;
	private TextView tvPort;
	private TextView tvExtIP;
	private ListView lvServiceList;
	private ArrayAdapter<Service> adapterServices;

	private Context ctx = this;
	private List<Service> listServices = new ArrayList<Service>();

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
				final String type = serviceInfo.getServiceType();
				final String name = serviceInfo.getServiceName();

				// remove button in a thread-safe manner
				lvServiceList.post(new Runnable() {
					@Override
					public void run() {
						for (Service i : listServices) {
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
				nsdManager.stopServiceDiscovery(this);
			}

			@Override
			public void onStopDiscoveryFailed(String serviceType, int errorCode) {
				Log.i(TAG, "StopDiscovery failed. Error: " + errorCode);
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
						listServices.add(new Service(name, host, port));
						adapterServices.notifyDataSetChanged();
					}
				});
			}
		};

		// find fields
		tvHost = (TextView) findViewById(R.id.host);
		tvPort = (TextView) findViewById(R.id.port);
		tvExtIP = (TextView) findViewById(R.id.external);

		// populate fields from current settings
		tvHost.setText(sSettings.getString(PREFS_HOST, ""));
		tvPort.setText("" + sSettings.getInt(PREFS_PORT, 0));
		tvExtIP.setText(sSettings.getString(PREFS_EXT_IP, ""));

		// set up list of services found
		lvServiceList = (ListView) findViewById(R.id.list);
		adapterServices = new ArrayAdapter<Service>(this, android.R.layout.simple_list_item_1, listServices);
		lvServiceList.setAdapter(adapterServices);

		// populate fields from the item that the user selected
		lvServiceList.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				Service s = listServices.get(position);
				tvHost.setText(s.host);
				tvPort.setText(s.port);
			}
		});

		// "fetch" button.
		findViewById(R.id.fetch).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				new GetExternalIP().execute();
			}
		});

		// "save" button.
		findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, 0).edit();
				editor.putString(PREFS_HOST, tvHost.getText().toString().trim());
				editor.putString(PREFS_EXT_IP, tvExtIP.getText().toString().trim());
				try {
					editor.putInt(PREFS_PORT, Integer.parseInt(tvPort.getText().toString()));
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
		nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
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

	// try to discover external IP of router via uPnP
	// requires upnplib-mobile.jar from http://sourceforge.net/projects/upnplibmobile/
	class GetExternalIP extends AsyncTask<Void, String, Void> {
		private String addr = null;

		@Override
		protected Void doInBackground(Void... params) {
			publishProgress("Fetching external IP address from Internet Gateway Device");
			try {
				InternetGatewayDevice[] IGDs = InternetGatewayDevice.getDevices(5000);
				if (IGDs != null) {
					for (InternetGatewayDevice igd : IGDs) {
						publishProgress("Found Internet Gateway Device:\n" + igd.getIGDRootDevice().getModelDescription());
						addr = igd.getExternalIPAddress();
						break;
					}
				} else {
					publishProgress("Unable to find Internet Gateway Device on your network\nIs UPnP disabled on your router?");
				}
			} catch (IOException e) {
				publishProgress("UPnP error: " + e.getMessage());
			} catch (UPNPResponseException respEx) {
				publishProgress("UPNP error: " + respEx.getDetailErrorCode() + " " + respEx.getDetailErrorDescription());
			}
			return null;
		}

		// display exception message
		@Override
		protected void onProgressUpdate(String... values) {
			Toast.makeText(ctx, values[0], Toast.LENGTH_LONG).show();
		}

		@Override
		protected void onPostExecute(Void result) {
			if (addr != null) {
				tvExtIP.setText(addr);
				Toast.makeText(ctx, "Fetched external IP address", Toast.LENGTH_LONG).show();
			}
			super.onPostExecute(result);
		}
	}

}
