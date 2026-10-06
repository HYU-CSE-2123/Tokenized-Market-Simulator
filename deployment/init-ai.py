"""Explicit cost-bearing ADMIN initialization, run inside the private edge network.

No keys/JWT/password in argv or logs. NOT called by Compose/local smoke/startup.
"""
import argparse
import json
from pathlib import Path
import re
import urllib.request

def decode_property(value):
    def decode(match):
        if match[1]: return chr(int(match[1], 16))
        return {'n':'\n','r':'\r','t':'\t','f':'\f'}.get(match[2], match[2])
    result = re.sub(r'\\u([0-9a-fA-F]{4})|\\(.)', decode, value)
    return result.encode('utf-16', 'surrogatepass').decode('utf-16')

def post(path, data, token=None):
    request = urllib.request.Request('http://backend:8082' + path, data=json.dumps(data).encode(),
              headers={'Content-Type':'application/json', **({'Authorization':'Bearer ' + token} if token else {})})
    with urllib.request.urlopen(request, timeout=90) as response: return json.load(response)

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--confirm-cost', action='store_true'); args = parser.parse_args()
    if not args.confirm_cost: parser.error('Explicit --confirm-cost required after budget approval')
    try:
        properties = {}
        for line in Path('/run/secrets/application.properties').read_text().splitlines():
            key, sep, value = line.partition('=')
            if sep: properties[key] = decode_property(value)
        token = post('/api/auth/login', {'loginId':properties['ADMIN_LOGIN_ID'],'password':properties['ADMIN_PASSWORD']})['accessToken']
        index = post('/api/ai/index', {}, token)
        post('/api/ai/diagnoses/index', {}, token)
        # Only approved non-secret result fields, not arbitrary provider/server payloads.
        result = {key:index.get(key) for key in ('indexVersion','documents','chunks','unchanged')}
        Path('/release/ai-index-result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('AI index and diagnosis schema initialized; verify golden/role regression before public access.')
    except Exception:
        print('AI initialization failed; response/credentials suppressed. Inspect private server diagnostics.')
        return 1
    return 0

if __name__ == '__main__': raise SystemExit(main())
