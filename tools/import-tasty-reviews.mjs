import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { chromium } from '../frontend/node_modules/playwright/index.mjs';

const productPattern = /^https:\/\/shop\.tastycoffee\.ru\/coffee\/[a-z0-9-]+$/;
export async function collectTastyReviews(page, url) {
  assert.match(url, productPattern);
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20000 });
  await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {});
  if (page.url() !== url) return [];
  const tab = page.getByRole('button', { name: /^Комментарии/ }).first();
  await tab.click({ timeout: 10000 });
  const card = page.locator('.cards-community-post .review-card .text-post');
  if (!(await card.first().waitFor({ state: 'visible', timeout: 12000 }).then(() => true, () => false))) return [];
  return page.locator('.cards-community-post .review-card').evaluateAll((cards, expected) => {
    const texts = new Set();
    for (const card of cards) {
      const body = card.querySelector('.text-post');
      const product = card.querySelector('.product-post a[href]');
      if (!body || !product) continue;
      const href = new URL(product.href);
      if (href.origin + href.pathname !== expected) continue;
      const text = body.textContent.replace(/\s+/gu, ' ').trim();
      if (text && text.length <= 8000) texts.add(text);
      if (texts.size >= 50) break;
    }
    return [...texts];
  }, url);
}

export async function importTastyReviews(product, submit) {
  assert.match(product.url, productPattern);
  let texts = [];
  let browser;
  try {
    browser = await chromium.launch();
    const context = await browser.newContext();
    await context.route('**/*', route => {
      const request = route.request();
      return request.isNavigationRequest() && new URL(request.url()).origin !== 'https://shop.tastycoffee.ru'
        ? route.abort() : route.continue();
    });
    texts = await collectTastyReviews(await context.newPage(), product.url);
  } catch (error) {
    console.error('Вкладка отзывов недоступна:', error.name);
  } finally {
    await browser?.close();
  }
  return submit(product.id, { url: product.url, texts });
}

async function cli() {
  const [url] = process.argv.slice(2);
  assert.match(url ?? '', productPattern);
  assert.ok(process.env.E2E_BASE_URL && process.env.OWNER_PASSWORD, 'Set E2E_BASE_URL and OWNER_PASSWORD');
  const base = process.env.E2E_BASE_URL + '/api/v1';
  const cookies = new Map();
  let csrf;
  async function api(path, method = 'GET', body) {
    const headers = { Cookie: [...cookies.values()].join('; ') };
    if (csrf) headers[csrf.headerName] = csrf.token;
    if (typeof body === 'string') headers['Content-Type'] = 'application/json';
    const response = await fetch(base + path, { method, body, headers });
    assert.ok(response.ok, method + ' ' + path + ': ' + response.status);
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';', 1)[0];
      cookies.set(pair.split('=', 1)[0], pair);
    }
    return response.json();
  }
  csrf = await api('/auth/csrf');
  await api('/auth/login', 'POST', new URLSearchParams({
    username: process.env.OWNER_LOGIN || 'owner', password: process.env.OWNER_PASSWORD,
  }));
  csrf = await api('/auth/csrf');
  const product = await api('/catalog/resolve-url', 'POST', JSON.stringify({ url }));
  const result = await importTastyReviews(product, (id, batch) =>
    api('/catalog/products/' + id + '/reviews/import', 'POST', JSON.stringify(batch)));
  console.log(result.status + ': ' + result.items.length + ' сохранённых отзывов');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await cli();
