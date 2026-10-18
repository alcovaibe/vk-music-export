"""Return a tap only for known emulator framework ANRs, never for the app under test."""
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
titles = [n.get('text', '') for n in root.iter('node') if n.get('resource-id') == 'android:id/alertTitle']
allowed = {"System UI isn't responding": 'Wait', "Quickstep isn't responding": 'Close app'}
action = next((allowed[t] for t in titles if t in allowed), None)
if action:
    for node in root.iter('node'):
        if node.get('package') == 'android' and node.get('text') == action and node.get('clickable') == 'true':
            bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
            if bounds:
                x1, y1, x2, y2 = map(int, bounds.groups())
                print((x1 + x2) // 2, (y1 + y2) // 2)
                break
