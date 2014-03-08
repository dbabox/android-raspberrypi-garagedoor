package org.genecash.garagedoor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class ViewLog extends Activity {
	private File logfile;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		FileReader f;

		super.onCreate(savedInstanceState);
		setContentView(R.layout.view_log);

		logfile = new File(getExternalFilesDir(null), GarageDoorService.LOG_FILENAME);

		// "clear log" button.
		findViewById(R.id.clear).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (logfile.delete()) {
					Toast.makeText(getApplicationContext(), "Log file cleared", Toast.LENGTH_LONG).show();
					finish();
				} else {
					Toast.makeText(getApplicationContext(), "Log file NOT cleared!", Toast.LENGTH_LONG).show();
				}
			}
		});

		TextView tv = (TextView) findViewById(R.id.log);
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logfile)));
			String line = null;
			while ((line = reader.readLine()) != null) {
				tv.setText(tv.getText() + line + "\n");
			}
			reader.close();
		} catch (Exception e) {
			tv.setText("Unable to load log file.");
			return;
		}

		// scroll to bottom
		final ScrollView sv = (ScrollView) findViewById(R.id.scrollbar);
		sv.post(new Runnable() {
			@Override
			public void run() {
				sv.fullScroll(View.FOCUS_DOWN);
			}
		});
	}
}
