#! /usr/bin/python
# this is the script that gets called by inetd

import os
import sys
import time
import socket
import syslog
import pifacedigitalio

lockdir='/var/lock/'

# toggle relay
def press_button():
    syslog.syslog('Press button')
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

# get address of the other end
client=socket.fromfd(0, socket.AF_INET, socket.SOCK_STREAM)
addr=client.getpeername()[0]
syslog.syslog('Connect: '+addr)

# ready for command
output('GARAGEDOOR')

# process command
cmd=input()
syslog.syslog('Command: '+cmd)
if cmd == 'TOGGLE':
    # open/close the door immediately
    press_button()
elif cmd == 'OPEN':
    # open door if it's closed
    if status() == 'CLOSED':
        press_button()
elif cmd == 'CLOSE':
    # close door if it's open
    if status() == 'OPEN':
        press_button()
elif cmd == 'STATUS':
    # report current state of door until the remote closes connection
    state_old=''
    ctr=0
    while 1:
        ctr+=1
        state_new=status()
        if (state_new != state_old) or (ctr > 20):
            # output only when status changes, or keepalive timer expires
            if state_new != state_old:
                syslog.syslog('Door state: '+state_new)
            try:
                output(state_new)
            except:
                # we can only tell the pipe is closed by getting an error trying to write to it
                break
            state_old=state_new
            ctr=0
        time.sleep(0.5)
elif cmd == 'AWAY':
    # wait until we lose contact and then close the door
    # check for "lock" file to see if the "away" function is in progress in another instance of the script
    lockfile='garagedoor-'+addr+'.lock'
    if os.path.exists(lockdir+lockfile):
        # when we remove it, the other instance will exit
        syslog.syslog('Removing lockfile')
        os.remove(lockdir+lockfile)
        output('ABORTED')
        exit(0)
    file(lockdir+lockfile, 'w').write('')

    # open door if it's closed
    if status() == 'CLOSED':
        press_button()

    # wait until we lose contact or are told to exit
    syslog.syslog('Away function waiting for loss of signal')
    output('WAITING')
    ctr=0
    while (ctr < 2) and os.path.exists(lockdir+lockfile):
        # deal with single dropped packets
        if os.system('ping -q -c 1 -W 1 '+addr+' >/dev/null') == 0:
            ctr=0
        else:
            ctr+=1
        time.sleep(0.5)

    # close door
    if os.path.exists(lockdir+lockfile):
        if status() == 'OPEN':
            press_button()
    else:
        syslog.syslog('Aborted')
    try:
        os.remove(lockdir+lockfile)
    except:
        pass
else:
    # huh? what?
    syslog.syslog(syslog.LOG_ERR, 'Unknown command')
    # ban him
    os.system('fail2ban-client set ssh banip '+addr)
    # bug workaround
    os.system('touch /var/log/auth.log')
