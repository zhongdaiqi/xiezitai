/*
 * AI 写作辅助端到端验证：AI 优化标题 / AI 提取SEO关键词 / AI 提取SEO描述
 *
 * 前置：被测实例的大模型必须指向 e2e/lib/fake-ai.cjs（离线假上游，输出固定且故意脏），
 *       否则断言没有确定性基准 —— 见 e2e/README.md「AI 相关脚本」一节。
 *
 * 覆盖：
 *   ① 三个按钮 → 三个接口 → 三个表单字段，全链路回填正确
 *   ② 模型返回的脏输出被洗干净（包裹引号、「优化后的标题：」标签、带序号的列表）
 *   ③ AI 不可用时只提示、绝不覆盖用户已经写好的内容（这是最容易踩的坑）
 *   ④ 保存后 SEO 关键词/描述真的落库，且出现在前台文章页的 meta 里
 */
const { chromium } = require('playwright');
const { enterNewArticle } = require('./lib/admin-ui.cjs');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const MD = ['# 写字台', '', '写字台是一套开箱即用的自托管博客系统，支持文章、评论与媒体管理。'].join('\n');

// 假上游给的是脏输出，清洗后应当恰好是下面这些值
const EXPECT_TITLE = '写字台 · 自托管博客系统';
const EXPECT_KEYWORDS = '写字台, 自托管博客, 内容管理';
const EXPECT_DESC = '写字台是一套开箱即用的自托管博客系统，支持文章、页面、评论、媒体管理与 SEO 优化。';
const UNAVAILABLE = '（AI 功能未启用：请在后台配置大模型接口地址、API Key 与模型名）';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

const errors = [], httpBad = [];

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => {
    // /api/admin/ai/* 是本脚本刻意探测的接口，失败时后端会有明确的降级响应，不算站点错误
    if (r.status() >= 400 && !/\/api\/admin\/ai\//.test(r.url())) httpBad.push(r.status() + ' ' + r.url());
  });
  // 「AI 优化标题」在替换前会弹确认框（覆盖用户手写的主文案要先问一声）
  page.on('dialog', d => d.accept());

  const log = (...a) => console.log(...a);
  const btn = name => page.getByRole('button', { name, exact: true });
  const val = sel => page.inputValue(sel);
  const msg = () => page.textContent('#aimsg');

  async function login() {
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1200);                       // 等 boot() 用 localStorage 里的 token 自动登录
    const appVisible = await page.isVisible('#app').catch(() => false);
    if (!appVisible) {
      await page.fill('#luser', 'xiezitai');
      await page.fill('#lpass', 'xiexiexie');
      await page.click('#login button');
    }
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
    // 文章面板默认落在列表视图 → 先进「+ 新建文章」的编辑视图，编辑器与表单项才存在
    await enterNewArticle(page);
  }

  await login();
  log('WHO=' + (await page.textContent('#who')));

  // 准备素材：标题 + 正文（三个 AI 动作都以「标题 + 正文」为输入）
  await page.fill('#a-title', '写字台');
  await page.evaluate(md => {
    document.querySelector('#editor .CodeMirror').CodeMirror.setValue(md);
  }, MD);
  await page.waitForTimeout(300);

  check('起始状态：SEO 关键词/描述为空', (await val('#a-keywords')) === '' && (await val('#a-desc')) === '');

  // ---------- ① AI 优化标题 ----------
  await btn('AI 优化标题').click();
  await page.waitForFunction((expect) => document.getElementById('a-title').value === expect,
    EXPECT_TITLE, { timeout: 30000 }).catch(() => log('WAIT_TITLE_TIMEOUT'));
  const t1 = await val('#a-title');
  log('TITLE=' + t1 + ' MSG=' + (await msg()));
  check('① 优化标题：去掉引号与「优化后的标题：」标签', t1 === EXPECT_TITLE, t1);
  check('① 优化标题：提示文案就绪', (await msg()).includes('标题已优化'), await msg());

  // ---------- ② AI 提取 SEO 关键词 ----------
  await btn('AI 提取SEO关键词').click();
  await page.waitForFunction((expect) => document.getElementById('a-keywords').value === expect,
    EXPECT_KEYWORDS, { timeout: 30000 }).catch(() => log('WAIT_KEYWORDS_TIMEOUT'));
  const kw = await val('#a-keywords');
  log('KEYWORDS=' + kw + ' MSG=' + (await msg()));
  check('② 提取关键词：带标签+序号的列表被洗成逗号分隔', kw === EXPECT_KEYWORDS, kw);
  check('② 提取关键词：没有残留序号/引号', !/[0-9][.、)）]|["“「]/.test(kw), kw);

  // ---------- ③ AI 提取 SEO 描述 ----------
  await btn('AI 提取SEO描述').click();
  await page.waitForFunction((expect) => document.getElementById('a-desc').value === expect,
    EXPECT_DESC, { timeout: 30000 }).catch(() => log('WAIT_DESC_TIMEOUT'));
  const desc = await val('#a-desc');
  log('DESC=' + desc + ' MSG=' + (await msg()));
  check('③ 提取描述：包裹的书名号被去掉', desc === EXPECT_DESC, desc);
  check('③ 提取描述：提示带字数', /已生成 SEO 描述（\d+ 字）/.test(await msg()), await msg());
  await page.screenshot({ path: 'e2e/out/17-ai-seo.png' });

  // ---------- ④ AI 不可用时：只提示，绝不覆盖用户内容 ----------
  // 用路由拦截冒充「后端回降级文案」——真去改后台配置会把这条用例变成有副作用的操作
  await page.route('**/api/admin/ai/title', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ result: UNAVAILABLE })
  }));
  await btn('AI 优化标题').click();
  await page.waitForFunction(() => (document.getElementById('aimsg').textContent || '').includes('AI 功能未启用'),
    null, { timeout: 15000 }).catch(() => log('WAIT_UNAVAILABLE_TIMEOUT'));
  const t2 = await val('#a-title');
  log('TITLE_AFTER_UNAVAILABLE=' + t2 + ' MSG=' + (await msg()));
  check('④ AI 不可用时标题保持不变（不被提示文案覆盖）', t2 === EXPECT_TITLE, t2);
  check('④ AI 不可用时给出可读提示', (await msg()).includes('AI 功能未启用'), await msg());
  await page.unroute('**/api/admin/ai/title');

  // ---------- ⑤ 保存 → 落库 → 前台 meta ----------
  const title = 'E2E AI 写作辅助 ' + Date.now();
  await page.fill('#a-title', title);
  await page.selectOption('#a-status', 'PUBLISHED');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await page.waitForFunction(() => !!document.getElementById('a-slug').value, { timeout: 30000 }).catch(() => {});
  const slug = await val('#a-slug');
  log('SLUG=' + slug);
  check('⑤ 文章保存成功并回填 slug', !!slug);

  const saved = await page.evaluate(async (t) => {
    const p = await jget('/api/admin/articles?size=100');
    return p.content.find(a => a.title === t) || null;
  }, title);
  log('SAVED=' + JSON.stringify(saved && {
    seoKeywords: saved.seoKeywords, seoDescription: saved.seoDescription, status: saved.status
  }));
  check('⑤ SEO 关键词已随文章落库', !!saved && saved.seoKeywords === EXPECT_KEYWORDS, saved && saved.seoKeywords);
  check('⑤ SEO 描述已随文章落库', !!saved && saved.seoDescription === EXPECT_DESC, saved && saved.seoDescription);

  if (slug) {
    await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
    const meta = await page.evaluate(() => ({
      keywords: (document.querySelector('meta[name="keywords"]') || {}).content || '',
      description: (document.querySelector('meta[name="description"]') || {}).content || ''
    }));
    log('META=' + JSON.stringify(meta));
    check('⑤ 前台文章页 meta keywords 用的是 AI 提取的关键词', meta.keywords === EXPECT_KEYWORDS, meta.keywords);
    check('⑤ 前台文章页 meta description 用的是 AI 提取的描述', meta.description === EXPECT_DESC, meta.description);
  }

  check('0 个页面 JS 错误', errors.length === 0, errors.slice(0, 4).join(' ; '));
  check('0 个 HTTP >= 400', httpBad.length === 0, httpBad.slice(0, 4).join(' ; '));

  await browser.close();

  const failed = checks.filter(c => !c.ok);
  console.log('\n===== 结果：' + (checks.length - failed.length) + '/' + checks.length + ' 通过 =====');
  if (failed.length) { failed.forEach(f => console.log('  FAILED: ' + f.name)); process.exit(1); }
})().catch(async e => {
  console.error('SCRIPT_ERROR', e && e.message ? e.message : e);
  try {
    (typeof errors !== 'undefined' ? errors : []).slice(0, 8).forEach(x => console.error('  PAGEERR: ' + x));
    (typeof httpBad !== 'undefined' ? httpBad : []).slice(0, 8).forEach(x => console.error('  HTTPBAD: ' + x));
  } catch (_) {}
  process.exit(2);
});
