package org.genecash.garagedoor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.Toast;

public class GarageAway extends Activity {
	private Context ctx = this;
	private String host;
	private int port;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// pull host address & port from preferences
		SharedPreferences sSettings = getSharedPreferences(GarageSettings.PREFS_NAME, MODE_PRIVATE);
		host = sSettings.getString(GarageSettings.PREFS_LOCAL_IP, "");
		port = sSettings.getInt(GarageSettings.PREFS_LOCAL_PORT, 0);

		new SetAway().execute();
		finish();
	}

	// talk to the network in a separate thread
	class SetAway extends AsyncTask<Void, String, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			String cmd = "AWAY\n";
			try {
				Socket sock = new Socket(host, port);
				sock.setSoTimeout(2000);
				BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ASCII"));
				if (br.readLine().equals("GARAGEDOOR")) {
					sock.getOutputStream().write(cmd.getBytes());
					String status = br.readLine();
					if (status.equals("WAITING")) {
						publishProgress("Away function ready");
					}
					if (status.equals("ABORTED")) {
						publishProgress("Away function aborted");
					}
				}
				sock.close();
			} catch (Exception e) {
				publishProgress(e.getMessage());
			}
			return null;
		}

		// display exception message
		@Override
		protected void onProgressUpdate(String... values) {
			Toast.makeText(ctx, values[0], Toast.LENGTH_LONG).show();
		}
	}
}
