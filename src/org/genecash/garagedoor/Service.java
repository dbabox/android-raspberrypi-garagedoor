package org.genecash.garagedoor;

import java.net.InetAddress;

public class Service {
	public String name;
	public String host;
	public String port;

	public Service(String n, InetAddress h, int p) {
		name = n;
		host = h.toString().substring(1);
		port = "" + p;
	}

	@Override
	public String toString() {
		return "Name: " + name + "\nHost: " + host + "\nPort: " + port;
	}
}
