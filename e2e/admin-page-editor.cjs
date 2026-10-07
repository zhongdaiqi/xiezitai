/* 后台「页面」编辑改用与文章一致的 ByteMD 编辑器 —— 端到端验证（2026-10-07 改版）
 *
 * 背景：页面正文以前是纯 <textarea>（#pg-content），**没有编辑器菜单** —— 标题/加粗/列表/
 *       图片/分屏/全屏都用不上。现在与文章共用 ByteMD 组件（挂载点 #pg-editor），
 *       且只在首次进「页面」面板时才挂载（ByteMD 有开销，不必常驻两个实例）。
 *
 * 覆盖：
 *   ① 切到「页面」面板 → #pg-editor 内挂出 ByteMD，工具栏菜单存在
 *   ② 工具栏按钮数 >= 8，且含「图片」「全屏」（与文章编辑器同一套默认工具栏）
 *   ③ 通过 CodeMirror 写入 markdown → 预览区真的渲染（标题/表格）
 *   ④ 「插入媒体」按钮走 openMedia('page')，目标指向页面编辑器（mediaTarget='page'）
 *   ⑤ 保存页面 → 提示「页面已保存」→ 页面出现在列表；再点「编辑」正文能回填
 *   ⑥ 0 JS 错误、0 意外 HTTP>=400
 *
 * 会建 1 个页面（标题带时间戳），跑完按 id 删掉。
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

const errors = [], httpBad = [];
let madeId = null;

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  const log = (...a) => console.log(...a);

  const pageEditorMounted = () => page.evaluate(() => !!document.querySelector('#pg-editor .bytemd'));
  const pgText = () => page.evaluate(() => {
    const cm = document.querySelector('#pg-editor .CodeMirror');
    return cm && cm.CodeMirror ? cm.CodeMirror.getValue() : '';
  });
  const toastText = () => page.evaluate(() => {
    const el = document.getElementById('toast');
    return el ? el.textContent : '';
  });
  /** hover 工具栏图标 → 读 tippy 气泡标题 */
  async function toolLabels(scopeSel) {
    const n = await page.locator(scopeSel + ' .bytemd-toolbar-icon').count();
    const out = [];
    for (let i = 0; i < n; i++) {
      try {
        await page.locator(scopeSel + ' .bytemd-toolbar-icon').nth(i).hover({ timeout: 3000 });
        await page.waitForTimeout(280);
        const tip = await page.evaluate(() => {
          const t = document.querySelector('.tippy-content');
          return t ? (t.textContent || '').trim() : '';
        });
        out.push(tip);
      } catch (e) { out.push(''); }
    }
    return out;
  }

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
    log('WHO=' + (await page.textContent('#who')));

    // 进页面面板之前，页面编辑器还不该挂载
    check('进「页面」面板前页面编辑器未挂载（按需挂载）', !(await pageEditorMounted()));

    // ---------- ① 切到「页面」面板 → 编辑器挂载 ----------
    await page.click('.tab[data-p="pages"]');
    await page.waitForSelector('#p-pages.active', { timeout: 8000 });
    await page.waitForSelector('#pg-editor .CodeMirror', { timeout: 15000 });
    await page.waitForTimeout(400);
    check('① 切到「页面」面板后编辑器已挂载（#pg-editor 内有 ByteMD）', await pageEditorMounted());
    check('① 页面编辑器带工具栏（.bytemd-toolbar 存在）',
      await page.evaluate(() => !!document.querySelector('#pg-editor .bytemd-toolbar')));

    // ---------- ② 工具栏菜单与文章一致 ----------
    const n = await page.locator('#pg-editor .bytemd-toolbar-icon').count();
    log('PG_TOOL_COUNT=' + n);
    check('② 页面编辑器工具栏按钮数 >= 8', n >= 8, n);
    const labels = await toolLabels('#pg-editor');
    log('PG_TOOLS=' + labels.join(' | '));
    check('② 工具栏含「图片」条目', labels.some(l => /image/i.test(l)), labels.join('|'));
    check('② 工具栏含「全屏」条目', labels.some(l => /full\s*screen/i.test(l)), labels.join('|'));

    // ---------- ③ 写入 markdown → 预览渲染 ----------
    const title = 'E2E 页面编辑器 ' + Date.now();
    const md = ['# ' + title, '', '这是页面正文，用来说明页面也有实时预览。', '',
      '| 列A | 列B |', '| --- | --- |', '| 1 | 2 |', ''].join('\n');
    await page.fill('#pg-title', title);
    await page.evaluate(v => {
      document.querySelector('#pg-editor .CodeMirror').CodeMirror.setValue(v);
    }, md);
    await page.waitForTimeout(700);
    const pv = await page.evaluate(() => ({
      h1: document.querySelectorAll('#pg-editor .bytemd-preview .markdown-body h1').length,
      table: document.querySelectorAll('#pg-editor .bytemd-preview .markdown-body table').length
    }));
    log('PG_PREVIEW=' + JSON.stringify(pv));
    check('③ 页面编辑器预览渲染出标题（h1）', pv.h1 >= 1, 'h1=' + pv.h1);
    check('③ 页面编辑器预览渲染出表格（GFM 插件生效）', pv.table >= 1, 'table=' + pv.table);
    await page.screenshot({ path: OUT + '/34-page-editor.png', fullPage: true });

    // ---------- ④ 「插入媒体」指向页面编辑器 ----------
    await page.click('button[onclick="openMedia(\'page\')"]');
    await page.waitForSelector('#media-modal.open', { timeout: 5000 });
    const target = await page.evaluate(() => (typeof mediaTarget !== 'undefined' ? mediaTarget : null));
    check('④ 「插入媒体」弹窗打开', true);
    check('④ 媒体插入目标＝页面编辑器（mediaTarget=page）', target === 'page', String(target));
    await page.click('#media-modal button[onclick="closeMedia()"]');
    await page.waitForFunction(() => !document.getElementById('media-modal').classList.contains('open'),
      { timeout: 5000 }).catch(() => log('WAIT_CLOSE_TIMEOUT'));

    // ---------- ⑤ 保存页面 ----------
    await page.click('button[onclick="savePage()"]');
    await page.waitForFunction(() => {
      const el = document.getElementById('toast');
      return !!el && el.classList.contains('show') && /页面已保存/.test(el.textContent);
    }, { timeout: 15000 }).catch(() => log('WAIT_TOAST_TIMEOUT'));
    check('⑤ 保存后提示「页面已保存」', /页面已保存/.test(await toastText()), await toastText());

    await page.waitForFunction(t => {
      return [...document.querySelectorAll('#pglist tbody tr td:first-child')]
        .some(td => td.textContent.trim() === t);
    }, title, { timeout: 15000 }).catch(() => log('WAIT_PAGE_ROW_TIMEOUT'));
    const listed = await page.evaluate(t => {
      const tr = [...document.querySelectorAll('#pglist tbody tr')]
        .find(x => x.querySelector('td:first-child').textContent.trim() === t);
      return !!tr;
    }, title);
    check('⑤ 新页面出现在页面列表', listed);

    madeId = await page.evaluate(async (t) => {
      const list = await jget('/api/admin/pages');
      const p = list.find(x => x.title === t);
      return p ? p.id : null;
    }, title);
    log('PAGE_ID=' + madeId);

    // 再点「编辑」→ 正文回填到编辑器（验证落库内容完整）
    await page.evaluate(t => {
      const tr = [...document.querySelectorAll('#pglist tbody tr')]
        .find(x => x.querySelector('td:first-child').textContent.trim() === t);
      tr.querySelector('td:last-child button').click();
    }, title);
    await page.waitForTimeout(800);
    check('⑤ 点「编辑」回填标题', (await page.inputValue('#pg-title')) === title, await page.inputValue('#pg-title'));
    check('⑤ 点「编辑」回填正文到页面编辑器', (await pgText()).includes(title), (await pgText()).slice(0, 40));

    // ---------- ⑥ 控制台干净 ----------
    check('⑥ 无 JS 错误', errors.length === 0, errors.slice(0, 3).join(' ; '));
    check('⑥ 无意外 HTTP>=400', httpBad.length === 0, httpBad.slice(0, 3).join(' ; '));
  } catch (e) {
    check('用例执行未抛异常', false, String(e && e.message || e).split('\n')[0]);
  } finally {
    if (madeId) {
      try {
        const token = await page.evaluate(() =>
          localStorage.getItem('xz_token') || sessionStorage.getItem('xz_token'));
        if (token) {
          const r = await fetch(BASE + '/api/admin/pages/' + madeId,
            { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
          console.log('CLEANUP page id=' + madeId + ' status=' + r.status);
        }
      } catch (e) { console.log('CLEANUP_FAIL ' + (e && e.message)); }
    }
    await browser.close();
    const failed = checks.filter(c => !c.ok);
    console.log('\n===== 结果：' + (checks.length - failed.length) + '/' + checks.length + ' 通过 =====');
    if (failed.length) { failed.forEach(f => console.log('  FAILED: ' + f.name)); process.exit(1); }
  }
})().catch(e => {
  console.error('SCRIPT_ERROR', e && e.message ? e.message : e);
  errors.slice(0, 8).forEach(x => console.error('  PAGEERR: ' + x));
  httpBad.slice(0, 8).forEach(x => console.error('  HTTPBAD: ' + x));
  process.exit(2);
});
