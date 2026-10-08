/* 后台「文章」标签功能 —— 端到端验证（2026-10-08 新增）
 *
 * 需求：文章增加标签，每篇最多 10 个。
 *   ① 编辑视图有 chip 标签输入（#a-tagbox）：输入 + 回车/逗号提交一个 chip，计数同步 n/10
 *   ② 重复标签被拦（提示已存在），计数不变
 *   ③ 超过 10 个被拦（提示最多），前端加不进第 11 个
 *   ④ 保存 → 「保存成功」→ 回列表，列表行的「标签」列出现徽章
 *   ⑤ 后台列表搜索能按标签命中（关键词只在 tags 里）
 *   ⑥ 再点「编辑」chip 回填；删一个 chip 保存 → 列表徽章同步减少
 *   ⑦ 前台文章页 /article/<slug> 渲染标签徽章
 *   ⑧ 全程 0 JS 错误、0 意外 HTTP>=400
 *
 * 会建 1 篇文章（标题带时间戳，发布），跑完按 id 删掉。
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
let madeId = null, madeSlug = null;

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  const log = (...a) => console.log(...a);

  const chipCount = () => page.locator('#a-tagbox .tagchip').count();
  const tagCounterText = () => page.textContent('#a-tagcount');
  const toastText = () => page.evaluate(() => {
    const el = document.getElementById('toast');
    return el ? el.textContent : '';
  });
  const addTag = async (text) => {
    await page.fill('#a-tag-input', text);
    await page.press('#a-tag-input', 'Enter');
  };
  /** 列表里目标文章行的「标签」列文本 */
  const rowTagsText = (t) => page.evaluate(title => {
    const tr = [...document.querySelectorAll('#alist tbody tr')]
      .find(x => x.querySelector('td:first-child a') &&
        x.querySelector('td:first-child a').textContent.trim() === title);
    return tr ? tr.querySelector('td:nth-child(2)').textContent.trim() : '';
  }, t);
  const searchList = async (kw) => {
    await page.fill('#a-search', kw);
    await page.press('#a-search', 'Enter');
    await page.waitForTimeout(600);
  };

  try {
    // ---------- 登录 → 进编辑视图 ----------
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    if (!(await page.isVisible('#app').catch(() => false))) {
      await page.fill('#luser', ADMIN.u);
      await page.fill('#lpass', ADMIN.p);
      await page.click('#login button');
    }
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
    await page.waitForSelector('#av-list', { state: 'visible', timeout: 15000 });
    await page.click('#a-new');
    await page.waitForSelector('#ev-edit', { state: 'visible', timeout: 10000 });
    await page.waitForSelector('#editor .CodeMirror', { timeout: 15000 });
    await page.waitForTimeout(300);

    const title = 'E2E 标签 ' + Date.now();
    const TAG1 = '随笔' + Date.now();
    await page.fill('#a-title', title);
    await page.evaluate(t => {
      document.querySelector('#editor .CodeMirror').CodeMirror.setValue('# ' + t);
    }, title);
    await page.selectOption('#a-status', 'PUBLISHED');

    // ---------- ① 加 3 个标签 ----------
    await addTag(TAG1);
    await addTag('Java');
    await addTag('Spring Boot');
    await page.waitForTimeout(200);
    check('① 3 个标签都成为 chip', (await chipCount()) === 3, (await chipCount()) + '');
    check('① 计数显示 3/10', (await tagCounterText()).trim() === '3/10', await tagCounterText());
    check('① chip 文案正确（去空白）', await page.evaluate(() =>
      [...document.querySelectorAll('#a-tagbox .tagchip')].map(c => c.textContent.replace(/×$/, '').trim()).join('|')
    ) === TAG1 + '|Java|Spring Boot', 'chips');
    await page.screenshot({ path: OUT + '/38-article-tags-edit.png', fullPage: true });

    // ---------- ② 重复标签被拦 ----------
    await addTag('Java');
    await page.waitForTimeout(300);
    check('② 重复标签不新增 chip', (await chipCount()) === 3, (await chipCount()) + '');
    check('② 重复标签有提示', /已存在/.test(await toastText()), await toastText());

    // ---------- ③ 超过 10 个被拦 ----------
    for (let i = 1; i <= 7; i++) await addTag('批量' + String(i).padStart(2, '0'));
    await page.waitForTimeout(200);
    check('③ 10 个 chip 已满', (await chipCount()) === 10, (await chipCount()) + '');
    check('③ 计数显示 10/10', (await tagCounterText()).trim() === '10/10', await tagCounterText());
    await addTag('第11个');
    await page.waitForTimeout(300);
    check('③ 第 11 个加不进去', (await chipCount()) === 10, (await chipCount()) + '');
    check('③ 超量有「最多」提示', /最多/.test(await toastText()), await toastText());

    // 删掉最后两个批量标签再继续（顺带验证 × 删除；.tagchip:nth-of-type 按 span 序号数）
    await page.click('#a-tagbox .tagchip:nth-of-type(10) button');   // 批量07
    await page.click('#a-tagbox .tagchip:nth-of-type(9) button');    // 批量06
    await page.waitForTimeout(200);
    check('③ 点 × 能删除 chip', (await chipCount()) === 8, (await chipCount()) + '');

    // ---------- ④ 保存 → 回列表 → 标签徽章 ----------
    await page.click('#a-save');
    await page.waitForFunction(() => {
      const el = document.getElementById('toast');
      return !!el && el.classList.contains('show') && /保存成功/.test(el.textContent);
    }, { timeout: 15000 }).catch(() => log('WAIT_TOAST_TIMEOUT'));
    check('④ 保存后提示「保存成功」', /保存成功/.test(await toastText()), await toastText());
    await page.waitForSelector('#av-list', { state: 'visible', timeout: 15000 });
    await page.waitForFunction(t => {
      const a = document.querySelector('#alist tbody td:first-child a');
      return !!a && a.textContent.trim() === t;
    }, title, { timeout: 15000 }).catch(() => log('WAIT_NEW_ROW_TIMEOUT'));
    const tagsInRow = await rowTagsText(title);
    check('④ 列表行「标签」列有徽章', tagsInRow.includes(TAG1) && tagsInRow.includes('Java'),
      tagsInRow || '(空)');
    await page.screenshot({ path: OUT + '/39-article-tags-list.png', fullPage: true });

    madeId = await page.evaluate(async t => {
      const p = await jget('/api/admin/articles?size=100');
      const a = p.content.find(x => x.title === t);
      return a ? a.id : null;
    }, title);
    madeSlug = await page.evaluate(async t => {
      const p = await jget('/api/admin/articles?size=100');
      const a = p.content.find(x => x.title === t);
      return a ? a.slug : null;
    }, title);
    log('MADE_ID=' + madeId + ' SLUG=' + madeSlug);
    const expectCsv = [TAG1, 'Java', 'Spring Boot', '批量01', '批量02', '批量03', '批量04', '批量05'].join(',');
    check('④ 服务端存的是归一化逗号串', await page.evaluate(async o => {
      const a = await jget('/api/admin/articles/' + o.id);
      return a.tags === o.expect;
    }, { id: madeId, expect: expectCsv }), expectCsv);

    // ---------- ⑤ 后台搜索按标签命中 ----------
    await searchList(TAG1);
    const hitTitles = await page.evaluate(() =>
      [...document.querySelectorAll('#alist tbody td:first-child a')].map(a => a.textContent.trim()));
    check('⑤ 搜独门标签能命中这篇文章', hitTitles.length === 1 && hitTitles[0] === title,
      hitTitles.join(' , ') || '(空)');

    // ---------- ⑥ 再编辑 → chip 回填 → 删一个保存 → 徽章同步 ----------
    await page.click('#alist tbody tr:first-child button[data-act="edit"]');
    await page.waitForSelector('#ev-edit', { state: 'visible', timeout: 10000 });
    await page.waitForTimeout(500);
    check('⑥ 编辑时标签回填成 chip（8 个）', (await chipCount()) === 8, (await chipCount()) + '');
    await page.click('#a-tagbox .tagchip:nth-of-type(3) button');   // 删「Spring Boot」
    await page.waitForTimeout(200);
    check('⑥ 删除后计数 7/10', (await tagCounterText()).trim() === '7/10', await tagCounterText());
    await page.click('#a-save');
    await page.waitForSelector('#av-list', { state: 'visible', timeout: 15000 });
    await page.waitForTimeout(800);
    const tagsAfter = await rowTagsText(title);
    check('⑥ 保存后列表徽章不再含被删标签', !tagsAfter.includes('Spring Boot'), tagsAfter || '(空)');

    // ---------- ⑦ 前台文章页渲染标签 ----------
    await page.goto(BASE + '/article/' + madeSlug, { waitUntil: 'networkidle' });
    const frontTags = await page.evaluate(() =>
      [...document.querySelectorAll('.tags .tag')].map(e => e.textContent.trim()).join('|'));
    check('⑦ 前台文章页渲染标签徽章', frontTags.includes(TAG1) && frontTags.includes('Java'),
      frontTags || '(空)');
    await page.screenshot({ path: OUT + '/40-article-tags-front.png', fullPage: true });

    // ---------- ⑧ 控制台干净 ----------
    check('⑧ 无 JS 错误', errors.length === 0, errors.slice(0, 3).join(' ; '));
    check('⑧ 无意外 HTTP>=400', httpBad.length === 0, httpBad.slice(0, 3).join(' ; '));
  } catch (e) {
    check('用例执行未抛异常', false, String(e && e.message || e).split('\n')[0]);
  } finally {
    // 清理：删掉本次新建的文章（从 Node 侧发，避免污染页面 console）
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
