/* 写字台后台端到端验证：登录 -> 编辑器 -> 建文发布 -> 前台可见 */
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const OUT = 'e2e/out';
const MD = [
  '# 端到端验证',
  '',
  '这是 Playwright 自动发布的文章。',
  '',
  '| 项目 | 值 |',
  '| --- | --- |',
  '| 框架 | Spring Boot |',
  '| 编辑器 | ByteMD |',
  '',
  '- [x] 编辑器加载',
  '- [ ] 待办事项',
  ''
].join('\n');

const errors = [];
const consoleMsgs = [];

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => {
    if (m.type() === 'error') { consoleMsgs.push('CONSOLE.ERROR: ' + m.text()); }
  });
  page.on('response', r => {
    if (r.status() >= 400) { consoleMsgs.push('HTTP ' + r.status() + ' ' + r.url()); }
  });

  const step = (s) => console.log('STEP ' + s);

  try {
    step('goto admin.html');
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });

    step('login');
    await page.fill('#luser', 'xiezitai');
    await page.fill('#lpass', 'xiexiexie');
    await page.click('#login button');
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
    const who = await page.textContent('#who');
    console.log('WHO=' + who);

    step('editor mounted');
    await page.waitForSelector('#editor .CodeMirror', { timeout: 15000 });
    const hasVendor = await page.evaluate(() =>
      typeof bytemd !== 'undefined' && typeof bytemd.Editor === 'function');
    console.log('BYTEMD_OK=' + hasVendor);

    step('fill article');
    const title = '端到端验证 ' + new Date().toISOString().slice(11, 19);
    await page.fill('#a-title', title);
    await page.evaluate((md) => {
      const wrap = document.querySelector('#editor .CodeMirror');
      wrap.CodeMirror.setValue(md);
    }, MD);
    await page.selectOption('#a-status', 'PUBLISHED');

    step('preview rendered');
    await page.waitForTimeout(600);
    const previewTable = await page.evaluate(() =>
      document.querySelectorAll('#editor .markdown-body table').length);
    const previewTask = await page.evaluate(() =>
      document.querySelectorAll('#editor .markdown-body .task-list-item').length);
    console.log('PREVIEW_TABLE=' + previewTable + ' PREVIEW_TASKLIST=' + previewTask);
    await page.screenshot({ path: OUT + '/1-admin-editor.png', fullPage: true });

    step('save/publish');
    await page.click('#p-articles button.primary');
    await page.waitForFunction((t) => {
      return [...document.querySelectorAll('#alist tbody tr td:first-child')]
        .some(td => td.textContent.includes(t));
    }, title, { timeout: 15000 });
    console.log('LISTED=OK');

    const slug = await page.evaluate((t) => {
      const a = [...document.querySelectorAll('#alist tbody tr')]
        .find(tr => tr.textContent.includes(t))?.querySelector('td:first-child a');
      return a ? a.getAttribute('href').split('/').pop() : '';
    }, title);
    console.log('SLUG=' + slug);
    await page.screenshot({ path: OUT + '/2-admin-list.png', fullPage: true });

    step('visit public article');
    await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
    const h1 = await page.textContent('h1').catch(() => '');
    const pubTable = await page.evaluate(() =>
      document.querySelectorAll('table').length);
    console.log('PUBLIC_H1=' + (h1 || '').trim() + ' PUBLIC_TABLE=' + pubTable);
    await page.screenshot({ path: OUT + '/3-public-article.png', fullPage: true });

    step('switch tabs to catch latent JS errors');
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
    for (const p of ['pages', 'files', 'comments', 'users', 'security', 'settings']) {
      await page.click(`.tab[data-p="${p}"]`);
      await page.waitForTimeout(250);
    }
    await page.waitForTimeout(500);
    await page.screenshot({ path: OUT + '/4-settings.png', fullPage: true });

    console.log('--- RESULT ---');
    console.log('PAGE_ERRORS=' + errors.length);
    errors.forEach(e => console.log(e));
    console.log('CONSOLE_ERRORS=' + consoleMsgs.length);
    consoleMsgs.forEach(e => console.log(e));
    console.log('DONE');
  } catch (e) {
    console.log('FATAL: ' + e.message);
    console.log('PAGE_ERRORS=' + errors.length);
    errors.forEach(x => console.log(x));
    consoleMsgs.forEach(x => console.log(x));
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
