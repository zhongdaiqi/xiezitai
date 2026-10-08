/* 后台「开放 API / MCP」配置示例 + 一键复制 + 列表图标按钮 —— 端到端验证
 *
 * 覆盖：
 *   ① 设置页的「开放 API / MCP」卡片把当前账号的 Token 与站点地址实时填进示例（用户不用手抄）
 *   ② MCP 配置按客户端切换：通用 HTTP（Cursor / VS Code / Claude Code）、
 *      Claude Desktop（mcp-remote 桥接）、curl 自测 —— 三份都能生成、都带 Token
 *   ③ 三个「复制」按钮真的写入剪贴板（读回来逐字比对），并给出成功反馈
 *   ④ 复制出来的配置是可用的：拿卡片上显示的 Token 调 /api/v1/mcp tools/list → 3 个工具
 *   ⑤ 列表行内操作已改成图标按钮：有 svg、有 aria-label、有 data-act；删除按钮是 danger 样式
 *   ⑥ 图标按钮不影响功能：点「编辑」图标仍能进编辑视图并回填
 *   ⑦ 全程 0 JS 错误
 * 收尾：删掉建的页面与上传的文件。
 */
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const OUT = 'e2e/out';
const ADMIN = { u: 'xiezitai', p: 'xiexiexie' };

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const ctx = await browser.newContext({
    viewport: { width: 1180, height: 1400 },
    permissions: ['clipboard-read', 'clipboard-write']
  });
  const page = await ctx.newPage();
  const errors = [], httpBad = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  page.on('dialog', d => d.accept());

  let pageId = null, fileId = null;

  try {
    /* ---------- 登录 ---------- */
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    await page.waitForTimeout(900);
    if (!(await page.isVisible('#app').catch(() => false))) {
      await page.fill('#luser', ADMIN.u);
      await page.fill('#lpass', ADMIN.p);
      await page.click('#login button');
    }
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
    const jwt = await page.evaluate(() =>
      localStorage.getItem('xz_token') || sessionStorage.getItem('xz_token'));
    const api = async (path, body, method = 'POST') => {
      const r = await fetch(BASE + path, {
        method,
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + jwt },
        body: body === undefined ? undefined : JSON.stringify(body)
      });
      const t = await r.text();
      if (!r.ok) throw new Error(path + ' -> ' + r.status + ' ' + t.slice(0, 160));
      return t ? JSON.parse(t) : null;
    };

    /* ---------- 切到设置面板 ---------- */
    await page.click('.tab[data-p="settings"]');
    await page.waitForSelector('#apitoken', { state: 'visible', timeout: 8000 });
    await page.waitForTimeout(400);

    /* ---------- ① Token 与站点地址实时填进示例 ---------- */
    const c0 = await page.evaluate(() => ({
      token: document.getElementById('apitoken').textContent.trim(),
      mcpUrl: document.getElementById('mcp-url').textContent.trim(),
      rest: document.getElementById('api-example').textContent,
      cfg: document.getElementById('mcp-cfg').textContent,
      client: document.getElementById('mcp-client').value,
      hint: document.getElementById('mcp-hint').textContent
    }));
    check('① API Token 已显示（非空、非占位）', c0.token.length >= 16 && !c0.token.includes('未生成'),
      c0.token.slice(0, 8) + '…');
    check('① MCP 接口地址指向 /api/v1/mcp', c0.mcpUrl.endsWith('/api/v1/mcp'), c0.mcpUrl);
    check('① REST 示例里带上了真实 Token', c0.rest.includes('X-API-Token: ' + c0.token), '');
    check('① REST 示例是可直接跑的 curl（含 -X POST / /api/v1/publish）',
      /curl -X POST/.test(c0.rest) && c0.rest.includes('/api/v1/publish'), '');
    check('① 默认客户端是「通用 HTTP」', c0.client === 'http', c0.client);
    check('① 通用 HTTP 配置带 type/url/headers 且含 Token',
      /"type": "http"/.test(c0.cfg) && c0.cfg.includes(c0.mcpUrl) && c0.cfg.includes(c0.token), '');
    check('① 配置提示里说明了各客户端的配置文件名',
      /mcp\.json/.test(c0.hint), c0.hint.slice(0, 60));

    /* ---------- ② 切换客户端 ---------- */
    await page.selectOption('#mcp-client', 'claude');
    await page.waitForTimeout(200);
    const claude = await page.evaluate(() => ({
      cfg: document.getElementById('mcp-cfg').textContent,
      hint: document.getElementById('mcp-hint').textContent
    }));
    check('② Claude Desktop 配置走 mcp-remote 桥接（command/args 形态）',
      /"command": "npx"/.test(claude.cfg) && claude.cfg.includes('mcp-remote')
      && claude.cfg.includes('--header') && claude.cfg.includes('X-API-Token:' + c0.token), '');
    check('② Claude 提示里给了 claude_desktop_config.json 路径',
      /claude_desktop_config\.json/.test(claude.hint), claude.hint.slice(0, 60));

    await page.selectOption('#mcp-client', 'curl');
    await page.waitForTimeout(200);
    const curl = await page.evaluate(() => document.getElementById('mcp-cfg').textContent);
    check('② curl 示例是 tools/list 自测命令',
      /curl -X POST/.test(curl) && curl.includes('tools/list') && curl.includes(c0.token), '');

    await page.selectOption('#mcp-client', 'http');
    await page.waitForTimeout(200);
    await page.screenshot({ path: OUT + '/53-mcp-config.png', fullPage: true });

    /* ---------- ③ 一键复制（读剪贴板比对） ----------
       ⚠️ Windows 剪贴板会把 LF 规范成 CRLF，所以比对前统一把换行归一 —— 否则
       「配置内容对得上但长度差 8」会误报（差的就是那 8 个换行符）。 */
    const readClip = async () => (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n');
    const norm = s => s.replace(/\r\n/g, '\n');

    await page.getByRole('button', { name: '复制配置', exact: true }).click();
    await page.waitForTimeout(300);
    const cfgText = await page.evaluate(() => document.getElementById('mcp-cfg').textContent);
    const clip1 = await readClip();
    check('③ 「复制配置」把整份 MCP 配置写进剪贴板（逐字一致）', clip1 === norm(cfgText),
      clip1.length + ' vs ' + cfgText.length);
    check('③ 复制后有成功反馈',
      (await page.textContent('#mcp-cpmsg')).includes('已复制') ||
      (await page.textContent('#toast')).includes('已复制'), await page.textContent('#mcp-cpmsg'));

    await page.getByRole('button', { name: '复制示例', exact: true }).click();
    await page.waitForTimeout(300);
    const clip2 = await readClip();
    check('③ 「复制示例」复制的是 REST curl 示例',
      clip2 === norm(c0.rest) && /api\/v1\/publish/.test(clip2), clip2.slice(0, 40));

    await page.getByRole('button', { name: '复制 Token', exact: true }).click();
    await page.waitForTimeout(300);
    const clip3 = await readClip();
    check('③ 「复制 Token」复制的是 Token 本身', clip3 === c0.token, clip3.slice(0, 8) + '…');
    check('③ Token 复制有反馈', (await page.textContent('#tkmsg')).includes('已复制'),
      await page.textContent('#tkmsg'));

    /* ---------- ④ 复制出来的 Token 真能用 ---------- */
    const mcp = await (await fetch(BASE + '/api/v1/mcp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-API-Token': c0.token },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' })
    })).json();
    const toolNames = ((mcp.result || {}).tools || []).map(t => t.name);
    check('④ 用卡片上的 Token 调 tools/list 成功，返回 3 个工具',
      toolNames.length === 3, toolNames.join(','));

    /* ---------- ⑤ 列表图标按钮 ---------- */
    // 文章列表：分发 / 编辑 / 删除 三个图标
    await page.click('.tab[data-p="articles"]');
    await page.waitForSelector('#alist tbody tr', { timeout: 15000 });
    const art = await page.evaluate(() => {
      const tr = document.querySelector('#alist tbody tr');
      const btns = [...tr.querySelectorAll('td:last-child button')];
      return {
        n: btns.length,
        acts: btns.map(b => b.getAttribute('data-act')),
        labels: btns.map(b => b.getAttribute('aria-label')),
        titles: btns.map(b => b.getAttribute('title')),
        sizes: btns.map(b => Math.round(b.getBoundingClientRect().width)),
        hasSvg: btns.every(b => !!b.querySelector('svg')),
        noText: btns.every(b => b.textContent.trim() === ''),
        danger: btns.map(b => b.classList.contains('danger')),
        big: btns.map(b => Math.round(b.getBoundingClientRect().height))
      };
    });
    check('⑤ 文章行有 3 个行内操作按钮', art.n === 3, 'n=' + art.n);
    check('⑤ 分别是 分发 / 编辑 / 删除（data-act）',
      art.acts.join(',') === 'dist,edit,del', art.acts.join(','));
    check('⑤ 图标按钮不含文字、内含 svg', art.noText && art.hasSvg, JSON.stringify(art.noText));
    check('⑤ 每个按钮都有中文 title 与 aria-label（无障碍名称）',
      art.labels.every(Boolean) && art.titles.every(Boolean), art.labels.join(' | '));
    check('⑤ 删除按钮是危险样式（danger）', art.danger.join(',') === 'false,false,true', art.danger.join(','));
    check('⑤ 按钮是紧凑方形（≈30px，操作列不再被文字撑宽）',
      art.sizes.every(w => w <= 34) && art.big.every(h => h <= 34),
      'w=' + art.sizes.join('/') + ' h=' + art.big.join('/'));

    // 图标按钮仍可用：点「编辑」→ 进编辑视图并回填
    await page.click('#alist tbody tr:first-child button[data-act="edit"]');
    await page.waitForSelector('#ev-edit', { state: 'visible', timeout: 8000 });
    check('⑥ 点「编辑」图标仍能进编辑视图并回填标题',
      (await page.inputValue('#a-title')).trim().length > 0, await page.inputValue('#a-title'));
    await page.click('#a-back');
    await page.waitForSelector('#av-list', { state: 'visible', timeout: 8000 });

    // 页面列表：编辑 / 删除
    await page.click('.tab[data-p="pages"]');
    await page.click('#pg-new');
    await page.waitForSelector('#pv-edit', { state: 'visible', timeout: 8000 });
    const pgTitle = 'E2E 图标按钮页面 ' + Date.now();
    await page.fill('#pg-title', pgTitle);
    await page.click('#pg-save');
    await page.waitForSelector('#pv-list', { state: 'visible', timeout: 8000 });
    await page.waitForTimeout(500);
    const pg = await page.evaluate(t => {
      const tr = [...document.querySelectorAll('#pglist tbody tr')]
        .find(r => r.querySelector('td:first-child').textContent.includes(t));
      if (!tr) return { acts: [], labels: [], hasSvg: false, noText: false };
      const btns = [...tr.querySelectorAll('td:last-child button')];
      return {
        acts: btns.map(b => b.getAttribute('data-act')),
        labels: btns.map(b => b.getAttribute('aria-label')),
        hasSvg: btns.length > 0 && btns.every(b => !!b.querySelector('svg')),
        noText: btns.length > 0 && btns.every(b => b.textContent.trim() === '')
      };
    }, pgTitle);
    check('⑤ 页面行是 编辑 / 删除 两个图标按钮',
      pg.acts.join(',') === 'edit,del' && pg.hasSvg && pg.noText, JSON.stringify(pg.acts));
    check('⑤ 页面按钮带中文提示', pg.labels.join('|') === '编辑|删除', pg.labels.join('|'));

    // 文件列表：删除（顺带验证上传 → 行出现图标按钮 → 图标删除可用）
    await page.click('.tab[data-p="files"]');
    await page.waitForTimeout(300);
    const up = await page.evaluate(async (jwt) => {
      const fd = new FormData();
      const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, 196, 137]);
      fd.append('file', new Blob([bytes], { type: 'image/png' }), 'e2e-icon-btn.png');
      const r = await fetch('/api/admin/files/upload', { method: 'POST', body: fd, headers: { Authorization: 'Bearer ' + jwt } });
      return r.ok ? await r.json() : { error: r.status };
    }, jwt);
    check('⑤ 上传一张测试图片成功', !!up.id, JSON.stringify(up).slice(0, 120));
    fileId = up.id || null;
    if (fileId) {
      await page.evaluate(() => loadFiles());
      await page.waitForTimeout(800);
      const fl = await page.evaluate(() => {
        const tr = [...document.querySelectorAll('#flist tbody tr')]
          .find(r => r.textContent.includes('e2e-icon-btn.png'));
        if (!tr) return null;
        const b = tr.querySelector('td:last-child button');
        return { act: b.getAttribute('data-act'), label: b.getAttribute('aria-label'),
                 svg: !!b.querySelector('svg'), danger: b.classList.contains('danger'),
                 noText: b.textContent.trim() === '' };
      });
      check('⑤ 文件行是「删除」图标按钮（危险样式）',
        !!fl && fl.act === 'del' && fl.label === '删除' && fl.svg && fl.danger && fl.noText,
        JSON.stringify(fl));
    }

    /* ---------- 清理 ---------- */
    const pagesList = await api('/api/admin/pages', undefined, 'GET');
    for (const p of pagesList.filter(x => /E2E 图标按钮页面/.test(x.title))) {
      await api('/api/admin/pages/' + p.id, undefined, 'DELETE');
      pageId = p.id;
    }
    if (fileId) await api('/api/admin/files/' + fileId, undefined, 'DELETE');
  } catch (e) {
    check('用例执行未抛异常', false, e.message);
    console.error(e);
  } finally {
    const realErrors = errors.filter(x => !/favicon|401/.test(x));
    const realHttpBad = httpBad.filter(x => !/401|favicon/.test(x));
    check('⑦ 无 JS 错误', realErrors.length === 0, realErrors.join(' ;; '));
    check('⑦ 无意外 HTTP>=400', realHttpBad.length === 0, realHttpBad.join(' ;; '));
    await browser.close();
  }

  const failed = checks.filter(c => !c.ok);
  console.log('\n===== 结果：' + (checks.length - failed.length) + '/' + checks.length + ' 通过 =====');
  process.exit(failed.length ? 2 : 0);
})();
