const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  await page.goto((process.env.E2E_BASE || 'http://localhost:8080') + '/', { waitUntil: 'networkidle' });
  const footer = await page.$eval('footer', el => el.textContent.trim());
  console.log('FOOTER_TEXT=' + footer);
  await page.$eval('footer', el => el.scrollIntoView({ block: 'center' }));
  await page.screenshot({ path: 'e2e/out/7-footer.png' });
  await page.screenshot({ path: 'e2e/out/7-home-full.png', fullPage: true });
  await browser.close();
  console.log('SHOT_OK');
})();
