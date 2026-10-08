/* 首页「站内搜索」端到端验证（?q=，服务端渲染）
 *
 * 覆盖：
 *   ① 首页有搜索框（纯 GET 表单）；输入关键词提交后 URL 带 ?q=、输入框回填、命中条数正确
 *   ② 只搜已发布：草稿即便命中关键词也不出现在结果里
 *   ③ 标题 / 正文 / 标签三处命中都能搜到（同一套 like 口径）
 *   ④ 结果 > 10 篇时分页，翻页链接与 canonical / rel prev·next 都带 ?q=（点下一页不会丢搜索条件）
 *   ⑤ 搜不到时给可读空态；搜索页带 noindex（SEO：站内搜索结果页不该被收录）
 *   ⑥ 「清除搜索」回到不带 q 的首页，行为与改造前一致（分页链接里也没有 q）
 *   ⑦ 中文关键词（URL 编码后进链接）同样能搜到
 * 收尾：删掉本次建的文章。
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

const stamp = Date.now() % 100000;
const KW = 'ZKS' + stamp;            // 主关键词（ASCII，链接里不用编码，便于断言结构）
const CN_KW = '中文检索' + stamp;     // 中文关键词（验证 URLEncoder 与中文 like）
const madeIds = [];

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const ctx = await browser.newContext({
    viewport: { width: 900, height: 1200 },
    permissions: ['clipboard-read', 'clipboard-write']
  });
  const page = await ctx.newPage();
  const errors = [], httpBad = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });

  try {
    /* ---------- 登录 ---------- */
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    await page.waitForTimeout(800);
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
    const make = async (title, content, status, tags) => {
      const a = await api('/api/admin/articles', { title, content, status, tags });
      madeIds.push(a.id);
      return a;
    };

    /* ---------- 造数据：12 篇标题命中（凑出第 2 页）+ 正文命中 + 标签命中 + 草稿命中 + 无关 1 篇 ---------- */
    for (let i = 1; i <= 12; i++) {
      await make(`搜索用例-${KW}-${String(i).padStart(2, '0')}`, `第 ${i} 篇。`, 'PUBLISHED');
    }
    await make('正文命中的文章-' + stamp, `正文里藏着关键词 ${KW}，标题里没有。`, 'PUBLISHED');
    await make('标签命中的文章-' + stamp, '正文与标题都没有那个词。', 'PUBLISHED', 'E2E,' + KW);
    await make('草稿不该出现-' + KW, '这是草稿，命中关键词也不该被搜出来。', 'DRAFT');
    await make('完全无关的文章-' + stamp, '正文里什么关键词都没有。', 'PUBLISHED');
    await make('中文标题-' + CN_KW, '中文关键词的正文。', 'PUBLISHED');
    // 命中总数 = 12 + 正文 1 + 标签 1 = 14（草稿不计），分 2 页
    check('① 预置测试数据成功', madeIds.length === 17, 'count=' + madeIds.length);

    /* ---------- ① 搜索框 + 提交 ---------- */
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    const box = await page.evaluate(() => {
      const f = document.querySelector('form.search');
      const i = f && f.querySelector('input[name=q]');
      return { hasForm: !!f, method: f ? f.getAttribute('method') : '', hasInput: !!i,
               placeholder: i ? i.getAttribute('placeholder') : '' };
    });
    check('① 首页有搜索框（GET 表单 + input[name=q]）',
      box.hasForm && box.method.toLowerCase() === 'get' && box.hasInput,
      JSON.stringify(box));

    await page.fill('form.search input[name=q]', KW);
    await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle' }), page.press('form.search input[name=q]', 'Enter')]);
    check('① 提交后 URL 带 ?q=', new RegExp('[?&]q=' + KW).test(decodeURIComponent(page.url())), page.url());

    const s1 = await page.evaluate(() => ({
      rows: [...document.querySelectorAll('.wrap > article h2')].map(h => h.textContent.trim()),
      hit: document.querySelector('.hit') ? document.querySelector('.hit').textContent.replace(/\s+/g, ' ').trim() : '',
      inputVal: document.querySelector('form.search input[name=q]').value,
      canonical: document.querySelector('link[rel=canonical]')?.getAttribute('href') || '',
      robots: document.querySelector('meta[name=robots]')?.getAttribute('content') || '',
      empty: !!document.querySelector('.empty')
    }));
    check('① 命中 14 篇（12 标题 + 1 正文 + 1 标签）', /命中\s*14\s*篇/.test(s1.hit), s1.hit);
    check('① 首页只渲染 10 篇（分页生效）', s1.rows.length === 10, 'rows=' + s1.rows.length);
    check('① 输入框回填关键词', s1.inputVal === KW, s1.inputVal);
    check('① canonical 带 q', s1.canonical.includes('q=' + KW), s1.canonical);
    check('⑤ 搜索页带 noindex', /noindex/.test(s1.robots), s1.robots);

    /* ---------- ③ 标题 / 正文 / 标签都能命中 ---------- */
    check('③ 标题命中（分页也在）', s1.rows.some(t => t.includes(KW)), s1.rows[0]);
    await page.goto(BASE + '/?q=' + encodeURIComponent('正文里藏着关键词'), { waitUntil: 'networkidle' });
    const sContent = await page.evaluate(() =>
      [...document.querySelectorAll('.wrap > article h2')].map(h => h.textContent.trim()));
    check('③ 正文命中可搜到', sContent.some(t => t.includes('正文命中的文章')), sContent.join('|'));
    await page.goto(BASE + '/?q=' + encodeURIComponent('E2E,' + KW), { waitUntil: 'networkidle' });
    const sTag = await page.evaluate(() => ({
      rows: [...document.querySelectorAll('.wrap > article h2')].map(h => h.textContent.trim()),
      hit: document.querySelector('.hit') ? document.querySelector('.hit').textContent : ''
    }));
    check('③ 标签命中可搜到（标签串参与 like）', sTag.rows.some(t => t.includes('标签命中的文章')), sTag.hit.trim());

    /* ---------- ② 草稿不出现 ---------- */
    await page.goto(BASE + '/?q=' + encodeURIComponent(KW), { waitUntil: 'networkidle' });
    const allText = await page.evaluate(() => document.querySelector('.wrap').textContent);
    check('② 草稿命中关键词也不出现在结果里', !allText.includes('草稿不该出现'), '');
    await page.goto(BASE + '/?q=' + encodeURIComponent(KW) + '&page=2', { waitUntil: 'networkidle' });
    const p2Text = await page.evaluate(() => document.querySelector('.wrap').textContent);
    check('② 第 2 页同样不含草稿', !p2Text.includes('草稿不该出现'), '');

    /* ---------- ④ 分页带 q ---------- */
    await page.goto(BASE + '/?q=' + encodeURIComponent(KW), { waitUntil: 'networkidle' });
    const pg = await page.evaluate(() => {
      const pager = document.querySelector('.pager');
      const next = pager.querySelector('a[rel=next]');
      const links = [...pager.querySelectorAll('.pages a')].map(a => a.getAttribute('href'));
      return {
        nextHref: next ? next.getAttribute('href') : '',
        nextUrl: next ? next.href : '',
        links,
        count: pager.querySelector('.count').textContent.trim()
      };
    });
    check('④ 下一页链接带 q', /q=/.test(pg.nextHref) && /page=2/.test(pg.nextHref), pg.nextHref);
    check('④ 页码链接都带 q', pg.links.length >= 2 && pg.links.every(h => /q=/.test(h)), pg.links.join(' '));
    check('④ 计数文案是「共 N 篇匹配」', /共\s*14\s*篇匹配/.test(pg.count), pg.count);

    await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle' }), page.click('.pager a[rel=next]')]);
    const s2 = await page.evaluate(() => ({
      rows: [...document.querySelectorAll('.wrap > article h2')].map(h => h.textContent.trim()),
      prevHref: document.querySelector('.pager a[rel=prev]')?.getAttribute('href') || '',
      canonical: document.querySelector('link[rel=canonical]')?.getAttribute('href') || '',
      inputVal: document.querySelector('form.search input[name=q]').value,
      hit: document.querySelector('.hit').textContent.replace(/\s+/g, ' ').trim()
    }));
    check('④ 点「下一页」后搜索条件还在（输入框回填）', s2.inputVal === KW, s2.inputVal);
    check('④ 第 2 页 4 篇（14 - 10）', s2.rows.length === 4, 'rows=' + s2.rows.length);
    // 第 2 页的「上一页」直接回「第 1 页的首址」（不带 page 参数，与 canonical 一致），但必须带 q
    check('④ 第 2 页「上一页」带 q', /q=/.test(s2.prevHref) && !/page=/.test(s2.prevHref), s2.prevHref);
    check('④ 第 2 页 canonical 带 q 与 page', /q=/.test(s2.canonical) && /page=2/.test(s2.canonical), s2.canonical);
    check('④ 第 2 页命中数提示一致', /命中\s*14\s*篇/.test(s2.hit), s2.hit);
    await page.screenshot({ path: OUT + '/52-home-search.png', fullPage: false });

    /* ---------- ⑤ 空结果 ---------- */
    await page.goto(BASE + '/?q=' + encodeURIComponent('这个词绝对搜不到' + stamp), { waitUntil: 'networkidle' });
    const s0 = await page.evaluate(() => ({
      empty: document.querySelector('.empty') ? document.querySelector('.empty').textContent.trim() : '',
      rows: document.querySelectorAll('.wrap > article').length,
      hit: document.querySelector('.hit') ? document.querySelector('.hit').textContent.replace(/\s+/g, ' ').trim() : '',
      pager: !!document.querySelector('.pager')
    }));
    check('⑤ 搜不到时给可读空态', /没有找到匹配/.test(s0.empty), s0.empty);
    check('⑤ 空结果不渲染文章、不显示分页条', s0.rows === 0 && !s0.pager, 'rows=' + s0.rows);
    check('⑤ 空结果命中数为 0', /命中\s*0\s*篇/.test(s0.hit), s0.hit);

    /* ---------- ⑥ 清除搜索 / 无 q 时行为不变 ---------- */
    await page.goto(BASE + '/?q=' + encodeURIComponent(KW), { waitUntil: 'networkidle' });
    await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle' }), page.click('.hit a')]);
    const back = await page.evaluate(() => ({
      url: location.href,
      inputVal: document.querySelector('form.search input[name=q]').value,
      robots: document.querySelector('meta[name=robots]')?.getAttribute('content') || '',
      pagerHasQ: [...document.querySelectorAll('.pager a')].some(a => /q=/.test(a.getAttribute('href') || '')),
      body: document.querySelector('.wrap').textContent
    }));
    check('⑥ 「清除搜索」回到不带 q 的首页', !/[?&]q=/.test(back.url), back.url);
    check('⑥ 首页输入框为空、不再 noindex', back.inputVal === '' && back.robots === '',
      JSON.stringify({ v: back.inputVal, r: back.robots }));
    check('⑥ 无 q 时翻页链接不带 q、全量文章照常列出（含刚才搜不到的无关文章）',
      !back.pagerHasQ && back.body.includes('完全无关的文章'), 'pagerHasQ=' + back.pagerHasQ);

    await page.goto(BASE + '/?q=', { waitUntil: 'networkidle' });
    const blank = await page.evaluate(() => ({
      robots: document.querySelector('meta[name=robots]')?.getAttribute('content') || '',
      hit: !!document.querySelector('.hit')
    }));
    check('⑥ 空关键词当普通首页处理（不 noindex、不显示命中提示）', blank.robots === '' && !blank.hit,
      JSON.stringify(blank));

    /* ---------- ⑦ 中文关键词 ---------- */
    await page.goto(BASE + '/?q=' + encodeURIComponent(CN_KW), { waitUntil: 'networkidle' });
    const cn = await page.evaluate(() => ({
      rows: [...document.querySelectorAll('.wrap > article h2')].map(h => h.textContent.trim()),
      hit: document.querySelector('.hit').textContent.replace(/\s+/g, ' ').trim(),
      inputVal: document.querySelector('form.search input[name=q]').value,
      canonical: document.querySelector('link[rel=canonical]')?.getAttribute('href') || ''
    }));
    check('⑦ 中文关键词命中 1 篇', cn.rows.length === 1 && /命中\s*1\s*篇/.test(cn.hit), cn.hit);
    check('⑦ 中文关键词在输入框正常回填', cn.inputVal === CN_KW, cn.inputVal);
    check('⑦ canonical 里的中文关键词是百分号编码', /%/.test(cn.canonical), cn.canonical);

    /* ---------- 收尾：清理 ---------- */
    for (const id of madeIds) {
      await fetch(BASE + '/api/admin/articles/' + id, { method: 'DELETE', headers: { Authorization: 'Bearer ' + jwt } });
    }
  } catch (e) {
    check('用例执行未抛异常', false, e.message);
    console.error(e);
  } finally {
    const realErrors = errors.filter(x => !/favicon|401/.test(x));
    const realHttpBad = httpBad.filter(x => !/401|favicon/.test(x));
    check('无 JS 错误', realErrors.length === 0, realErrors.join(' ;; '));
    check('无意外 HTTP>=400', realHttpBad.length === 0, realHttpBad.join(' ;; '));
    await browser.close();
  }

  const failed = checks.filter(c => !c.ok);
  console.log('\n===== 结果：' + (checks.length - failed.length) + '/' + checks.length + ' 通过 =====');
  process.exit(failed.length ? 2 : 0);
})();
