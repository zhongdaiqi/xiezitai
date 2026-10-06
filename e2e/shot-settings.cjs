const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const errs = [];
  page.on('pageerror', e => errs.push(e.message));
  await page.goto((process.env.E2E_BASE || 'http://localhost:8080') + '/admin.html', { waitUntil: 'networkidle' });
  await page.fill('#luser', 'xiezitai');
  await page.fill('#lpass', 'xiexiexie');
  await page.click('#login button');
  await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
  await page.click('.tab[data-p="settings"]');
  await page.waitForTimeout(1200);

  const vals = await page.evaluate(() => ({
    url: document.getElementById('s-aiurl').value,
    model: document.getElementById('s-aimodel').value,
    image: document.getElementById('s-aiimage').value,
    link: document.querySelector('#p-settings a[href*="modelscope"]')?.href || ''
  }));
  console.log('AIURL=' + vals.url);
  console.log('MODEL=' + vals.model);
  console.log('IMAGE=' + vals.image);
  console.log('APPLY_LINK=' + vals.link);
  console.log('PAGE_ERRORS=' + errs.length);

  await page.screenshot({ path: 'e2e/out/5-ai-settings.png', fullPage: true });
  await browser.close();
})();
