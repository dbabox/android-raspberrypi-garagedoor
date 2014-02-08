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
    log.msg('Press button')
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

# Twisted class to implement garage door functionality
class GarageDoor(LineReceiver):
    def connectionMade(self):
        self.addr=self.transport.getPeer().host
        log.msg('Connection from: '+self.addr)
        self.delimiter='\n'
        self.statusTask=None
        self.cmd=None
        self.sendLine('GARAGEDOOR')

    def connectionLost(self, reason):
        if not self.cmd:
            # SSL authentication failure
            self.banhammer()

        if self.statusTask:
            # shut down status task
            self.statusTask.stop()

    def lineReceived(self, cmd):
        log.msg('Command: '+cmd)
        self.cmd=cmd
        if cmd == 'TOGGLE':
            # open/close the door immediately
            press_button()
            self.sendLine('DONE')
            self.transport.loseConnection()
        elif cmd == 'OPEN':
            # open door if it's closed
            if status() == 'CLOSED':
                press_button()
            self.sendLine('DONE')
            self.transport.loseConnection()
        elif cmd == 'CLOSE':
            # close door if it's open
            if status() == 'OPEN':
                press_button()
            self.sendLine('DONE')
            self.transport.loseConnection()
        elif cmd == 'STATUS':
            # report current state of door until the remote closes connection
            self.ctr=0
            self.state_old=''
            self.statusTask=task.LoopingCall(self.status)
            self.statusTask.start(0.5)
        elif cmd == 'AWAY':
            # wait until we lose contact and then close the door
            if self.addr in awayTasks:
                # if we're already waiting, abort
                awayTasks[self.addr].stop()
                del awayTasks[self.addr]
                self.sendLine('ABORTED')
                log.msg('Away function aborted')
                return

            # open door if it's closed
            if status() == 'CLOSED':
                press_button()

            log.msg('Away function waiting for loss of signal')
            self.sendLine('WAITING')
            self.transport.loseConnection()
            self.ctr=0
            awayTasks[self.addr]=task.LoopingCall(self.away)
            awayTasks[self.addr].start(0.5)
        else:
            # unknown command
            log.error('Unknown command from: '+self.addr)
            self.transport.loseConnection()
            self.banhammer()

    # periodic task to report current state of door
    def status(self):
        self.ctr+=1
        self.state_new=status()
        # output only when status changes, or keepalive timer expires
        if (self.state_new != self.state_old) or (self.ctr > 20):
            if self.state_new != self.state_old:
                log.msg('Door state: '+self.state_new)
            self.sendLine(self.state_new)
            self.state_old=self.state_new
            self.ctr=0

    # periodic task to wait until we lose contact and then close the door
    def away(self):
        # deal with single dropped packets
        if os.system('ping -q -c 1 -W 1 '+self.addr+' >/dev/null') == 0:
            self.ctr=0
        else:
            self.ctr+=1

        # we've lost contact
        if self.ctr > 2:
            # stop executing
            awayTasks[self.addr].stop()
            del awayTasks[self.addr]
            log.msg('Away function lost contact')
            # close door
            if status() == 'OPEN':
                press_button()

    # ban this IP from further communication
    def banhammer(self):
        log.err('Ban '+self.addr)
        # ban an IP with
        # iptables -I INPUT -s 1.2.3.4 -j DROP
        # view currently banned list with
        # iptables -L INPUT -n --line-numbers
        # un-ban IP listed on line #1
        # iptables -D INPUT 1
        os.system('sudo iptables -I INPUT -s '+self.addr+' -j DROP')

# tasks waiting for the user to leave
awayTasks={}

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
factory=Factory()
factory.protocol=GarageDoor
contextFactory=serverCert.options(clientCert)
internet.SSLServer(port, factory, contextFactory).setServiceParent(service.IServiceCollection(application))
