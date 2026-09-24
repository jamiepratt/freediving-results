#!/usr/bin/env python3
"""Authenticated loopback readiness, with a bounded startup wait. No secret output."""
import pathlib, shlex, urllib.request, json, time
values=dict(line.split('=',1) for line in shlex.split(pathlib.Path('/etc/freediving/public.env').read_text()))
for attempt in range(30):
    try:
        request=urllib.request.Request('http://127.0.0.1:8785/api/results',headers={
            'X-Freediving-Gateway':values['FREEDIVING_GATEWAY_SECRET'],
            'X-Freediving-Client':'127.0.0.1','Origin':values['FREEDIVING_PUBLIC_ORIGIN']})
        with urllib.request.urlopen(request,timeout=3) as response:
            data=json.load(response)
            assert response.status==200 and data['demo'] is False
        print('Public application ready')
        break
    except Exception:
        if attempt==29: raise SystemExit('Public application health check failed')
        time.sleep(1)
