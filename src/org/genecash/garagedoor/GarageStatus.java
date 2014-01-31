package org.genecash.garagedoor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLSocket;

import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.params.BasicHttpParams;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

public class GarageStatus extends Activity {
	private static final String TAG = "GarageDoorStatus";

	private Context ctx = this;

	private GetStatus taskStatus;
	private ImageButton btn;
	private String host;
	private int port;

	private SSLSocketFactory sslSocketFactory;

	private String TRUSTSTORE_PASSWORD = "secret";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.status);

		// pull external address & port from preferences
		SharedPreferences sSettings = getSharedPreferences(GarageSettings.PREFS_NAME, MODE_PRIVATE);
		host = sSettings.getString(GarageSettings.PREFS_EXT_IP, "");
		port = sSettings.getInt(GarageSettings.PREFS_EXT_PORT, 0);

		btn = (ImageButton) findViewById(R.id.status);

		btn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				// this is how we have more than one AsyncTask running at a time
				new ToggleDoor().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null);
			}
		});

		// initialize SSL
		KeyStore localTrustStore = null;
		try {
			localTrustStore = KeyStore.getInstance("BKS");
			InputStream in = getResources().openRawResource(R.raw.mytruststore);
			localTrustStore.load(in, TRUSTSTORE_PASSWORD.toCharArray());
			if (localTrustStore.size() != 1) {
				Toast.makeText(this, "No certificiates in trust store", Toast.LENGTH_LONG).show();
			}
			sslSocketFactory = new SSLSocketFactory(localTrustStore);
			sslSocketFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
		} catch (KeyStoreException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (KeyManagementException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (UnrecoverableKeyException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (NoSuchAlgorithmException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (CertificateException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (IOException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		}

		taskStatus = new GetStatus();
		taskStatus.execute();
	}

	@Override
	protected void onPause() {
		taskStatus.cancel(false);
		finish();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
	}

	// get a string from the network in a separate thread
	class GetStatus extends AsyncTask<Void, String, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			String cmd = "STATUS\n";
			try {
				SSLSocket sock = (SSLSocket) sslSocketFactory.connectSocket(null, host, port, null, 0, new BasicHttpParams());
				sock.setSoTimeout(2000);
				BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ASCII"));
				if (br.readLine().equals("GARAGEDOOR")) {
					sock.getOutputStream().write(cmd.getBytes());
					sock.setSoTimeout(30000);
					String status = "";
					// read status changes until we exit
					while (status != null && !isCancelled()) {
						try {
							status = br.readLine();
							publishProgress("status", status);
						} catch (Exception e) {
							break;
						}
					}
				}
				sock.close();
			} catch (Exception e) {
				publishProgress("error", e.getMessage());
			}
			return null;
		}

		@Override
		protected void onProgressUpdate(String... values) {
			if (values[0].equals("error")) {
				// error message from exception
				Toast.makeText(ctx, values[1], Toast.LENGTH_LONG).show();
				Log.e(TAG, "Status exception: " + values[1]);
			}
			if (values[0].equals("status")) {
				// change button background to display status value
				String status = values[1];
				if ("TRANSIT".equals(status)) {
					btn.setImageResource(R.drawable.barberpole_gray);
				} else if ("CLOSED".equals(status)) {
					btn.setImageResource(R.drawable.solid_green);
				} else if ("OPEN".equals(status)) {
					btn.setImageResource(R.drawable.solid_red);
				} else {
					btn.setImageResource(R.drawable.barberpole_red);
				}
			}
		}
	}

	// toggle the garage door
	class ToggleDoor extends AsyncTask<Void, String, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			String cmd = "TOGGLE\n";
			try {
				SSLSocket sock = (SSLSocket) sslSocketFactory.connectSocket(null, host, port, null, 0, new BasicHttpParams());
				sock.setSoTimeout(2000);
				BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ASCII"));
				if (br.readLine().equals("GARAGEDOOR")) {
					sock.getOutputStream().write(cmd.getBytes());
					br.readLine();
				}
				sock.close();
			} catch (Exception e) {
				publishProgress(e.getMessage());
			}
			return null;
		}

		// display error message
		@Override
		protected void onProgressUpdate(String... values) {
			// error message from exception
			Toast.makeText(ctx, values[0], Toast.LENGTH_LONG).show();
			Log.e(TAG, "Status exception: " + values[0]);
		}
	}
}
