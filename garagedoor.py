#! /usr/bin/python
# this is the script that gets called by inetd

import sys
import time
import syslog
import pifacedigitalio

def press_button():
    pifacedigital.relays[0].turn_on()
    time.sleep(0.2)
    pifacedigital.relays[0].turn_off()

syslog.openlog(ident='garage-door', facility=syslog.LOG_DAEMON)
pifacedigital = pifacedigitalio.PiFaceDigital()
cmd=sys.stdin.readline().strip()
if cmd == 'TOGGLE':
    # open/close the door immediately
    syslog.syslog('Toggle door')
    press_button()
    sys.stdout.write('DONE\n')
elif cmd == 'STATE':
    # report current state of door
    syslog.syslog('Door state: UNKNOWN')
    sys.stdout.write('UNKNOWN\n')
elif cmd == 'CLOSE':
    # wait until we lose contact and then close the door
    syslog.syslog('Close door - waiting for loss of signal')
    syslog.syslog('Closing door')
    press_button()
    sys.stdout.write('DONE\n')
else:
    # huh? what?
    syslog.syslog(syslog.LOG_ERR, 'Unknown command: '+cmd)
    sys.stdout.write('ERROR\n')
