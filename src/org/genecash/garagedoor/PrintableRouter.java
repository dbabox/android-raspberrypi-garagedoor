package org.genecash.garagedoor;

public class PrintableRouter {
	public String name;
	public String ip;

	public PrintableRouter(String n, String p) {
		name = n;
		ip = p;
	}

	@Override
	public String toString() {
		if (name == null) {
			// dummy status message
			return ip;
		}
		return "Name: " + name + "\nExternal IP: " + ip;
	}
}
