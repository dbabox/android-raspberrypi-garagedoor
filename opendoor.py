#! /usr/bin/python

import sys
import time
import pifacedigitalio

word=sys.stdin.readline().strip()
if word == 'OPEN':
    pifacedigital = pifacedigitalio.PiFaceDigital()
    pifacedigital.relays[0].turn_on()
    time.sleep(0.2)
    pifacedigital.relays[0].turn_off()
    print "DONE"
