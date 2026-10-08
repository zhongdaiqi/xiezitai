/* 后台文章列表：搜索 + 翻页 + 新建按钮 的端到端验证
 *
 * 覆盖：
 *   ① 分页条渲染（共 N 篇 / 第 x/y 页）与首页/上一页/下一页/末页的禁用态、翻页后的行数
 *   ② 关键词搜索：命中数正确、无结果时的空态、清空后恢复
 *   ③ 状态筛选：草稿 / 已发布 各只出对应状态
 *   ④ 「+ 新建文章」进入编辑视图并清空表单与编辑器；返回列表后搜索条件不受影响
 *   ⑤ 点「编辑」能把正文载入编辑区；列表视图与编辑视图互斥（进编辑隐藏列表、返回恢复）
 *   ⑥ 全程 0 JS 错误、0 意外 HTTP>=400
 *
 * 会临时建 12 篇文章（标题带 stamp），跑完按 id 删掉，不留垃圾。
 */
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const OUT = 'e2e/out';
const ADMIN = { u: 'xiezitai', p: 'xiexiexie' };
const ALIST_SIZE = 10;                          // 与 admin.html 里的 ALIST_SIZE 保持一致
const STAMP = Date.now().toString().slice(-6);
const NAME = '分页验证' + STAMP;
const TAG = 'ZZTAG' + STAMP;                    // 只写进两篇的正文，用来验证搜索
const MADE = [];                                // 本次创建的 id，收尾清理

let pass = 0, fail = 0;
function check(name, ok, extra) {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
  ok ? pass++ : fail++;
}

async function req(method, path, body, token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(BASE + path, { method, headers: h, body: body ? JSON.stringify(body) : undefined });
  let d = null; try { d = await r.json(); } catch (e) { }
  return { status: r.status, d };
}

(async () => {
  /* ---------------- 准备：管理员登录 + 造 12 篇 ----------------
     奇数为已发布、偶数为草稿，方便验证状态筛选；
     第 3、7 篇正文埋关键词，用来验证全文搜索。 */
  const login = await req('POST', '/api/auth/login', { username: ADMIN.u, password: ADMIN.p });
  if (login.status !== 200) { console.error('管理员登录失败 status=' + login.status); process.exit(1); }
  const token = login.d.token;

  const first = await req('GET', '/api/admin/articles?page=0&size=1', null, token);
  const total0 = first.d.totalElements;
  const draft0 = (await req('GET', '/api/admin/articles?page=0&size=1&status=DRAFT', null, token)).d.totalElements;
  const pub0 = (await req('GET', '/api/admin/articles?page=0&size=1&status=PUBLISHED', null, token)).d.totalElements;
  console.log('BASE total=' + total0 + ' draft=' + draft0 + ' pub=' + pub0);

  for (let i = 1; i <= 12; i++) {
    const r = await req('POST', '/api/admin/articles', {
      title: NAME + '-' + String(i).padStart(2, '0'),
      content: '## 正文 ' + i + '\n' + ((i === 3 || i === 7) ? TAG + ' 命中关键词' : '普通内容'),
      summary: '分页验证摘要 ' + i,
      status: (i % 2) ? 'PUBLISHED' : 'DRAFT',
    }, token);
    if (r.status !== 200) { console.error('建文章失败 i=' + i + ' status=' + r.status); process.exit(1); }
    MADE.push(r.d.id);
  }

  const expTotal = total0 + 12;
  const expDraft = draft0 + 6;
  const expPub = pub0 + 6;
  const expPages = Math.max(Math.ceil(expTotal / ALIST_SIZE), 1);
  console.log('EXPECT total=' + expTotal + ' draft=' + expDraft + ' pub=' + expPub + ' pages=' + expPages);

  /* ---------------- 浏览器 ---------------- */
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 950 } });
  const errors = [], httpBad = [];
  page.on('pageerror', e => errors.push('PAGEERROR ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });

  const rows = () => page.evaluate(() => document.querySelectorAll('#alist tbody td:first-child a').length);
  const pinfo = () => page.evaluate(() => {
    const el = document.querySelector('#alist-pager .pinfo');
    return el ? el.textContent : '';
  });
  const badges = () => page.evaluate(() =>
    [...document.querySelectorAll('#alist tbody tr')]
      .filter(tr => tr.querySelector('td:first-child a'))
      .map(tr => tr.querySelector('td:nth-child(3)').textContent.trim()));   // 状态列（第 2 列现在是标签）
  const PBTN = n => `#alist-pager .pbtns button:nth-child(${n})`;   // 1首页 2上一页 3下一页 4末页
  const isDisabled = n => page.evaluate(sel => document.querySelector(sel).disabled, PBTN(n));
  const editorText = () => page.evaluate(() => {
    const cm = document.querySelector('#editor .CodeMirror');
    return cm && cm.CodeMirror ? cm.CodeMirror.getValue() : '';
  });
  const settle = () => page.waitForTimeout(900);   // 防抖 260ms + 请求往返

  try {
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    if (!(await page.isVisible('#app').catch(() => false))) {
      await page.fill('#luser', ADMIN.u);
      await page.fill('#lpass', ADMIN.p);
      await page.click('#login button');
    }
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
    await page.waitForFunction(() => document.querySelectorAll('#alist tbody td:first-child a').length > 0, { timeout: 15000 });
    await settle();

    /* ---------- ① 首屏分页条 ---------- */
    check('分页条出现', (await pinfo()).includes('共 ' + expTotal + ' 篇'), await pinfo());
    check('首屏页码 = 1/' + expPages, (await pinfo()).includes('1/' + expPages + ' 页'));
    check('首页显示 ' + ALIST_SIZE + ' 条', (await rows()) === ALIST_SIZE, await rows());
    check('首页时「首页/上一页」禁用', (await isDisabled(1)) && (await isDisabled(2)));
    check('多页时「下一页/末页」可用', !(await isDisabled(3)) && !(await isDisabled(4)));
    await page.screenshot({ path: OUT + '/list-p1.png', fullPage: true });

    /* ---------- ② 翻页 ---------- */
    await page.click(PBTN(3));   // 下一页
    await settle();
    check('下一页 -> 2/' + expPages, (await pinfo()).includes('2/' + expPages + ' 页'), await pinfo());
    check('第 2 页行数正确', (await rows()) === Math.min(ALIST_SIZE, expTotal - ALIST_SIZE), await rows());
    check('第 2 页「上一页」可用', !(await isDisabled(2)));

    await page.click(PBTN(1));   // 首页（此刻已是末页，「末页」按钮是禁用的，必须先回首页）
    await settle();
    check('首页按钮 -> 回到 1/' + expPages, (await pinfo()).includes('1/' + expPages + ' 页'), await pinfo());

    await page.click(PBTN(4));   // 末页
    await settle();
    const lastRows = expTotal - (expPages - 1) * ALIST_SIZE;
    check('末页 -> ' + expPages + '/' + expPages, (await pinfo()).includes(expPages + '/' + expPages + ' 页'), await pinfo());
    check('末页行数正确', (await rows()) === lastRows, await rows());
    check('末页时「下一页/末页」禁用', (await isDisabled(3)) && (await isDisabled(4)));
    await page.screenshot({ path: OUT + '/list-last.png', fullPage: true });

    await page.click(PBTN(1));   // 回首页，继续搜索相关的用例
    await settle();
    check('回首页 -> 1/' + expPages, (await pinfo()).includes('1/' + expPages + ' 页'));

    /* ---------- ③ 关键词搜索（正文命中） ---------- */
    await page.fill('#a-search', TAG);
    await settle();
    check('搜正文关键词 -> 命中 2 篇', (await rows()) === 2, await rows());
    check('搜索后分页收敛为 1/1', (await pinfo()).includes('共 2 篇') && (await pinfo()).includes('1/1 页'), await pinfo());
    check('搜索时翻页按钮全禁用', (await isDisabled(1)) && (await isDisabled(3)));
    const titles = await page.evaluate(() =>
      [...document.querySelectorAll('#alist tbody td:first-child a')].map(a => a.textContent.trim()));
    check('命中项就是埋了关键词的两篇', titles.every(t => t.startsWith(NAME)) && titles.length === 2, titles.join(' | '));
    await page.screenshot({ path: OUT + '/list-search.png', fullPage: true });

    /* ---------- ④ 搜索无结果 ---------- */
    await page.fill('#a-search', 'zzz-none-' + STAMP);
    await settle();
    check('无结果时列表为空', (await rows()) === 0, await rows());
    check('无结果时显示空态文案', await page.isVisible('#alist-empty'));
    check('无结果时页码仍为 1/1', (await pinfo()).includes('1/1 页'), await pinfo());

    /* ---------- ⑤ 清空搜索恢复 ---------- */
    await page.fill('#a-search', '');
    await settle();
    check('清空搜索 -> 恢复 ' + expTotal + ' 篇', (await pinfo()).includes('共 ' + expTotal + ' 篇'), await pinfo());
    check('清空搜索 -> 回到第 1 页 10 条', (await rows()) === ALIST_SIZE);

    /* ---------- ⑥ 状态筛选（与搜索可叠加） ---------- */
    await page.selectOption('#a-filter-status', 'DRAFT');
    await settle();
    check('筛草稿 -> 计数 ' + expDraft, (await pinfo()).includes('共 ' + expDraft + ' 篇'), await pinfo());
    const db = await badges();
    check('筛草稿 -> 全部是「草稿」', db.length > 0 && db.every(t => t === '草稿'), db.join(','));

    await page.selectOption('#a-filter-status', 'PUBLISHED');
    await settle();
    check('筛已发布 -> 计数 ' + expPub, (await pinfo()).includes('共 ' + expPub + ' 篇'), await pinfo());
    const pb = await badges();
    check('筛已发布 -> 全部是「已发布」', pb.length > 0 && pb.every(t => t === '已发布'), pb.join(','));

    // 搜索与筛选叠加：关键词只埋在 i=3 / i=7 两篇里，而它们都是「已发布」
    await page.fill('#a-search', TAG);
    await settle();
    check('搜关键词 + 筛已发布 -> 2 命中', (await rows()) === 2, await rows());

    await page.selectOption('#a-filter-status', 'DRAFT');
    await settle();
    check('同一关键词切到筛草稿 -> 0 命中', (await rows()) === 0, await rows());
    check('叠加过滤到空时显示空态', await page.isVisible('#alist-empty'));

    await page.selectOption('#a-filter-status', '');
    await settle();
    check('清筛选后关键词仍生效 -> 2 篇', (await rows()) === 2, await rows());

    /* ---------- ⑦ 点「编辑」载入（在搜索结果里点） ---------- */
    const firstTitle = await page.evaluate(() =>
      document.querySelector('#alist tbody td:first-child a').textContent.trim());
    await page.click('#alist tbody tr:first-child button[data-act="edit"]');
    await page.waitForTimeout(1200);
    const loadedTitle = await page.inputValue('#a-title');
    const loadedText = await editorText();
    check('编辑载入标题', loadedTitle === firstTitle, loadedTitle + ' vs ' + firstTitle);
    check('编辑载入正文（含关键词）', loadedText.includes(TAG), loadedText.slice(0, 40).replace(/\n/g, '⏎'));
    check('编辑载入后带上了 id', !!(await page.inputValue('#a-id')));

    /* ---------- ⑦b 列表视图与编辑视图互斥（本次改版核心） ---------- */
    check('点「编辑」→ 进入编辑视图（列表同时隐藏）',
      (await page.isVisible('#ev-edit')) && !(await page.isVisible('#av-list')));
    await page.click('#a-back');
    await page.waitForSelector('#av-list', { state: 'visible', timeout: 8000 });
    await settle();
    check('「← 返回列表」→ 回到列表视图（编辑区隐藏）',
      (await page.isVisible('#av-list')) && !(await page.isVisible('#ev-edit')));
    check('返回列表后搜索条件仍在', (await page.inputValue('#a-search')) === TAG, await page.inputValue('#a-search'));

    /* ---------- ⑧ 新建文章按钮 ---------- */
    await page.click('.list-head button.primary');
    await page.waitForTimeout(900);
    check('新建 -> 进入编辑视图', (await page.isVisible('#ev-edit')) && !(await page.isVisible('#av-list')));
    check('新建 -> 清空标题', (await page.inputValue('#a-title')) === '', await page.inputValue('#a-title'));
    check('新建 -> 清空 id（保存时走新增而非覆盖）', (await page.inputValue('#a-id')) === '');
    const emptyBody = await editorText();
    check('新建 -> 清空正文', emptyBody === '', JSON.stringify(emptyBody.slice(0, 30)));
    check('新建 -> 状态回落到草稿', (await page.inputValue('#a-status')) === 'DRAFT');
    check('新建 -> 仍在文章面板', await page.isVisible('#p-articles'));
    await page.screenshot({ path: OUT + '/list-new.png', fullPage: true });

    // 返回列表：新建不该把用户的搜索词也清掉
    await page.click('#a-back');
    await page.waitForSelector('#av-list', { state: 'visible', timeout: 8000 });
    await settle();
    check('新建 -> 不影响已有搜索条件', (await rows()) === 2, await rows());

    /* ---------- ⑨ 窄屏不横向溢出（列表头是新增的 flex 行，窄屏最容易撑破） ---------- */
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(900);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    check('移动端 390px 无横向溢出', overflow <= 2, 'overflow=' + overflow + 'px');
    await page.screenshot({ path: OUT + '/list-mobile.png', fullPage: true });

    /* ---------- ⑩ 控制台干净 ---------- */
    check('无 JS 错误', errors.length === 0, errors.slice(0, 3).join(' || '));
    check('无意外 HTTP>=400', httpBad.length === 0, httpBad.slice(0, 3).join(' || '));

  } catch (e) {
    fail++;
    console.log('FAIL  用例中途抛异常: ' + String(e.message || e).split('\n')[0]);
  } finally {
    await browser.close();
    for (const id of MADE) await req('DELETE', '/api/admin/articles/' + id, null, token);
    const after = (await req('GET', '/api/admin/articles?page=0&size=1', null, token)).d.totalElements;
    console.log('CLEANUP total=' + after + ' (期望回到 ' + total0 + ')');
  }

  console.log('RESULT ' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
})();
