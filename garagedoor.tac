#!/usr/bin/python

import os
import sys
import grp
import pwd
import time
import pifacedigitalio

from twisted.python import log
from twisted.application import internet, service
from twisted.internet import task, ssl
from twisted.internet.protocol import Factory, Protocol
from twisted.protocols.basic import LineReceiver

# this is the port we listen on
port=17000

# toggle relay
def press_button():
    log.msg('press button')
    if os.path.exists('/var/run/garage_door_disarmed'):
        return
    pifacedigital.relays[0].turn_on()
    time.sleep(0.2)
    pifacedigital.relays[0].turn_off()

# read current door status
def status():
    data=pifacedigital.input_port.value & 3
    if data == 0:
        return 'TRANSIT'
    elif data == 1:
        return 'CLOSED'
    elif data == 2:
        return 'OPEN'
    return 'UNKNOWN'

class GarageDoorProtocol(LineReceiver):
    def __init__(self):
        self.response='GARAGEDOOR'
        self.security='cleartext'

    def connectionMade(self):
        self.addr=self.transport.getPeer().host
        log.msg(self.security+' connection from: '+self.addr)
        self.delimiter='\n'
        self.statusTask=None
        self.cmd=None
        self.sendLine(self.response)

    def connectionLost(self, reason):
        log.msg(self.security+' connection lost: '+self.addr)
        if self.statusTask:
            self.statusTask.stop()

    def lineReceived(self, line):
        # ignore blank lines
        if not line:
            return

        # parse the command
        words=line.split()
        cmd=words[0].lower()
        self.key=None
        if len(words) > 1:
            self.key=words[1]

        # dispatch the command to the appropriate do_* method
        try:
            method=getattr(self, 'do_'+cmd)
        except AttributeError, e:
            log.msg('unknown '+self.security+' command "'+cmd+'" from: '+self.addr)
            self.transport.loseConnection()
        else:
            try:
                log.msg('command: '+cmd)
                method()
            except Exception, e:
                log.msg('error: '+str(e))
                self.transport.loseConnection()

    def do_ping(self):
        self.sendLine('PONG')

# these are commands done over SSL
class GarageDoorSecure(GarageDoorProtocol):
    def __init__(self):
        self.response='GARAGEDOOR SECURE'
        self.security='secure'

    # open/close the door immediately
    def do_toggle(self):
        press_button()
        self.sendLine('DONE')
        self.transport.loseConnection()

    # close door if it's open
    def do_close(self):
        if status() == 'OPEN':
            press_button()
        self.sendLine('DONE')
        self.transport.loseConnection()

    # calculate and send one-time authorization key
    def do_key(self):
        log.msg('sending key')
        # read 64 random bytes
        f=open('/dev/random', 'rb')
        bytes=f.read(64)
        # encode bytes as hex
        key=':'.join('{:02X}'.format(ord(c)) for c in bytes)
        # remember key
        keys.append(key)
        # send key
        self.sendLine(key)
        self.sendLine('KEY SENT')

# these are commands done over a standard socket
class GarageDoorCleartext(GarageDoorProtocol):
    # verify key passed with command
    def check_key(self):
        log.msg('checking key')
        if (self.key != None) and (self.key in keys):
            keys.remove(self.key)
            return True
        log.msg('key failure')
        return False

    # periodic task to report current state of door
    def status(self):
        self.ctr+=1
        self.state_new=status()
        # output only when status changes, or keepalive timer expires
        if (self.state_new != self.state_old) or (self.ctr > 20):
            log.msg('door state: '+self.state_new)
            self.sendLine(self.state_new)
            self.state_old=self.state_new
            self.ctr=0

    # periodic task to wait until we lose contact and then close the door
    def away(self):
        log.msg('away ping')
        # deal with single dropped packets
        if os.system('ping -q -c 1 -W 2 '+self.addr+' >/dev/null') == 0:
            self.ctr=0
        else:
            self.ctr+=1

        # we've lost contact
        if self.ctr > 3:
            log.msg('away function lost contact')
            # stop executing
            awayTasks[self.addr].stop()
            del awayTasks[self.addr]
            # close door
            if status() == 'OPEN':
                press_button()

    # open door if it's closed
    def do_open(self):
        if self.check_key():
            if status() == 'CLOSED':
                press_button()
            self.sendLine('DONE')
        self.transport.loseConnection()

    # report current state of door until the remote closes connection
    def do_status(self):
        self.ctr=0
        self.state_old=''
        self.statusTask=task.LoopingCall(self.status)
        self.statusTask.start(0.5)

    # wait until we lose contact and then close the door
    # this is only invoked over local secure wi-fi so we don't need to check a key
    def do_away(self):
        # if we're already waiting, abort
        if self.addr in awayTasks:
            awayTasks[self.addr].stop()
            del awayTasks[self.addr]
            self.sendLine('ABORTED')
            log.msg('away function aborted')
            return

        # open door if it's closed
        if status() == 'CLOSED':
            press_button()

        log.msg('away function waiting for loss of signal')
        self.sendLine('WAITING')
        self.transport.loseConnection()

        # start task
        self.ctr=0
        awayTasks[self.addr]=task.LoopingCall(self.away)
        awayTasks[self.addr].start(1)

# tasks waiting for the user to leave
awayTasks={}

# valid keys
keys=[]

# initialize PiFace board
pifacedigital=pifacedigitalio.PiFaceDigital()

# start logging
log.startLogging(sys.stdout)

# load SSL client certificate
with open("/etc/garagedoor/cert-client.pem") as clientCertFile:
    clientCert=ssl.Certificate.loadPEM(clientCertFile.read())

# load SSL server certificate & key
with open("/etc/garagedoor/key-server.pem") as keyFile:
    with open("/etc/garagedoor/cert-server.pem") as certFile:
        serverCert=ssl.PrivateCertificate.loadPEM(keyFile.read()+certFile.read())

# kick everything off
application=service.Application('garagedoor',
                                uid=pwd.getpwnam('garagedoor').pw_uid,
                                gid=grp.getgrnam('nogroup').gr_gid)

# start secure listener
factory=Factory()
factory.protocol=GarageDoorSecure
contextFactory=serverCert.options(clientCert)
internet.SSLServer(port, factory, contextFactory).setServiceParent(service.IServiceCollection(application))

# start cleartext listener
factory=Factory()
factory.protocol=GarageDoorCleartext
internet.TCPServer(port+1, factory).setServiceParent(service.IServiceCollection(application))
