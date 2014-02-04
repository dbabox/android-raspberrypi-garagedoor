package org.genecash.garagedoor;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

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
		sslSocketFactory = Utilities.initSSL(getResources().openRawResource(R.raw.client));

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
				SSLSocket sock = (SSLSocket) sslSocketFactory.createSocket(host, port);
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
				SSLSocket sock = (SSLSocket) sslSocketFactory.createSocket(host, port);
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
