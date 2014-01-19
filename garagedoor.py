#! /usr/bin/python
# this is the script that gets called by inetd

import sys
import time
import syslog
import pifacedigitalio

# toggle relay
def press_button():
    pifacedigital.relays[0].turn_on()
    time.sleep(0.2)
    pifacedigital.relays[0].turn_off()

# read from network
def input():
    return sys.stdin.readline().strip()

# write to network
def output(s):
    sys.stdout.write(s+'\n')
    sys.stdout.flush()

# start of main routine
syslog.openlog(ident='garage-door', facility=syslog.LOG_DAEMON)
pifacedigital = pifacedigitalio.PiFaceDigital()

# process command
cmd=input()
if cmd == 'TOGGLE':
    # open/close the door immediately
    syslog.syslog('Toggle door')
    press_button()
    output('DONE')
elif cmd == 'STATE':
    # report current state of door
    syslog.syslog('Door state: UNKNOWN')
    output('UNKNOWN')
elif cmd == 'AWAY':
    # wait until we lose contact and then close the door
    syslog.syslog('Away function waiting for loss of signal')
    syslog.syslog('Closing door')
    press_button()
    output('DONE')
else:
    # huh? what?
    syslog.syslog(syslog.LOG_ERR, 'Unknown command: '+cmd)
    output('ERROR')
