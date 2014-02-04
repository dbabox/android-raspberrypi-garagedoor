package org.genecash.garagedoor;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import android.util.Log;

public class Utilities {
	private static final String TAG = "GarageDoorSSL";
	private static String KEYSTORE_PASSWORD = "secret";

	// initialize SSL
	static SSLSocketFactory initSSL(InputStream pkcs12in) {
		// Thanks to Erik Tews for this code
		// https://www.datenzone.de/blog/2012/01/using-ssltls-client-certificate-authentification-in-android-applications/
		try {
			// Load local client certificate and key and server certificate
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
		} catch (NoSuchAlgorithmException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (CertificateException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (IOException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (KeyStoreException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (UnrecoverableKeyException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		} catch (KeyManagementException e) {
			Log.e(TAG, "Exception: " + Log.getStackTraceString(e));
		}
		return null;
	}
}
