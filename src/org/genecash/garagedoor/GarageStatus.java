package org.genecash.garagedoor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

public class GarageStatus extends Activity {
	private static final String cmd = "STATUS\n";

	private Context ctx = this;
	private String host;
	private int port;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.status);

		// pull host address & port from preferences
		SharedPreferences sSettings = getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE);
		host = sSettings.getString(Settings.PREFS_HOST, "");
		port = sSettings.getInt(Settings.PREFS_PORT, 0);
		String status = null;
		AsyncTask<Void, String, String> task = new GetStatus().execute();
		try {
			status = task.get();
		} catch (Exception e) {
			Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
		}

		TextView tvStatus = (TextView) findViewById(R.id.status);
		tvStatus.setText(status);
	}

	// get a string from the network in a separate thread
	class GetStatus extends AsyncTask<Void, String, String> {
		@Override
		protected String doInBackground(Void... params) {
			StringBuffer data = new StringBuffer();
			String status = null;

			try {
				Socket sock = new Socket(host, port);
				sock.setSoTimeout(2000);
				BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ASCII"));
				if (br.readLine().equals("GARAGEDOOR")) {
					sock.getOutputStream().write(cmd.getBytes());
					status = br.readLine();
				}
				sock.close();
			} catch (Exception e) {
				publishProgress(e.getMessage());
			}
			return status;
		}

		// display exception message
		@Override
		protected void onProgressUpdate(String... values) {
			Toast.makeText(ctx, values[0], Toast.LENGTH_LONG).show();
		}
	}
}
