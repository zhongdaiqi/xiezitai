/* 后台编辑器「全屏」态不被吸顶导航遮挡 —— 端到端验证（2026-10-07 修复）
 *
 * 现象：点编辑器全屏后，#topbar（position:sticky; z-index:100）压在编辑器顶部，
 *       正好盖住 ByteMD 的工具栏菜单 —— 全屏反而没菜单可用。
 * 根因：ByteMD 的 .bytemd-fullscreen 自带 inset:0 但**没写 z-index**，默认层级低于顶栏。
 * 修复：本页补 .bytemd-fullscreen.bytemd { z-index: 1100 }，让全屏态高于顶栏，
 *       同时把弹窗(1300)/灯箱(1400)/toast(1500) 排在它上面。
 *
 * 覆盖：
 *   ① 工具栏存在「全屏」按钮，点击后进入全屏态（.bytemd-fullscreen）
 *   ② 全屏态 z-index 高于 #topbar（回归护栏）
 *   ③ 命中测试：工具栏那一排的最上层元素是工具栏自己，而不是顶栏（关键）
 *   ④ 工具栏第一个按钮顶部 y >= 0（没有被顶出视口）且可被命中
 *   ⑤ 退出全屏后恢复常规布局（.bytemd-fullscreen 消失）
 *   ⑥ 0 JS 错误、0 意外 HTTP>=400
 */
const { chromium } = require('playwright');
const { enterNewArticle } = require('./lib/admin-ui.cjs');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const OUT = 'e2e/out';

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
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  const log = (...a) => console.log(...a);

  /** hover 工具栏图标 → 读 tippy 气泡标题，返回匹配的下标（找不到返回 -1） */
  async function findToolIndex(re) {
    const n = await page.locator('.bytemd-toolbar-icon').count();
    for (let i = 0; i < n; i++) {
      try {
        await page.locator('.bytemd-toolbar-icon').nth(i).hover({ timeout: 3000 });
        await page.waitForTimeout(300);
        const tip = await page.evaluate(() => {
          const t = document.querySelector('.tippy-content');
          return t ? (t.textContent || '').trim() : '';
        });
        if (re.test(tip)) return i;
      } catch (e) { /* 分隔符等非图标项忽略 */ }
    }
    return -1;
  }

  try {
    // ---------- 登录 → 进文章编辑视图 ----------
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    if (!(await page.isVisible('#app').catch(() => false))) {
      await page.fill('#luser', 'xiezitai');
      await page.fill('#lpass', 'xiexiexie');
      await page.click('#login button');
    }
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
    await enterNewArticle(page);
    log('WHO=' + (await page.textContent('#who')));

    const toolCount = await page.locator('.bytemd-toolbar-icon').count();
    log('TOOL_COUNT=' + toolCount);
    check('编辑器工具栏已渲染（按钮数 > 0）', toolCount > 0, toolCount);

    // ---------- ① 找「全屏」按钮并点击 ----------
    const fsIndex = await findToolIndex(/full\s*screen/i);
    log('FULLSCREEN_INDEX=' + fsIndex);
    check('① 工具栏找到「全屏」按钮', fsIndex >= 0, 'index=' + fsIndex);
    if (fsIndex < 0) throw new Error('工具栏没有全屏按钮，无法继续');

    await page.locator('.bytemd-toolbar-icon').nth(fsIndex).click();
    await page.waitForSelector('.bytemd-fullscreen', { timeout: 5000 });
    await page.waitForTimeout(400);
    check('① 点击后进入全屏态（.bytemd-fullscreen 出现）',
      await page.evaluate(() => !!document.querySelector('.bytemd-fullscreen')));

    // ---------- ②③④ 几何 / 层级 / 命中测试 ----------
    const geo = await page.evaluate(() => {
      const fs = document.querySelector('.bytemd-fullscreen');
      const bar = document.querySelector('.bytemd-toolbar');
      const topbar = document.getElementById('topbar');
      const z = el => el ? (parseInt(getComputedStyle(el).zIndex, 10) || 0) : 0;
      const out = {
        fsZ: z(fs), topbarZ: z(topbar), topbarVisible: !!(topbar && topbar.offsetParent !== null)
      };
      if (bar) {
        const r = bar.getBoundingClientRect();
        out.barTop = Math.round(r.top);
        out.barH = Math.round(r.height);
        const hit = document.elementFromPoint(r.left + Math.min(20, r.width / 2), r.top + r.height / 2);
        out.hitInBar = !!(hit && bar.contains(hit));
        out.hitDesc = hit ? (hit.tagName.toLowerCase()
          + (hit.className ? '.' + String(hit.className).trim().split(/\s+/).join('.') : '')) : null;
        const icon = bar.querySelector('.bytemd-toolbar-icon');
        out.iconTop = icon ? Math.round(icon.getBoundingClientRect().top) : null;
        out.iconHitOk = false;
        if (icon) {
          const ir = icon.getBoundingClientRect();
          const ih = document.elementFromPoint(ir.left + ir.width / 2, ir.top + ir.height / 2);
          out.iconHitOk = !!(ih && (ih === icon || icon.contains(ih) || ih.contains(icon)));
        }
      }
      return out;
    });
    log('GEO=' + JSON.stringify(geo));

    check('② 全屏态 z-index 高于吸顶顶栏', geo.fsZ > geo.topbarZ && geo.fsZ >= 1000,
      '全屏=' + geo.fsZ + ' 顶栏=' + geo.topbarZ);
    check('③ 工具栏那一排没被顶栏遮挡（命中工具栏自身）', geo.hitInBar === true,
      'hit=' + geo.hitDesc);
    check('④ 工具栏不用被顶出视口（首个按钮 top >= 0）', geo.iconTop !== null && geo.iconTop >= 0,
      'iconTop=' + geo.iconTop);
    check('④ 工具栏首个按钮可被直接命中', geo.iconHitOk === true);

    // 全屏时顶栏仍然存在（这次改版不是把顶栏藏掉，而是靠层级压过去）
    check('③ 顶栏仍可见（验证是层级生效而非隐藏顶栏）', geo.topbarVisible === true);

    await page.screenshot({ path: OUT + '/33-editor-fullscreen.png' });

    // ---------- ⑤ 退出全屏 ----------
    await page.keyboard.press('Escape');   // ByteMD 全屏支持 ESC 退出
    const exited = await page.waitForFunction(() => !document.querySelector('.bytemd-fullscreen'),
      { timeout: 5000 }).then(() => true).catch(() => false);
    if (!exited) {
      // 兜底：再点一次全屏按钮
      await page.locator('.bytemd-toolbar-icon').nth(fsIndex).click();
      await page.waitForFunction(() => !document.querySelector('.bytemd-fullscreen'), { timeout: 5000 })
        .catch(() => log('WAIT_EXIT_TIMEOUT'));
    }
    check('⑤ 退出全屏后恢复常规布局（.bytemd-fullscreen 消失）',
      await page.evaluate(() => !document.querySelector('.bytemd-fullscreen')));

    // ---------- ⑥ 控制台干净 ----------
    check('⑥ 无 JS 错误', errors.length === 0, errors.slice(0, 3).join(' ; '));
    check('⑥ 无意外 HTTP>=400', httpBad.length === 0, httpBad.slice(0, 3).join(' ; '));
  } catch (e) {
    check('用例执行未抛异常', false, String(e && e.message || e).split('\n')[0]);
  } finally {
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
