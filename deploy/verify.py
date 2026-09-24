#!/usr/bin/env python3
"""Public smoke checks. Does not create records, approvals or corrections."""
import json, urllib.request, urllib.error
base='https://poc.alphacompose.com'
def request(path, expected, method='GET', headers=None, data=None):
    req=urllib.request.Request(base+path,method=method,headers={'User-Agent':'freediving-deployment-check', **(headers or {})},data=data)
    try: response=urllib.request.urlopen(req,timeout=20)
    except urllib.error.HTTPError as error: response=error
    assert response.status==expected, (path,response.status,expected)
    assert response.headers.get('Cache-Control')=='no-store', path
    return response.read()
assert b'Freediving Results' in request('/',200)
request('/public.js',200); request('/public.css',200)
result=json.loads(request('/api/results',200))
assert result['demo'] is False
request('/api/results?q=deployment-check',200)
request('/api/results?limit=51',400)
request('/api/owner',404); request('/data',404)
request('/api/corrections',403,method='POST',headers={'Origin':'https://invalid.example'},data=b'{}')
request('/api/corrections',400,method='POST',headers={'Origin':base,'Content-Type':'application/json','X-Correction-Request':'1'},data=b'{}')
try:
    urllib.request.urlopen(urllib.request.Request('https://poc-origin.alphacompose.com/api/results',headers={'User-Agent':'freediving-deployment-check'}),timeout=20)
    raise AssertionError('Direct origin accepted')
except urllib.error.HTTPError as error: assert error.code==403
print('HTTPS, assets, search, bounds, origin and private-route checks passed;',result['coverage']['results'],'published records')
