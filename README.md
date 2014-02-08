Hands-free garage door operation using a Raspberry Pi and an Android phone
==========================================================================

From the [android-raspberrypi-garagedoor](https://github.com/CrashCash/android-raspberrypi-garagedoor) Git repository on GitHub

I ride a motorcycle, and garage door remotes don't last very long out in the elements, even if you can find a good place to mount one on your bike and you're willing to put up with the hassle.

This system automatically opens the garage door when I come home. The user only has to remember to start the Android app before riding home. When you arrive home and it sees your personal wi-fi network, it asks the Raspberry Pi to open the garage door so you can drive right in without fiddling with anything.

It was inspired by [Brad Fitzpatrick's work](http://brad.livejournal.com/2394220.html) that he started in 2008, and the fact that my Flash2Pass stopped working.

It uses a [Raspberry Pi](http://www.raspberrypi.org/) with the [PiFace I/O board](http://www.piface.org.uk/products/piface_digital/) and a wi-fi dongle. The Pi should be running [Raspbian](http://www.raspbian.org). It uses SSL mutual authentication to control who can open the door. Only the Android app with the properly signed certificate can do it.

Originally the approach was to connect to the Raspberry Pi over my home wi-fi. However, it takes *forever* to authorize and connect to the wi-fi network using WPA2, so what I do now is to connect over the cellular data network when my wi-fi network appears in a scan. The app is smart enough to turn the data connection on and off as necessary.

When I'm getting ready to leave, there is an "away" app that opens the garage door, and then when I'm out of wi-fi range the Raspberry Pi automatically closes the door. If you change your mind, just run the app again to cancel it.

You can also check the status of the garage door remotely, so you can know it's closed. It displays a large green button if it's closed, which turns to red if it's open. It's barberpole-grey if it's in-between, and barberpole-red if there is a problem. You can tap the button to open or close the door.

The setup app uses UPnP and zeroconf to automatically determine as much information as it can, so the Android configuration is just a couple taps.

### To quickly set up the Raspberry Pi:

(you'll need to do most of this as root)

1.  Change the hostname to "garagedoor" in raspi-config.
2.  Get the most current list of the latest Raspbian packages by running:
     `apt-get update`
3.  Install the software packages you need by running:
     `apt-get install avahi-daemon python-pifacedigitalio python-twisted`
4.  Copy the garagedoor.service file to /etc/avahi/services.
5.  Create the script user by running:
     `adduser --system garagedoor`
6.  Add the script user to the proper groups (so he can see the PiFace devices) by running:
     `adduser garagedoor spi`
     `adduser garagedoor gpio`
7.  Copy the garagedoor.tac file to /usr/local/bin.
8.  Set the proper permissions by running:
     `chmod ugo=r /usr/local/bin/garagedoor.tac`
9.  Assign it to the script user by running:
     `chown garagedoor:nogroup /usr/local/bin/garagedoor.tac`
10. Install a wi-fi dongle as per the HOWTOs on the 'net, like [this one from SparkFun](https://learn.sparkfun.com/tutorials/using-pcduinos-wifi-dongle-with-the-pi). You will most probably need a powered USB hub for it, as most dongles are extremely power-hungry.
11. Start the daemon with:
     `twistd --python /usr/local/bin/garagedoor.tac --syslog`

### Local network setup

-   Enable UPnP on your router. This allows the setup app to simply ask it for the external IP address, so you don't have to manually trawl through the router configuration webpages.
-   Assign the Raspberry Pi a reserved DHCP address. Your router will have a screen somewhere to say "this IP address is reserved for that MAC address".
-   Open a port in the firewall and forward it to the Raspberry Pi's address and port 17000. This lets the phone to connect over the internet. Remember this port number so you can enter it in the setup app.
-   You should run avahi-daemon on your Linux box to resolve "garagedoor.local" for ssh, but this is usually already the case with Debian and Ubuntu.

### Setting up the ability to open/close the garage door

One of the relays on the PiFace board is connected to the opener simulating a button. You can connect it in parallel with the real pushbuttons.

The wires should be inserted in the two relay contacts nearest the Rasberry Pi connector (see wiring.jpg) and connected to where you need to connect switches on your opener. I used plain 22-gauge stranded 2-conductor speaker wire.

To test things, run the following Python commands:

`       >>> import time, pifacedigitalio       >>> pifacedigital=pifacedigitalio.PiFaceDigital()       >>> pifacedigital.relays[0].turn_on() ; time.sleep(0.2) ; pifacedigital.relays[0].turn_off()     `

The garage door should open, or you should at least hear a click from the relay.

### Setting up the ability to sense if the door is opened or closed

[Reed switches](http://en.wikipedia.org/wiki/Reed_switch) are [small devices](http://www.reed-switch-info.com/) that close a contact when a magnet is brought near. I mounted two of them on the garage opener rail, and placed magnets on the slider so that the reed switches activate when the slider reaches the open or closed positions. These reed switches are connected to the digital inputs on the PiFace board. I used [Mouser Electronics](http://mouser.com/) part 507-AMS-9G which are AMSECO 505-90G devices.

One end of each switch should be wired into the green connector nearest the RCA video jack. This is the 0-volt common ground. The other end of the "door-closed" switch should be connected to input "0" at the other end of the green connector. The "door-opened" switch should be connected next to it, in input "1". These are the same inputs as the first 2 pushbuttons switches. Look at wiring.jpg, reed1.jpg, and reed2.jpg for details.

To test things, run the following Python commands:

`       >>> import pifacedigitalio       >>> pifacedigital=pifacedigitalio.PiFaceDigital()       >>> print pifacedigital.input_port.value & 3       1     `

This should print a "1" if the door is closed, or "2" if it's open. It should print "0" only if the door is neither open nor closed.

### Security and generating the SSL certificates

Obviously the garage door needs to be secure. It's protected by SSL mutual authentication, where not only does the phone authenticate the server, but the phone presents a certificate to prove it's authorized.

Here are the steps to generate two self-signed X.509 certificates. This will generate two new RSA 2048 bit keys, generate two self signed certificates, and bundle the client certificate with the corresponding private key, and the server's public certificate in a PKCS\#12 container file. These certificates will be valid for 10 years (3650 days).

`       OPENSSL_OPTS="-new -newkey rsa:2048 -nodes -days 3650 -x509"       openssl req -keyout key-server.pem -subj "/CN=server" -out cert-server.pem $OPENSSL_OPTS       openssl req -keyout key-client.pem -subj "/CN=client" -out cert-client.pem $OPENSSL_OPTS       openssl pkcs12 -export -passout "pass:secret" -in cert-client.pem -inkey key-client.pem -out client.p12 -certfile cert-server.pem -name "Client" -caname       "Server"     `

Now you need to install the certificates into the proper places.

-   Copy client.p12 to the sdcard area on your phone, and use the setup app "Fetch Certificate" button to move it to protected storage.
-   Copy cert-server.pem, key-server.pem, and cert-client.pem to /etc/garagedoor on the Raspberry Pi.
-   Set the proper permissions by running (as root) on the Raspberry Pi
     `           cd /etc/garagedoor           chmod u=rxt .           chmod u=r cert-server.pem key-server.pem cert-client.pem           chown garagedoor:nogroup . cert-server.pem key-server.pem cert-client.pem         `

### Credits

The setup app uses the [UPNPLib-mobile library](http://sourceforge.net/projects/upnplibmobile) created by "suggam", which in turn is a port of the abandoned UPNPLib project developed by "sbbi". Grateful thanks to whoever these guys are.

### Brickbats

Since security is so important in mobile apps, it would really behoove Google to write training docs on how to code SSL, and how to generate the keys. It'd be icing on the cake if they covered mutual authentication for non-HTTPS applications. The Twisted guys can do it for Python, why can't Google do it for Java?

I spent six days searching Google and Bing, and I found only [one answer](https://www.datenzone.de/blog/2012/01/using-ssltls-client-certificate-authentification-in-android-applications/) that showed how to implement mutual authentication properly, and more importantly, showed how to generate the certificates. Even this answer doesn't explain how it works, it just shows a couple blocks of code and OpenSSL commands.

*Every single one* of the StackOverflow answers were "here's how to accept every certificate" which makes implementing SSL a waste of time if you're just going to let everybody in. Worse, the responses indicated the coder was going to use it without question, in production code, and without realizing that it was no security at all. They said "Great! Thanks for the wonderful answer. Now my code can connect. I'm done!"

If Google feels that writing such a tutorial is some sort of legal risk, they could keep it on their servers and not make it part of the SDK documentation download. If they found an error or something misleading, it would still be under their control and they could instantly rewrite or remove the problem. They have no excuse for not putting this information out there.

This stuff is difficult, and Android developers need help.

### History

I originally started off with a very simple Python script run by inetd, about 09-JAN-2014. I realized I really needed SSL authentication, so I used stunnel as an inetd wrapper, then as a full daemon. This is how I got SSL client authentication working. Performance was horrible due to the SSL initialization time, so I rewrote it as a standalone daemon using the Twisted framework. The first "production" version was 03-FEB-2014.

### Other notes

I originally intended to use an Arduino, but a wi-fi shield was \$85! The other killer factor was that all the I/O shields were kits and I'm really crap at soldering.

I thought all the Raspberry Pi buzz was just the usual internet hype, so I never really considered one, but for \$35, it was too good to pass up. The [*MAKE Magazine*](http://makezine.com) microcontroller guide basically flat out said "out of the dozen boards in this roundup, this or the Arduino are your two good choices" and that was a deciding factor.

It's amazing that it's a full Debian machine not too far behind my previous generation desktop PC.
