/* 后台「写字台」面板：关联账号 / 浏览 / 单篇导入 / 整站导入进度 —— 端到端验证
 *
 * 方案：脚本内起一个本地 mock「对方写字台」HTTP 服务器（127.0.0.1 随机端口），实现开放 API：
 *   - GET /api/v1/articles          文章列表（JSON，需 X-API-Token）
 *   - GET /api/v1/articles/{id}     单篇详情（正文 Markdown 原文，媒体已绝对化）
 *   - GET /media-e2e.png            站点自身媒体（会被下载落盘为本站 /media/）
 *   外站图用 http://cdn.external-xz-e2e.test/（不可达也无妨 —— 外站保留外链，不下载）。
 *   关联时用户填的是「发布接口地址」= MOCK/api/v1/publish，后端要能推导出 /api/v1 根。
 *
 * 覆盖：
 *   ① 「写字台」tab 存在，进面板账号列表为空态
 *   ② 关联账号 → 表格出现该账号；接口列表永不含密钥明文
 *   ③ 浏览文章 → 3 篇渲染，分页「共 3 篇」；发布时间/冲突选择器存在
 *   ④ 单篇导入 → 本站图片落盘 /media/、外站图保留外链、标签与摘要导入、发布时间沿用原时间
 *   ④b 发布时间选「当前时间」导入第二篇 → 发布时间=今天
 *   ④c 重复导入 → 提示跳过
 *   ⑤ 整站导入 → 进度区出现并最终 DONE（成功 1、跳过 2）
 *   ⑥ 全程 0 JS 错误、0 意外 HTTP>=400
 * 收尾：删掉导入的文章与关联账号。
 */
const { chromium } = require('playwright');
const http = require('http');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const OUT = 'e2e/out';
const ADMIN = { u: 'xiezitai', p: 'xiexiexie' };
const XZ_TOKEN = 'e2e-xz-token-0123456789abcdef';
const XZ_USER = 'xz-e2e';
const EXT_IMG = 'http://cdn.external-xz-e2e.test/ext.png';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

const errors = [], httpBad = [];
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=', 'base64');

/* ---------- mock「对方写字台」开放 API ---------- */
let mockBase = '';
const POSTS = [
  { id: 1, slug: 'e2e-xz-post-01', title: 'E2E XZ Post 01', summary: 'XZ 摘要 01',
    tags: 'E2ETag,Java', cover: true, status: 'PUBLISHED', publishedAt: '2026-01-01T08:00:00' },
  { id: 2, slug: 'e2e-xz-post-02', title: 'E2E XZ Post 02', summary: '',
    tags: '', cover: false, status: 'PUBLISHED', publishedAt: '2026-01-01T09:00:00' },
  { id: 3, slug: '中文写字台文章-e2e', title: '中文写字台文章', summary: '',
    tags: '', cover: false, status: 'PUBLISHED', publishedAt: '2026-01-01T10:00:00' },
];

/** 第一篇的正文：本站图（同主机，应落盘）+ 外站图（应保留外链）+ 本站附件 */
function bodyOf(p) {
  if (p.id !== 1) return '正文 ' + p.id;
  return '开头一段\n\n'
    + '![本站图](' + mockBase + '/media-e2e.png)\n\n'
    + '![外站图](' + EXT_IMG + ')\n\n'
    + '<img src="' + mockBase + '/media-e2e.png"/>\n';
}

function briefOf(p) {
  return {
    id: p.id, slug: p.slug, title: p.title, summary: p.summary, tags: p.tags,
    cover: p.cover ? mockBase + '/media-e2e.png' : null,
    publishedAt: p.publishedAt, url: '/article/' + encodeURIComponent(p.slug),
  };
}

function detailOf(p) {
  return Object.assign(briefOf(p), {
    content: bodyOf(p), author: XZ_USER, status: p.status,
  });
}

function startMockXz() {
  const server = http.createServer((req, res) => {
    const path = req.url.split('?')[0];
    if (req.method === 'GET' && path === '/media-e2e.png') {
      res.setHeader('Content-Type', 'image/png');
      return res.end(PNG_1X1);
    }
    if (req.method !== 'GET' || !path.startsWith('/api/v1/articles')) {
      res.statusCode = 404;
      return res.end('not found');
    }
    if (req.headers['x-api-token'] !== XZ_TOKEN) {
      res.statusCode = 401;
      res.setHeader('Content-Type', 'application/json');
      return res.end(JSON.stringify({ error: '无效的 API Token' }));
    }
    res.setHeader('Content-Type', 'application/json');
    const m = /^\/api\/v1\/articles\/(\d+)$/.exec(path);
    if (m) {
      const p = POSTS.find(x => x.id === parseInt(m[1], 10));
      if (!p) { res.statusCode = 404; return res.end(JSON.stringify({ error: '文章不存在' })); }
      return res.end(JSON.stringify(detailOf(p)));
    }
    // 列表：支持 page/size，本项目导入按 size=100 遍历，totalPages 固定 1
    const qs = new URLSearchParams(req.url.split('?')[1] || '');
    const size = Math.min(Math.max(parseInt(qs.get('size') || '20', 10), 1), 100);
    return res.end(JSON.stringify({
      site: mockBase, user: XZ_USER, page: 1, size, total: POSTS.length, totalPages: 1,
      items: POSTS.slice(0, size).map(briefOf),
    }));
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)));
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  page.on('dialog', d => d.accept());
  const log = (...a) => console.log(...a);

  const mock = await startMockXz();
  mockBase = 'http://127.0.0.1:' + mock.address().port;
  log('MOCK_XZ=' + mockBase);

  let siteId = null;
  // 清理本测试创建的所有文章（发布人=xz-e2e）+ 上次残留
  const cleanupArticles = async () => {
    try {
      return await page.evaluate(async ([user]) => {
        const r = await fetch('/api/admin/articles?size=200', { headers: { Authorization: 'Bearer ' + token } });
        const data = await r.json();
        let ok = 0;
        for (const a of (data.content || [])) {
          if (a.author === user || /^e2e-xz-post-|^中文写字台文章-e2e$/.test(a.slug || '')) {
            const dr = await fetch('/api/admin/articles/' + a.id, {
              method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
            if (dr.ok) ok++;
          }
        }
        return ok;
      }, [XZ_USER]);
    } catch (e) { return -1; }
  };

  try {
    // ---------- 登录 ----------
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    if (!(await page.isVisible('#app').catch(() => false))) {
      await page.fill('#luser', ADMIN.u);
      await page.fill('#lpass', ADMIN.p);
      await page.click('#login button');
    }
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
    await cleanupArticles();

    // ---------- ① 进面板：tab 存在、空态 ----------
    check('① 「写字台」tab 存在', await page.locator('.tab[data-p="xz"]').count() === 1);
    await page.click('.tab[data-p="xz"]');
    await page.waitForSelector('#p-xz.active', { timeout: 8000 });
    await page.waitForFunction(() => !!document.querySelector('#xzlist tbody tr'), { timeout: 10000 });
    const emptyTip = await page.textContent('#xzlist tbody');
    check('① 进面板账号列表为空态提示', emptyTip.includes('还没有关联'), emptyTip.trim().slice(0, 30));

    // ---------- ② 关联账号（接口地址填「发布接口」） ----------
    await page.fill('#xz-url', mockBase + '/api/v1/publish');
    await page.fill('#xz-user', XZ_USER);
    await page.fill('#xz-token', XZ_TOKEN);
    await page.click('#xz-add');
    await page.waitForFunction(() =>
      document.querySelectorAll('#xzlist tbody tr').length > 0
      && !document.querySelector('#xzlist tbody').textContent.includes('还没有关联'),
      { timeout: 15000 });
    const rowText = await page.textContent('#xzlist tbody tr');
    check('② 关联后账号出现在列表', rowText.includes(XZ_USER), rowText.slice(0, 60));
    const sitesJson = await page.evaluate(async () => {
      const r = await fetch('/api/admin/xz/sites', { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    });
    check('② 账号列表不回显密钥明文字段',
      Array.isArray(sitesJson) && sitesJson.every(s => !('apiToken' in s) && !('token' in s)),
      JSON.stringify(sitesJson).slice(0, 140));
    check('② hasToken=true 且列表不含密钥字符串',
      sitesJson.length === 1 && sitesJson[0].hasToken === true && !JSON.stringify(sitesJson).includes(XZ_TOKEN));
    siteId = sitesJson.length ? sitesJson[0].id : null;

    // ---------- ③ 浏览文章 ----------
    await page.getByRole('button', { name: '浏览文章', exact: true }).click();
    await page.waitForFunction(() =>
      document.querySelectorAll('#xzposts tbody tr').length > 0, { timeout: 15000 });
    const pagerInfo = await page.textContent('#xzposts-pager .pinfo');
    check('③ 浏览到 mock 站点文章（共 3 篇）', /共 3 篇/.test(pagerInfo), pagerInfo.trim());
    check('③ 每行有「导入」按钮', await page.evaluate(() =>
      document.querySelectorAll('#xzposts tbody button').length === 3));
    check('③ 发布时间选择器默认「用对方原发布时间」', await page.evaluate(() => {
      const s = document.getElementById('xz-date-mode');
      return !!s && s.value === 'src' && s.options.length === 2;
    }));
    check('③ 冲突选择器默认「跳过」', await page.evaluate(() => {
      const s = document.getElementById('xz-conflict');
      return !!s && s.value === 'skip' && s.options.length === 2;
    }));
    await page.screenshot({ path: OUT + '/54-xz-browse.png', fullPage: true });

    // ---------- ④ 单篇导入 ----------
    const importRespPromise = page.waitForResponse(
      r => r.url().includes('/import') && !r.url().includes('import-all') && r.request().method() === 'POST',
      { timeout: 60000 });
    await page.locator('#xzposts tbody tr').first().getByRole('button', { name: '导入', exact: true }).click();
    const importResp = await importRespPromise;
    const importData = await importResp.json();
    check('④ 单篇导入接口 imported=true', importData.imported === true, JSON.stringify(importData).slice(0, 140));
    await page.waitForFunction(() =>
      document.getElementById('toast') && document.getElementById('toast').textContent.includes('导入成功'),
      { timeout: 8000 });
    check('④ 提示「导入成功」', true);
    check('④ 无媒体下载假警告（落盘后的本站 /media/ 不会被二次当远端资源下载）',
      Array.isArray(importData.warnings) && importData.warnings.length === 0,
      JSON.stringify(importData.warnings || []).slice(0, 160));

    const art = await page.evaluate(async id => {
      const r = await fetch('/api/admin/articles/' + id, { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    }, importData.articleId);
    check('④ 发布人=关联时填写的写字台账号', art.author === XZ_USER, art.author);
    check('④ 本站媒体已落盘为 /media/', art.content.includes('/media/'),
      (art.content.match(/\/media\/[\w.]+/g) || []).join(','));
    check('④ 正文无 mock 主机 URL 残留', !art.content.includes('127.0.0.1'));
    check('④ 外站图保留外链', art.content.includes(EXT_IMG));
    check('④ 标签已导入', (art.tags || '').includes('E2ETag'), art.tags);
    check('④ 摘要已导入', (art.summary || '') === 'XZ 摘要 01', art.summary);
    check('④ 发布时间沿用对方原时间', (art.publishedAt || '').startsWith('2026-01-01T08:00'), art.publishedAt);
    check('④ 封面已落盘为站内地址', (art.cover || '').startsWith('/media/'), art.cover);

    // ---------- ④b 发布时间选「当前时间」导入第二篇 ----------
    await page.selectOption('#xz-date-mode', 'now');
    const importResp2Promise = page.waitForResponse(
      r => r.url().includes('/import') && !r.url().includes('import-all') && r.request().method() === 'POST',
      { timeout: 60000 });
    await page.locator('#xzposts tbody tr').nth(1).getByRole('button', { name: '导入', exact: true }).click();
    const importData2 = await (await importResp2Promise).json();
    const art2 = await page.evaluate(async id => {
      const r = await fetch('/api/admin/articles/' + id, { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    }, importData2.articleId);
    const today = new Date().toISOString().slice(0, 10);
    check('④b 选「当前时间」后发布时间=今天', (art2.publishedAt || '').startsWith(today), art2.publishedAt);
    await page.selectOption('#xz-date-mode', 'src');

    // ---------- ④c 重复导入 → 跳过 ----------
    const dupRespPromise = page.waitForResponse(
      r => r.url().includes('/import') && !r.url().includes('import-all') && r.request().method() === 'POST',
      { timeout: 60000 });
    await page.locator('#xzposts tbody tr').first().getByRole('button', { name: '导入', exact: true }).click();
    const dupData = await (await dupRespPromise).json();
    check('④c 重复导入 imported=false（已跳过）', dupData.imported === false, JSON.stringify(dupData).slice(0, 120));

    // ---------- ⑤ 整站导入 ----------
    await page.click('#xz-import-all');
    await page.waitForFunction(() =>
      document.getElementById('xz-progress') && document.getElementById('xz-progress').style.display !== 'none',
      { timeout: 10000 });
    await page.waitForFunction(() => {
      const t = document.getElementById('xz-progress-text');
      return t && /已完成|已中断/.test(t.textContent);
    }, { timeout: 90000 });
    const progText = await page.textContent('#xz-progress-text');
    check('⑤ 整站导入进度到「已完成」', /已完成/.test(progText), progText.trim());
    const progLog = await page.textContent('#xz-progress-log');
    check('⑤ 日志含「整站导入完成」', progLog.includes('整站导入完成'), progLog.trim().split('\n').pop());
    const prog = await page.evaluate(async id => {
      const r = await fetch('/api/admin/xz/sites/' + id + '/progress', { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    }, siteId);
    check('⑤ 进度统计：总数 3', prog.total === 3, 'total=' + prog.total);
    check('⑤ 进度统计：成功 1、跳过 2、失败 0',
      prog.imported === 1 && prog.skipped === 2 && prog.failed === 0,
      'imported=' + prog.imported + ' skipped=' + prog.skipped + ' failed=' + prog.failed);
    check('⑤ 中文 slug 文章也导入成功', await page.evaluate(async () => {
      const r = await fetch('/api/admin/articles?size=200', { headers: { Authorization: 'Bearer ' + token } });
      const data = await r.json();
      return (data.content || []).some(a => a.title === '中文写字台文章');
    }));
    await page.screenshot({ path: OUT + '/55-xz-progress.png', fullPage: true });

    // ---------- ⑥ 无 JS 错误 / 无意外 4xx-5xx ----------
    const realBad = httpBad.filter(u => !/\/import|401/.test(u));
    check('⑥ 无 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
    check('⑥ 无意外 HTTP>=400', realBad.length === 0, realBad.slice(0, 3).join(' | '));
  } finally {
    try { await cleanupArticles(); } catch (e) { /* 忽略 */ }
    if (siteId) {
      try {
        await page.evaluate(async id => {
          await fetch('/api/admin/xz/sites/' + id, { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
        }, siteId);
      } catch (e) { /* 忽略 */ }
    }
    mock.close();
    await browser.close();
  }

  const passed = checks.filter(c => c.ok).length;
  console.log('\n结果：' + passed + '/' + checks.length + ' 通过');
  if (passed !== checks.length) {
    console.log('失败项：');
    checks.filter(c => !c.ok).forEach(c => console.log('  - ' + c.name + (c.extra !== undefined ? '  [' + c.extra + ']' : '')));
    process.exit(1);
  }
})();
