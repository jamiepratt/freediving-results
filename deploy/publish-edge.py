#!/usr/bin/env python3
"""Publish gateway and synchronize its secret over SSH/stdin without local secret files."""
import pathlib, subprocess, shlex
root=pathlib.Path(__file__).resolve().parents[1]
cmd=['wrangler','--profile','alphacompose','--config','deploy/wrangler.jsonc']
subprocess.run(cmd+['deploy'],cwd=root,check=True)
content=subprocess.check_output(['ssh','bridge-vps','sudo -n cat /etc/freediving/public.env'],text=True)
values=dict(line.split('=',1) for line in shlex.split(content))
subprocess.run(cmd+['secret','put','GATEWAY_SECRET'],cwd=root,input=values['FREEDIVING_GATEWAY_SECRET'],text=True,check=True)
