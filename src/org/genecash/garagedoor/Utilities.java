package org.genecash.garagedoor;

import java.io.FileInputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

public class Utilities {
	private static final String TAG = "GarageDoorSSL";
	private static String KEYSTORE_PASSWORD = "secret";

	// initialize SSL
	static SSLSocketFactory initSSL(Context ctx) {
		// Thanks to Erik Tews for this code
		// https://www.datenzone.de/blog/2012/01/using-ssltls-client-certificate-authentification-in-android-applications/
		try {
			// Load local client certificate and key and server certificate
			FileInputStream pkcs12in = ctx.openFileInput(GarageSettings.CERT_FILE);
			SSLContext context = SSLContext.getInstance("TLS");
			KeyStore keyStore = KeyStore.getInstance("PKCS12");
			keyStore.load(pkcs12in, KEYSTORE_PASSWORD.toCharArray());
			// Build a TrustManager, that trusts only the server certificate
			TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
			KeyStore keyStoreCA = KeyStore.getInstance("BKS");
			keyStoreCA.load(null, null);
			keyStoreCA.setCertificateEntry("Server", keyStore.getCertificate("Server"));
			tmf.init(keyStoreCA);
			// Build a KeyManager for Client Authentication
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(keyStore, null);
			context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
			return context.getSocketFactory();
		} catch (Exception e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
			Toast.makeText(ctx, "Could not initialize SSL: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}
		return null;
	}
}
