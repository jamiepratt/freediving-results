#!/usr/bin/env python3
"""Idempotent tunnel + origin DNS provisioning. Secrets never printed or saved locally."""
import json, subprocess, urllib.request
ACCOUNT='d55b062637980b94f707f6fb05281a88'
ZONE='fe7b1dc7d11fd117565553093bb8b8fe'
s=subprocess.check_output(['wrangler','auth','token','--profile','alphacompose','--json'],text=True)
auth=json.loads(s[s.index('{'):])['token']
dns=subprocess.check_output(['security','find-generic-password','-s','cloudflare-api-token','-a','alphacompose-dns','-w'],text=True).strip()
def api(path, method='GET', body=None, token=auth):
    request=urllib.request.Request('https://api.cloudflare.com/client/v4/'+path,method=method,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},data=None if body is None else json.dumps(body).encode())
    result=json.load(urllib.request.urlopen(request))
    if not result['success']: raise RuntimeError('Cloudflare request failed')
    return result['result']
base='accounts/'+ACCOUNT+'/cfd_tunnel'
tunnels=[x for x in api(base+'?is_deleted=false') if x['name']=='freediving-results-poc']
assert len(tunnels)<=1
item=tunnels[0] if tunnels else api(base,'POST',{'name':'freediving-results-poc','config_src':'cloudflare'})
tunnel=item['id']
api(base+'/'+tunnel+'/configurations','PUT',{'config':{'ingress':[{'hostname':'poc-origin.alphacompose.com','service':'http://127.0.0.1:8785','originRequest':{'httpHostHeader':'127.0.0.1:8785'}},{'service':'http_status:404'}]}})
token=api(base+'/'+tunnel+'/token')
subprocess.run(['ssh','bridge-vps','sudo -n sh -c "umask 077; cat > /etc/freediving/tunnel-token"'],input=token,text=True,check=True)
records=api('zones/'+ZONE+'/dns_records?name=poc-origin.alphacompose.com',token=dns)
if records:
    assert len(records)==1 and records[0]['type']=='CNAME' and records[0]['content']==tunnel+'.cfargotunnel.com', 'Refusing unrelated DNS overwrite'
else:
    api('zones/'+ZONE+'/dns_records','POST',{'type':'CNAME','name':'poc-origin.alphacompose.com','content':tunnel+'.cfargotunnel.com','proxied':True,'ttl':1},token=dns)
subprocess.run(['ssh','bridge-vps','sudo -n systemctl enable --now freediving-tunnel.service'],check=True)
print('Tunnel configured:',tunnel)
