#! /usr/bin/python
# this is the script that gets called by inetd

import os
import sys
import time
import syslog
import pifacedigitalio

# toggle relay
def press_button():
    if os.path.exists('/tmp/ignore'):
        return
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
syslog.openlog(ident='garagedoor', facility=syslog.LOG_DAEMON)
pifacedigital = pifacedigitalio.PiFaceDigital()

# identify ourselves
output('GARAGEDOOR')

# process command
cmd=input()
if cmd == 'TOGGLE':
    # open/close the door immediately
    syslog.syslog('Toggle door')
    press_button()
    output('DONE')
elif cmd == 'STATUS':
    # report current state of door
    # we do it this way so if things get in a weird state, we get notified
    data=pifacedigital.input_port.value & 3
    if data == 0:
        state='TRANSIT'
    elif data == 1:
        state='CLOSED'
    elif data == 2:
        state='OPEN'
    else:
        state='UNKNOWN'
    syslog.syslog('Door state: '+state)
    output(state)
elif cmd == 'AWAY':
    # wait until we lose contact and then close the door
    syslog.syslog('Away function waiting for loss of signal')
    addr=input()
    while os.system('ping -q -c 1 -W 1 '+addr+' >/dev/null') == 0:
        time.sleep(0.5)
    syslog.syslog('Closing door')
    press_button()
else:
    # huh? what?
    syslog.syslog(syslog.LOG_ERR, 'Unknown command: '+cmd)
    output('ERROR')
