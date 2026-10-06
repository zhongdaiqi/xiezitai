// 验证：① 首页分页（每页 10 篇 / 翻页链接可用 / 越界钳制 / 页码高亮）
//       ② GFM 任务列表在前台渲染成只读复选框，而不是 [x] / [ ] 字面量
const { chromium } = require('playwright');
const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const PAGE_SIZE = 10;

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

async function req(method, path, body, token) {
  const h = {};
  if (body) h['Content-Type'] = 'application/json';
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(BASE + path, { method, headers: h, body: body ? JSON.stringify(body) : undefined });
  let d = null; try { d = await r.json(); } catch (e) {}
  return { status: r.status, d };
}
const post = (p, b, t) => req('POST', p, b, t);

const stamp = Date.now() % 100000;

(async () => {
  const al = await post('/api/auth/login', { username: 'xiezitai', password: 'xiexiexie' });
  const admin = al.d.token;
  if (!admin) { console.error('登录失败', al); process.exit(1); }

  // 先建任务列表文章，再建 12 篇分页文章 —— 后者更新，把前者挤到第 2 页，正好一起验
  const taskMd = '上线前检查：\n\n- [x] 服务端渲染任务列表\n- [ ] 移动端再走查一遍\n';
  const taskArt = await post('/api/admin/articles', {
    title: '任务列表演示-' + stamp, content: taskMd, status: 'PUBLISHED'
  }, admin);
  const taskSlug = taskArt.d.slug;
  console.log('TASKS_SLUG=' + taskSlug);

  for (let i = 1; i <= 12; i++) {
    await post('/api/admin/articles', {
      title: `分页演示-${stamp}-${String(i).padStart(2, '0')}`,
      content: `第 ${i} 篇分页演示文章。`, status: 'PUBLISHED'
    }, admin);
  }

  // ---------- 浏览器 ----------
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 1200 }, deviceScaleFactor: 2 });
  const errors = [], httpBad = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });

  const readPager = () => page.evaluate(() => {
    const pager = document.querySelector('.pager');
    const g = el => el ? Math.round(el.getBoundingClientRect().left) : -1;
    const prev = pager ? pager.querySelector('a[rel=prev]') : null;
    const next = pager ? pager.querySelector('a[rel=next]') : null;
    const cur = pager ? pager.querySelector('.pages a.cur') : null;
    const anyPages = pager ? Array.from(pager.querySelectorAll('.pages a')) : [];
    // 页码条不能与「上一页」重叠
    const lastNum = anyPages.length ? anyPages[anyPages.length - 1] : null;
    return {
      exists: !!pager,
      count: document.querySelectorAll('.wrap > article').length,
      prevText: prev ? prev.textContent.trim() : '',
      nextText: next ? next.textContent.trim() : '',
      cur: cur ? cur.textContent.trim() : '',
      curAriaCurrent: cur ? cur.getAttribute('aria-current') : null,
      pageNums: anyPages.map(a => a.textContent.trim()),
      overlap: (lastNum && next) ? g(next) < g(lastNum) + lastNum.offsetWidth - 2 : false,
      totalText: pager && pager.querySelector('.count') ? pager.querySelector('.count').textContent.trim() : ''
    };
  });

  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  const p1 = await readPager();
  check('首页出现分页条', p1.exists);
  check('第 1 页正好 ' + PAGE_SIZE + ' 篇', p1.count === PAGE_SIZE, 'count=' + p1.count);
  check('第 1 页没有「上一页」链接（用占位 span）', p1.prevText === '', 'prev=' + JSON.stringify(p1.prevText));
  check('第 1 页高亮当前页', p1.cur === '1' && p1.curAriaCurrent === 'page', p1.cur + '/' + p1.curAriaCurrent);
  check('第 1 页有「下一页」', /下一页/.test(p1.nextText), p1.nextText);
  check('页码与「下一页」不重叠', p1.overlap === false, 'pageNums=' + p1.pageNums.join(','));
  check('显示总篇数', /共 \d+ 篇/.test(p1.totalText), p1.totalText);

  // 点「下一页」真实跳转
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle' }), page.click('.pager a[rel=next]')]);
  const p2 = await readPager();
  check('点「下一页」跳到 ?page=2', /[?&]page=2\b/.test(page.url()), page.url());
  check('第 2 页有「上一页」', /上一页/.test(p2.prevText), p2.prevText);
  check('第 2 页高亮 2', p2.cur === '2', p2.cur);
  check('第 2 页文章数 1~' + PAGE_SIZE, p2.count >= 1 && p2.count <= PAGE_SIZE, 'count=' + p2.count);

  // 越界页码必须钳到最后一页，而不是给空列表
  await page.goto(BASE + '/?page=999', { waitUntil: 'networkidle' });
  const p999 = await readPager();
  check('?page=999 钳到有效页（非空）', p999.count >= 1, 'count=' + p999.count);
  check('?page=999 高亮最后一页', p999.cur !== '' && p999.cur !== '999', 'cur=' + p999.cur);
  await page.goto(BASE + '/?page=0', { waitUntil: 'networkidle' });
  const p0 = await readPager();
  check('?page=0 当作第 1 页', p0.cur === '1', 'cur=' + p0.cur);

  // rel prev/next + canonical（SEO）
  const seo = await page.evaluate(() => ({
    canonical: (document.querySelector('link[rel=canonical]') || {}).href || '',
    prev: (document.querySelector('link[rel=prev]') || {}).href || '',
    next: (document.querySelector('link[rel=next]') || {}).href || ''
  }));
  await page.goto(BASE + '/?page=2', { waitUntil: 'networkidle' });
  const seo2 = await page.evaluate(() => ({
    canonical: (document.querySelector('link[rel=canonical]') || {}).href || '',
    prev: (document.querySelector('link[rel=prev]') || {}).href || ''
  }));
  check('第 1 页 canonical 指向 /', /\/$/.test(seo.canonical), seo.canonical);
  check('第 1 页有 rel=next', /page=2/.test(seo.next), seo.next);
  check('第 1 页无 rel=prev', seo.prev === '', seo.prev);
  check('第 2 页 canonical 带 page=2', /page=2/.test(seo2.canonical), seo2.canonical);
  check('第 2 页有 rel=prev', /page=1|\/$/.test(seo2.prev), seo2.prev);

  await page.screenshot({ path: 'e2e/out/18-home-pager.png', fullPage: false });

  // ---------- 任务列表 ----------
  await page.goto(BASE + '/article/' + taskSlug, { waitUntil: 'networkidle' });
  const tl = await page.evaluate(() => {
    const boxes = Array.from(document.querySelectorAll('.markdown-body input[type=checkbox]'));
    const items = boxes.map(b => {
      const li = b.closest('li');
      return {
        checked: b.checked,
        disabled: b.disabled,
        listStyle: li ? getComputedStyle(li).listStyleType : '',
        marker: li ? getComputedStyle(li).listStylePosition : '',
        boxW: Math.round(b.getBoundingClientRect().width),
        text: (li ? li.textContent : '').trim()
      };
    });
    return { n: boxes.length, items, bodyText: document.querySelector('.markdown-body').textContent };
  });
  check('每个任务项渲染成复选框', tl.n === 2, 'n=' + tl.n);
  check('复选框只读（disabled）', tl.items.every(i => i.disabled));
  check('已完成项默认勾选', tl.items[0] && tl.items[0].checked === true, JSON.stringify(tl.items[0] && tl.items[0].checked));
  check('未完成项未勾选', tl.items[1] && tl.items[1].checked === false, JSON.stringify(tl.items[1] && tl.items[1].checked));
  check('项目符号已去掉（:has 生效）', tl.items.every(i => i.listStyle === 'none'), tl.items.map(i => i.listStyle).join('/'));
  check('复选框有实际尺寸', tl.items.every(i => i.boxW >= 12), tl.items.map(i => i.boxW).join('/'));
  check('正文不再出现字面量 [x] / [ ]',
    !/\[x\]/.test(tl.bodyText) && !/\[ ?\]/.test(tl.bodyText),
    JSON.stringify(tl.bodyText.slice(0, 60)));

  await page.screenshot({ path: 'e2e/out/19-tasklist.png', fullPage: false });

  const realErrors = errors.filter(e => !/favicon/.test(e) && !/401/.test(e));
  const realHttpBad = httpBad.filter(x => !/401/.test(x) && !/favicon/.test(x));
  check('无 JS 错误', realErrors.length === 0, realErrors.join(' ;; '));
  check('无意外 HTTP>=400', realHttpBad.length === 0, realHttpBad.join(' ;; '));

  await browser.close();
  const failed = checks.filter(c => !c.ok);
  console.log('\n===== SUMMARY: ' + (checks.length - failed.length) + '/' + checks.length + ' passed =====');
  process.exit(failed.length ? 2 : 0);
})().catch(e => { console.error('E2E CRASH', e); process.exit(3); });
