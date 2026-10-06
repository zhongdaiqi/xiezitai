import urllib.request, json, sys

def api(u):
    req = urllib.request.Request(u, headers={'User-Agent': 'curl/8', 'Accept': 'application/vnd.github+json'})
    return json.load(urllib.request.urlopen(req, timeout=30))

out = []
try:
    runs = api('https://api.github.com/repos/zhongdaiqi/xiezitai/actions/runs?per_page=3')
    for r in runs['workflow_runs']:
        out.append(f"{r['id']}  {r['head_sha'][:7]}  {r['status']}/{r['conclusion']}  {r['created_at']}  {r['display_title']}")
except Exception as e:
    out.append('GITHUB ERR: ' + repr(e))

try:
    d = api('https://hub.docker.com/v2/repositories/zhongdaiqi/xiezitai/tags?page_size=10')
    for t in d['results']:
        archs = sorted({i['architecture'] for i in (t.get('images') or [])})
        out.append(f"HUB {t['name']}  {archs}  {t.get('last_updated')}")
except Exception as e:
    out.append('HUB ERR: ' + repr(e))

open(r'E:\xiezitai\tools\ci-status.txt', 'w', encoding='utf-8').write('\n'.join(out))
print('\n'.join(out))
