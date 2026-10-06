import json, urllib.request, urllib.error, time, os

BASE = os.environ.get('XZ_BASE', 'http://localhost:8080')

def call(path, method='GET', body=None, token=None, timeout=180):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')

# 1. 登录
s, b = call('/api/auth/login', 'POST', {'username': 'xiezitai', 'password': 'xiexiexie'})
print('[1] login ->', s)
token = json.loads(b)['token']

# 2. 读取后台设置里的 AI 配置
s, b = call('/api/admin/settings', token=token)
cfg = json.loads(b)
print('[2] settings ->', s)
for k in ['ai.baseUrl', 'ai.model', 'ai.imageModel', 'ai.apiKey']:
    print('     ', k, '=', cfg.get(k))

# 3. AI 摘要（文本模型实调）
t0 = time.time()
s, b = call('/api/admin/ai/summary', 'POST', {
    'text': '写字台是一个基于 Spring Boot 3 的自托管博客系统，支持 Markdown 写作、评论、媒体库、'
            '开放 API 与 MCP、企业微信通知和防篡改基线。它内置 JWT 登录与 TOTP 两步验证，'
            '并提供 AI 润色、摘要与封面图生成能力。'
}, token=token, timeout=180)
print('[3] ai/summary -> %s (%.1fs)' % (s, time.time() - t0))
try:
    print('     result =', json.loads(b).get('result'))
except Exception:
    print('     body =', b[:300])

# 4. AI 封面图（文生图，ModelScope 异步任务）
t0 = time.time()
s, b = call('/api/admin/ai/cover', 'POST', {'prompt': '写字台博客系统封面图，简洁现代，蓝色主题，桌面与笔记本'},
            token=token, timeout=300)
print('[4] ai/cover -> %s (%.1fs)' % (s, time.time() - t0))
try:
    d = json.loads(b)
    print('     coverUrl =', d.get('coverUrl'))
except Exception:
    print('     body =', b[:300])
