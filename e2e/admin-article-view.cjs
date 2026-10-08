/* 后台「文章」面板：列表视图 / 编辑视图拆分 —— 端到端验证（2026-10-07 改版）
 *
 * 需求：文章列表与「发布/编辑文章」不要混在一个界面。
 *   ① 登录后默认落在**列表视图**（#av-list 可见、#ev-edit 隐藏），编辑器不预挂载
 *   ② 点「+ 新建文章」才进**编辑视图**（#ev-edit 可见、#av-list 隐藏），编辑器此时才挂载
 *   ③ 保存成功后顶部提示「保存成功：<标题>」→ 自动回到列表，且刚保存的文章在列表首条
 *   ④ 列表行内「编辑」→ 进编辑视图并回填标题/正文；「← 返回列表」回到列表
 *   ⑤ 全程 0 JS 错误、0 意外 HTTP>=400
 *
 * 会建 1 篇文章（标题带时间戳），跑完按 id 删掉，不留垃圾。
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

  const listVisible = () => page.isVisible('#av-list');
  const editVisible = () => page.isVisible('#ev-edit');
  /** 文章编辑器（ByteMD）是否已挂载到 #editor */
  const articleEditorMounted = () => page.evaluate(() => !!document.querySelector('#editor .bytemd'));
  const firstRowTitle = () => page.evaluate(() => {
    const a = document.querySelector('#alist tbody td:first-child a');
    return a ? a.textContent.trim() : '';
  });
  const editorText = () => page.evaluate(() => {
    const cm = document.querySelector('#editor .CodeMirror');
    return cm && cm.CodeMirror ? cm.CodeMirror.getValue() : '';
  });
  const toastText = () => page.evaluate(() => {
    const el = document.getElementById('toast');
    return el ? el.textContent : '';
  });

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

    // ---------- ① 默认落在列表视图 ----------
    await page.waitForSelector('#av-list', { state: 'visible', timeout: 15000 });
    // 等列表渲染完（有文章时是数据行，空库时是空态行——两者都算渲染完成）
    await page.waitForFunction(() => {
      const tb = document.querySelector('#alist tbody');
      return !!tb && tb.children.length > 0;
    }, { timeout: 15000 }).catch(() => log('WAIT_LIST_TIMEOUT'));
    check('① 登录后默认落在列表视图（#av-list 可见）', await listVisible());
    check('① 列表视图下编辑视图隐藏（#ev-edit 不可见）', !(await editVisible()));
    check('① 列表视图下文章编辑器尚未挂载（按需挂载，不预加载）', !(await articleEditorMounted()));
    await page.screenshot({ path: OUT + '/30-article-list-view.png', fullPage: true });

    // ---------- ② 点「+ 新建文章」进编辑视图 ----------
    await page.click('#a-new');
    await page.waitForSelector('#ev-edit', { state: 'visible', timeout: 10000 });
    await page.waitForSelector('#editor .CodeMirror', { timeout: 15000 });
    await page.waitForTimeout(300);
    check('② 点「+ 新建文章」→ 进入编辑视图', await editVisible());
    check('② 编辑视图下列表隐藏（两视图互斥）', !(await listVisible()));
    check('② 进入编辑视图后编辑器已挂载', await articleEditorMounted());
    check('② 新建时标题为空', (await page.inputValue('#a-title')) === '', await page.inputValue('#a-title'));
    check('② 新建时正文为空', (await editorText()) === '');
    await page.screenshot({ path: OUT + '/31-article-edit-view.png', fullPage: true });

    // ---------- ③ 保存 → 提示「保存成功」→ 回列表 ----------
    const title = 'E2E 视图拆分 ' + Date.now();
    const md = '# ' + title + '\n\n正文用于验证「保存后回到列表」。';
    await page.fill('#a-title', title);
    await page.evaluate(v => {
      document.querySelector('#editor .CodeMirror').CodeMirror.setValue(v);
    }, md);
    await page.selectOption('#a-status', 'PUBLISHED');
    await page.waitForTimeout(300);

    await page.click('#a-save');
    // toast 只显示 2.2s —— 用 waitForFunction 抢在它消失前抓到
    await page.waitForFunction(() => {
      const el = document.getElementById('toast');
      return !!el && el.classList.contains('show') && /保存成功/.test(el.textContent);
    }, { timeout: 15000 }).catch(() => log('WAIT_TOAST_TIMEOUT'));
    const t = await toastText();
    log('TOAST=' + t);
    check('③ 保存后弹出「保存成功」提示', /保存成功/.test(t), t);
    check('③ 提示里带上刚保存的标题', t.includes(title), t);

    await page.waitForSelector('#av-list', { state: 'visible', timeout: 15000 });
    check('③ 保存后自动回到列表视图', (await listVisible()) && !(await editVisible()));
    await page.waitForFunction(t => {
      const a = document.querySelector('#alist tbody td:first-child a');
      return !!a && a.textContent.trim() === t;
    }, title, { timeout: 15000 }).catch(() => log('WAIT_NEW_ROW_TIMEOUT'));
    check('③ 刚保存的文章出现在列表首条', (await firstRowTitle()) === title, await firstRowTitle());
    await page.screenshot({ path: OUT + '/32-article-list-after-save.png', fullPage: true });

    madeId = await page.evaluate(async (t) => {
      const p = await jget('/api/admin/articles?size=100');
      const a = p.content.find(x => x.title === t);
      return a ? a.id : null;
    }, title);
    log('MADE_ID=' + madeId);

    // ---------- ④ 列表行内「编辑」→ 编辑视图并回填 ----------
    // 行内操作已改成图标按钮，用 data-act 定位（不再靠按钮位置，操作列增删按钮也不会错位）
    await page.click('#alist tbody tr:first-child button[data-act="edit"]');
    await page.waitForSelector('#ev-edit', { state: 'visible', timeout: 10000 });
    await page.waitForTimeout(600);
    check('④ 点行内「编辑」→ 进入编辑视图', (await editVisible()) && !(await listVisible()));
    check('④ 编辑载入标题', (await page.inputValue('#a-title')) === title, await page.inputValue('#a-title'));
    check('④ 编辑载入正文', (await editorText()).includes(title), (await editorText()).slice(0, 40));
    check('④ 模式标签显示「编辑文章」', (await page.textContent('#a-mode')).trim() === '编辑文章',
      await page.textContent('#a-mode'));

    // ---------- ⑤ 「← 返回列表」 ----------
    await page.click('#a-back');
    await page.waitForSelector('#av-list', { state: 'visible', timeout: 8000 });
    await page.waitForTimeout(200);
    check('⑤ 点「← 返回列表」→ 回到列表视图', (await listVisible()) && !(await editVisible()));

    // ---------- ⑥ 控制台干净 ----------
    check('⑥ 无 JS 错误', errors.length === 0, errors.slice(0, 3).join(' ; '));
    check('⑥ 无意外 HTTP>=400', httpBad.length === 0, httpBad.slice(0, 3).join(' ; '));
  } catch (e) {
    check('用例执行未抛异常', false, String(e && e.message || e).split('\n')[0]);
  } finally {
    // 清理：删掉本次新建的文章
    if (madeId) {
      try {
        const token = await page.evaluate(() =>
          localStorage.getItem('xz_token') || sessionStorage.getItem('xz_token'));
        if (token) {
          const r = await fetch(BASE + '/api/admin/articles/' + madeId,
            { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
          console.log('CLEANUP id=' + madeId + ' status=' + r.status);
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
