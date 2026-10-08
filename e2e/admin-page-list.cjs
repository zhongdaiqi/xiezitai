/* 后台「页面」面板：列表 / 搜索 / 筛选 / 分页 / 编辑视图拆分 —— 端到端验证（2026-10-08 改版）
 *
 * 背景：页面面板以前是「上面一串输入框 + 下面一个表格」挤在同一屏，也没有搜索与分页。
 *       现在与文章面板对齐：默认只看到列表，点「+ 新建页面」或行内「编辑」才进编辑视图，
 *       保存成功提示「保存成功：<标题>」并自动回到列表；列表支持关键词搜索与状态筛选，
 *       超过 10 条自动分页（页面数量少，搜索/分页在前端做）。
 *
 * 覆盖：
 *   ① 登录后点「页面」→ 默认落在列表视图（#pv-list 可见、#pv-edit 隐藏、页面编辑器未挂载）
 *   ② 列表渲染种子页面（关于 / 友链），标题列是可点开的 /page/<slug> 链接
 *   ③ 关键词搜索：命中标题 / 命中 slug / 命中正文；搜不到时给空态提示且计数为 0
 *   ④ 状态筛选（已发布 / 未发布）与搜索叠加生效
 *   ⑤ 分页：>10 条时分两页，首/末页按钮禁用态正确，翻页后行数变化
 *   ⑥ 「+ 新建页面」→ 进编辑视图且编辑器此时才挂载；「← 返回列表」回列表
 *   ⑦ 保存 → 提示「保存成功：<标题>」+ 自动回列表 + 新页面出现在列表；行内「删除」能删掉
 *   ⑧ 全程 0 JS 错误、0 意外 HTTP>=400
 *
 * 会临时建 1（UI）+ 12（接口）个页面，跑完全部按 id 删掉，不留垃圾。
 */
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const OUT = 'e2e/out';
const ADMIN = { u: 'xiezitai', p: 'xiexiexie' };
const TAG = 'e2e-pg-' + Date.now();          // 临时页面标题前缀（唯一，便于收紧搜索范围）

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

const errors = [], httpBad = [];
const madeIds = [];                          // 需要清理的页面 id
let uiMadeId = null;                         // UI 新建并删除的那条（用于断言）

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  page.on('dialog', d => d.accept());        // delPage 里的 confirm()
  const log = (...a) => console.log(...a);

  const listVisible = () => page.isVisible('#pv-list');
  const editVisible = () => page.isVisible('#pv-edit');
  const pageEditorMounted = () => page.evaluate(() => !!document.querySelector('#pg-editor .bytemd'));
  const rowTitles = () => page.evaluate(() =>
    [...document.querySelectorAll('#pglist tbody tr')]
      .filter(r => !r.querySelector('#pglist-empty'))       // 空态那一行不是数据行
      .map(r => r.querySelector('td:first-child').textContent.trim()));
  const rowCount = () => page.evaluate(() => {
    const rows = [...document.querySelectorAll('#pglist tbody tr')];
    return rows.filter(r => !r.querySelector('#pglist-empty')).length;
  });
  const pinfo = () => page.evaluate(() => {
    const el = document.querySelector('#pglist-pager .pinfo');
    return el ? el.textContent.trim() : '';
  });
  const pagerBtn = i => '#pglist-pager .pbtns button:nth-child(' + i + ')';   // 1首页 2上一页 3下一页 4末页
  const disabled = i => page.isDisabled(pagerBtn(i));
  const toastText = () => page.evaluate(() => {
    const el = document.getElementById('toast');
    return el ? el.textContent : '';
  });
  /** 输入搜索词（防抖 260ms）并等列表重渲染 */
  const search = async kw => {
    await page.fill('#pg-search', kw);
    await page.waitForTimeout(520);
  };
  const setStatus = async v => {
    await page.selectOption('#pg-filter-status', v);
    await page.waitForTimeout(320);
  };
  /** 直接调后台接口建页面（跳过 UI，用来把列表撑过一页）；建失败要立刻炸，别留下对不上的计数 */
  const apiCreate = async (title, body, published) => {
    const r = await page.evaluate(async a => {
      const res = await api('/api/admin/pages', {
        method: 'POST', body: JSON.stringify({ title: a.title, slug: '', content: a.body, published: a.published })
      });
      let j = null; try { j = await res.json(); } catch (e) {}
      return { status: res.status, id: j && j.id, body: j };
    }, { title, body, published });
    if (!r.id) throw new Error('POST /api/admin/pages 未返回 id：status=' + r.status + ' ' + JSON.stringify(r.body));
    madeIds.push(r.id);
    return r.id;
  };
  const refreshList = () => page.evaluate(() => loadPages());

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
    await page.click('.tab[data-p="pages"]');
    await page.waitForSelector('#p-pages.active', { timeout: 8000 });
    await page.waitForSelector('#pv-list', { state: 'visible', timeout: 10000 });
    await page.waitForFunction(() => {
      const tb = document.querySelector('#pglist tbody');
      return !!tb && tb.children.length > 0;
    }, { timeout: 15000 }).catch(() => log('WAIT_LIST_TIMEOUT'));
    check('① 进「页面」默认落在列表视图（#pv-list 可见）', await listVisible());
    check('① 编辑视图隐藏（#pv-edit 不可见）', !(await editVisible()));
    check('① 列表视图下页面编辑器不预挂载（按需挂载）', !(await pageEditorMounted()));

    // ---------- ② 种子页面 + 标题链接 ----------
    const seedTitles = await rowTitles();
    log('SEED_ROWS=' + JSON.stringify(seedTitles));
    check('② 列表渲染出种子页面（关于 / 友链，>= 2 行）', seedTitles.length >= 2, seedTitles.join(' , '));
    check('② 标题是 /page/<slug> 链接', await page.evaluate(() => {
      const a = document.querySelector('#pglist tbody td:first-child a');
      return !!a && /\/page\//.test(a.getAttribute('href'));
    }));
    check('② 状态列有徽章（已发布 / 未发布）', await page.evaluate(() =>
      document.querySelectorAll('#pglist tbody .badge').length > 0));
    check('② 计数与分页信息已渲染', /共 \d+ 个/.test(await pinfo()), await pinfo());
    await page.screenshot({ path: OUT + '/35-page-list-view.png', fullPage: true });

    // ---------- ③ 关键词搜索 ----------
    await search('友链');
    let hits = await rowTitles();
    check('③ 搜索标题「友链」命中 1 条', hits.length === 1 && hits[0].includes('友链'), hits.join(' , '));

    await search('about');
    hits = await rowTitles();
    log('SEARCH_about=' + JSON.stringify(hits));
    check('③ 搜索 slug「about」命中「关于」', hits.length === 1 && hits[0].includes('关于'), hits.join(' , '));

    await search('关于这个站点');
    hits = await rowTitles();
    check('③ 搜索正文关键词也能命中', hits.some(t => t.includes('关于')), hits.join(' , '));

    await search('zzz-nobody-uses-this-word');
    check('③ 搜不到时给空态提示', await page.evaluate(() => {
      const td = document.querySelector('#pglist-empty');
      return !!td && /没有匹配的页面/.test(td.textContent);
    }));
    check('③ 搜不到时计数为 0', /共 0 个/.test(await pinfo()), await pinfo());

    await search('');
    hits = await rowTitles();
    check('③ 清空搜索后恢复全部', hits.length >= 2, hits.length + '');

    // ---------- ④ 状态筛选 ----------
    const draftId = await apiCreate(TAG + '-草稿页', '# 草稿页面\n\n未发布页面。', false);
    const pubId = await apiCreate(TAG + '-已发布页', '# 已发布页面\n\n已发布页面。', true);
    log('DRAFT_ID=' + draftId + ' PUB_ID=' + pubId);
    // 两个标题 slugify 后 ASCII 部分完全相同（中文被剥掉）→ 后端必须给第二个换个 slug 而不是 500。
    // 顺带验证「显式指定已占用 slug」是 409 + 可读文案，而不是 500。
    // 这一枪从 Node 侧打（不是页面里 fetch）：4xx 会让浏览器记一条 console error，
    // 会污染下面「无 JS 错误」的断言。
    const token = await page.evaluate(() =>
      localStorage.getItem('xz_token') || sessionStorage.getItem('xz_token'));
    const cRes = await fetch(BASE + '/api/admin/pages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
      body: JSON.stringify({ title: 'e2e-slug-conflict', slug: 'about', content: 'x', published: true })
    });
    const cBody = await cRes.json().catch(() => null);
    check('④ 显式指定已占用 slug → 409 + 错误文案（不是 500）',
      cRes.status === 409 && !!(cBody && cBody.error), cRes.status + ' ' + JSON.stringify(cBody));

    await refreshList();
    await search(TAG);                       // 只看本次临时页，隔离种子数据
    const both = await rowTitles();
    check('④ 临时页两条都在（搜索叠加生效）', both.length === 2, both.join(' , '));

    await setStatus('DRAFT');
    hits = await rowTitles();
    check('④ 筛「未发布」只剩草稿页', hits.length === 1 && hits[0].includes('草稿页'), hits.join(' , '));

    await setStatus('PUBLISHED');
    hits = await rowTitles();
    check('④ 筛「已发布」只剩已发布页', hits.length === 1 && hits[0].includes('已发布页'), hits.join(' , '));

    await setStatus('');
    await search('');

    // ---------- ⑤ 分页 ----------
    for (let i = 1; i <= 12; i++) await apiCreate(TAG + '-批量' + String(i).padStart(2, '0'), '# 批量页面 ' + i, true);
    await refreshList();
    await page.waitForTimeout(400);
    // 期望值直接按接口总数算，避免依赖「库里恰好有多少条」
    const total = await page.evaluate(async () => (await jget('/api/admin/pages')).length);
    const totalPages = Math.max(Math.ceil(total / 10), 1);
    log('TOTAL=' + total + ' TOTAL_PAGES=' + totalPages + ' PINFO_P1=' + (await pinfo()));
    check('⑤ 临时页已撑过一页（总数 > 10）', total > 10, 'total=' + total);
    check('⑤ 超 10 条自动分页（共 N 个 · 第 1-10 条 · 1/' + totalPages + ' 页）',
      (await pinfo()) === `共 ${total} 个 · 第 1-10 条 · 1/${totalPages} 页`, await pinfo());
    check('⑤ 第 1 页 10 行', (await rowCount()) === 10, (await rowCount()) + '');
    check('⑤ 首页时「首页 / 上一页」禁用', (await disabled(1)) && (await disabled(2)));
    check('⑤ 首页时「下一页 / 末页」可用', !(await disabled(3)) && !(await disabled(4)));

    await page.click(pagerBtn(3));           // 下一页
    await page.waitForTimeout(400);
    log('PINFO_P2=' + (await pinfo()));
    check('⑤ 点「下一页」→ 第 2 页', (await pinfo()) ===
      `共 ${total} 个 · 第 11-${Math.min(20, total)} 条 · 2/${totalPages} 页`, await pinfo());
    check('⑤ 第 2 页行数 = min(10, 总数-10)', (await rowCount()) === Math.min(10, total - 10), (await rowCount()) + '');
    check('⑤ 末页时「下一页 / 末页」禁用', (await disabled(3)) && (await disabled(4)));

    await page.click(pagerBtn(1));           // 首页
    await page.waitForTimeout(400);
    check('⑤ 点「首页」回到第 1 页', /· 1\//.test(await pinfo()), await pinfo());
    await page.screenshot({ path: OUT + '/36-page-list-pager.png', fullPage: true });

    // 搜索会让页码越界 → 必须自动退回最后一页而不是空白
    await page.click(pagerBtn(4));           // 先到末页
    await page.waitForTimeout(300);
    await search(TAG + '-草稿页');
    await page.waitForTimeout(300);
    check('⑤ 筛选后页码越界能自动回退（仍能看到结果）', (await rowTitles()).length === 1, (await rowTitles()).join(' , '));
    await search('');
    await setStatus('');
    await page.waitForTimeout(300);

    // ---------- ⑥ 新建 → 编辑视图 ----------
    await page.click('#pg-new');
    await page.waitForSelector('#pv-edit', { state: 'visible', timeout: 10000 });
    await page.waitForSelector('#pg-editor .CodeMirror', { timeout: 15000 });
    await page.waitForTimeout(300);
    check('⑥ 点「+ 新建页面」→ 进入编辑视图', (await editVisible()) && !(await listVisible()));
    check('⑥ 进入编辑视图后编辑器才挂载', await pageEditorMounted());
    check('⑥ 模式标签显示「新建页面」', (await page.textContent('#pg-mode')).trim() === '新建页面',
      await page.textContent('#pg-mode'));
    check('⑥ 新建时标题为空', (await page.inputValue('#pg-title')) === '', await page.inputValue('#pg-title'));

    await page.click('#pg-back');            // ← 返回列表
    await page.waitForSelector('#pv-list', { state: 'visible', timeout: 8000 });
    check('⑥ 点「← 返回列表」→ 回到列表视图', (await listVisible()) && !(await editVisible()));

    // ---------- ⑦ 保存 → 提示 + 回列表 ----------
    await page.click('#pg-new');
    await page.waitForSelector('#pv-edit', { state: 'visible', timeout: 8000 });
    const uiTitle = TAG + '-UI新建页';
    await page.fill('#pg-title', uiTitle);
    await page.fill('#pg-slug', '');
    await page.evaluate(v => {
      document.querySelector('#pg-editor .CodeMirror').CodeMirror.setValue('# ' + v + '\n\nUI 新建并保存的页面。');
    }, uiTitle);
    await page.waitForTimeout(300);
    await page.click('#pg-save');
    await page.waitForFunction(() => {
      const el = document.getElementById('toast');
      return !!el && el.classList.contains('show') && /保存成功/.test(el.textContent);
    }, { timeout: 15000 }).catch(() => log('WAIT_TOAST_TIMEOUT'));
    const t = await toastText();
    log('TOAST=' + t);
    check('⑦ 保存后提示「保存成功：<标题>」', /保存成功/.test(t) && t.includes(uiTitle), t);

    await page.waitForSelector('#pv-list', { state: 'visible', timeout: 15000 });
    check('⑦ 保存后自动回到列表视图', (await listVisible()) && !(await editVisible()));
    await search(uiTitle);                   // 用搜索定位（不受排序/分页影响）
    hits = await rowTitles();
    check('⑦ 新页面出现在列表里', hits.length === 1 && hits[0] === uiTitle, hits.join(' , '));

    uiMadeId = await page.evaluate(async a => {
      const list = await jget('/api/admin/pages');
      const p = list.find(x => x.title === a);
      return p ? p.id : null;
    }, uiTitle);
    log('UI_MADE_ID=' + uiMadeId);
    if (uiMadeId) madeIds.push(uiMadeId);

    // 行内「编辑」→ 回填
    await page.click('#pglist tbody tr:first-child button[data-act="edit"]');
    await page.waitForSelector('#pv-edit', { state: 'visible', timeout: 8000 });
    await page.waitForTimeout(600);
    check('⑦ 点行内「编辑」→ 编辑视图并回填', (await page.inputValue('#pg-title')) === uiTitle,
      await page.inputValue('#pg-title'));
    check('⑦ 模式标签显示「编辑页面」', (await page.textContent('#pg-mode')).trim() === '编辑页面',
      await page.textContent('#pg-mode'));
    await page.screenshot({ path: OUT + '/37-page-edit-view.png', fullPage: true });

    // 行内「删除」→ 行消失（confirm 已被 dialog handler 接受）
    await page.click('#pg-back');
    await page.waitForSelector('#pv-list', { state: 'visible', timeout: 8000 });
    await search(uiTitle);
    await page.waitForTimeout(300);
    await page.click('#pglist tbody tr:first-child button[data-act="del"]');
    await page.waitForTimeout(900);
    await search(uiTitle);
    await page.waitForTimeout(300);
    const afterDel = await rowTitles();
    check('⑦ 行内「删除」后该页从列表消失', afterDel.length === 0, afterDel.join(' , '));
    madeIds.splice(madeIds.indexOf(uiMadeId), 1);   // 已删，别再删一次
    uiMadeId = null;
    await search('');

    // ---------- ⑧ 控制台干净 ----------
    check('⑧ 无 JS 错误', errors.length === 0, errors.slice(0, 3).join(' ; '));
    check('⑧ 无意外 HTTP>=400', httpBad.length === 0, httpBad.slice(0, 3).join(' ; '));
  } catch (e) {
    check('用例执行未抛异常', false, String(e && e.message || e).split('\n')[0]);
  } finally {
    // 清理本次建的页面
    try {
      const token = await page.evaluate(() =>
        localStorage.getItem('xz_token') || sessionStorage.getItem('xz_token'));
      for (const id of madeIds) {
        try {
          const r = await fetch(BASE + '/api/admin/pages/' + id,
            { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
          console.log('CLEANUP page id=' + id + ' status=' + r.status);
        } catch (e) { console.log('CLEANUP_FAIL id=' + id + ' ' + (e && e.message)); }
      }
    } catch (e) { console.log('CLEANUP_TOKEN_FAIL ' + (e && e.message)); }
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
