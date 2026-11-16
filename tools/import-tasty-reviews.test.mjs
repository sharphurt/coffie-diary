import assert from 'node:assert/strict';
import test from 'node:test';
import { chromium } from '../frontend/node_modules/playwright/index.mjs';
import { collectTastyReviews } from './import-tasty-reviews.mjs';

test('only top-level customer cards for the current product enter the import', async () => {
  const url = 'https://shop.tastycoffee.ru/coffee/test-card';
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    const product = '<div class="product-post"><a href="' + url + '">Товар</a></div>';
    const review = text => '<div class="text-post">' + text + '</div>' + product;
    await page.route(url, route => route.fulfill({ contentType: 'text/html; charset=utf-8', body:
      '<button>Комментарии</button><div class="cards-community-post">'
      + '<article class="review-card">' + review('Кофе не горчит')
      + '<article class="review-card">' + review('Вложенный ответ') + '</article></article>'
      + '<article class="review-card">' + review('Кофе   не горчит') + '</article>'
      + '<article class="review-card"><span>Команда поддержки</span>' + review('Ответ сотрудника') + '</article>'
      + '<article class="review-card">' + review('Другой товар').replace(url, url + '-other') + '</article>'
      + '</div>',
    }));
    assert.deepEqual(await collectTastyReviews(page, url), ['Кофе не горчит']);
  } finally {
    await browser.close();
  }
});
