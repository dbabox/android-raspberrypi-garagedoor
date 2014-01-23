package org.genecash.garagedoor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class GarageDoor extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// start background service process
		this.startService(new Intent(this, GarageDoorService.class));

		finish();
	}
}
