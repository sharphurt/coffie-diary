import { test, expect, type Page } from '@playwright/test';

const today = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Yekaterinburg' }).format(new Date());
async function totals(page: Page) {
  const response = await page.request.get(`/api/v1/consumption-stats?start=${today()}&end=${today()}`);
  expect(response.ok()).toBeTruthy();
  return response.json();
}
async function save(page: Page) {
  await page.getByRole('dialog').getByRole('button', { name: 'Сохранить', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('alert')).toHaveCount(0);
}

test('diary: create, consume, rate, history, restore, statuses and logout', async ({ page }, info) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  // Exercise the HTTP/LAN fallback in a real browser, without mocking the API.
  await page.addInitScript(() => Object.defineProperty(Crypto.prototype, 'randomUUID', { value: undefined, configurable: true }));
  await page.goto('/');
  await page.getByLabel('Пароль', { exact: true }).fill(process.env.OWNER_PASSWORD!);
  await page.getByRole('button', { name: 'Открыть дневник' }).click();
  await expect(page.getByRole('button', { name: /Добавить кофе/ })).toBeVisible();
  const initial = await totals(page);
  const name = `E2E ${info.project.name} ${Date.now()}`;
  await page.getByRole('button', { name: /Добавить кофе/ }).click();
  await page.getByLabel('Название кофе').fill(name);
  await expect(page.getByLabel('Масса, г')).toHaveValue('250');
  await save(page);
  const card = page.locator('article.card').filter({ hasText: name });
  await expect(card).toBeVisible();
  await expect(card).toContainText('250 г');
  await page.reload();
  await expect(card).toBeVisible();

  await card.getByRole('button', { name: /Выпил чашку/ }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect.poll(async () => (await totals(page)).cups).toBe(initial.cups + 1);
  await expect(page.locator('.numbers')).toContainText(String(initial.volume + 250));

  await page.getByRole('button', { name: /История/ }).click();
  const row = page.locator('article.history-row').filter({ hasText: name });
  await expect(row).toContainText('250 мл');
  await row.getByRole('button', { name: 'Оценить', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('Чашка уже учтена');
  await page.getByLabel('Общее впечатление').selectOption('4');
  await page.getByText('Подробнее о вкусе').click();
  await page.getByRole('combobox', { name: /^Сладость/ }).selectOption('UNKNOWN');
  await page.getByLabel('Ваши впечатления').fill(name + ' — понравилось');
  await save(page);
  await expect(row.getByRole('button', { name: 'Оценить', exact: true })).toHaveCount(0);
  expect((await totals(page)).cups).toBe(initial.cups + 1);
  await page.getByRole('button', { name: 'Оценки', exact: true }).click();
  await expect(row).toContainText('★ 4/5');
  const tastings = await (await page.request.get('/api/v1/tastings')).json();
  const tasting = tastings.find((t: any) => t.comment === name + ' — понравилось');
  expect(tasting.sensory.acidity).toEqual({ status: 'UNANSWERED', value: null });
  expect(tasting.sensory.sweetness).toEqual({ status: 'UNKNOWN', value: null });
  await row.getByRole('button', { name: 'Изменить', exact: true }).click();
  await page.getByText('Подробнее о вкусе').click();
  await expect(page.getByRole('combobox', { name: /^Сладость/ })).toHaveValue('UNKNOWN');
  await expect(page.getByRole('combobox', { name: /^Кислотность/ })).toHaveValue('');
  await page.getByRole('combobox', { name: /^Кислотность/ }).selectOption('3');
  await expect(page.getByLabel('Также учесть выпитую чашку')).toHaveCount(0);
  await save(page);
  await row.getByRole('button', { name: 'Изменить', exact: true }).click();
  await page.getByText('Подробнее о вкусе').click();
  await expect(page.getByRole('combobox', { name: /^Кислотность/ })).toHaveValue('3');
  await save(page);
  const edited = await (await page.request.get(`/api/v1/tastings/${tasting.id}`)).json();
  expect(edited.sensory.acidity).toEqual({ status: 'RATED', value: 3 });
  expect(edited.brew_snapshot).toEqual(tasting.brew_snapshot);
  expect((await totals(page)).cups).toBe(initial.cups + 1);
  await row.getByRole('button', { name: 'Удалить', exact: true }).click();
  await expect(row).toHaveCount(0);
  expect((await totals(page)).cups).toBe(initial.cups + 1);
  await page.getByLabel('Показать удалённые').check();
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: 'Восстановить', exact: true }).click();
  await expect(row).toHaveCount(0);
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.getByLabel('Показать удалённые').uncheck();
  await expect(row).toContainText('★ 4/5');

  await page.getByRole('button', { name: 'Выпито', exact: true }).click();
  await row.getByRole('button', { name: 'Удалить', exact: true }).click();
  await expect(row).toHaveCount(0);
  expect((await totals(page)).cups).toBe(initial.cups);
  await page.getByLabel('Показать удалённые').check();
  await row.getByRole('button', { name: 'Восстановить', exact: true }).click();
  await expect(row).toHaveCount(0);
  await expect.poll(async () => (await totals(page)).cups).toBe(initial.cups + 1);
  await page.getByLabel('Показать удалённые').uncheck();

  await page.getByRole('button', { name: /Сейчас/ }).click();
  await card.getByRole('button', { name: /Закончить/ }).click();
  await expect(card).toHaveCount(0);
  await page.getByRole('button', { name: /История/ }).click();
  await page.getByRole('button', { name: 'Пачки', exact: true }).click();
  await expect(row).toContainText('FINISHED');
  await row.getByRole('button', { name: 'Открыть', exact: true }).click();
  await expect(row).toContainText('ACTIVE');
  await row.getByRole('button', { name: 'Купил ещё', exact: true }).click();
  await save(page);
  await expect(row).toHaveCount(2);
  await page.getByRole('button', { name: /Сейчас/ }).click();
  await expect(card).toHaveCount(2);

  await page.getByRole('button', { name: /Выпил другой кофе/ }).click();
  await page.getByLabel('Объём напитка, мл').fill('200');
  await page.getByLabel('Что пили?').fill(name + ' неизвестный');
  await save(page);
  const final = await totals(page);
  expect(final.cups).toBe(initial.cups + 2);
  expect(final.volume).toBe(initial.volume + 450);
  await page.reload();
  await expect(page.locator('.numbers')).toContainText(String(final.volume));
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await page.getByRole('button', { name: /Выйти/ }).click();
  await expect(page.getByRole('button', { name: 'Открыть дневник' })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect((await page.request.get('/api/v1/bags')).status()).toBe(401);
  expect(errors).toEqual([]);
});


test('cancel, stock opening, standalone rating and settings', async ({ page }, info) => {
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('/');
  await page.getByLabel('Пароль', { exact: true }).fill(process.env.OWNER_PASSWORD!);
  await page.getByRole('button', { name: 'Открыть дневник' }).click();
  await expect(page.getByRole('button', { name: /Добавить кофе/ })).toBeVisible();
  const initial = await totals(page);
  const name = `E2E stock ${info.project.name} ${Date.now()}`;
  await page.getByRole('button', { name: /Добавить кофе/ }).click();
  await page.getByLabel('Название кофе').fill(name);
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Закрыть форму' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText(name, { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: /Добавить кофе/ }).click();
  await page.getByLabel('Название кофе').fill(name);
  await page.getByRole('combobox', { name: 'Статус' }).selectOption('STOCK');
  await save(page);
  await page.locator('details.stock summary').click();
  const stock = page.locator('.stock .history-row').filter({ hasText: name });
  await stock.getByRole('button', { name: 'Открыть пачку' }).click();
  const card = page.locator('article.card').filter({ hasText: name });
  await expect(card).toBeVisible();
  await card.getByRole('button', { name: 'Оценить', exact: true }).click();
  await page.getByRole('combobox', { name: 'Общее впечатление' }).selectOption('4');
  await save(page);
  expect((await totals(page)).cups).toBe(initial.cups);
  await expect(card).toContainText('★ 4.0');
  await page.getByRole('button', { name: /Настройки/ }).click();
  await page.getByLabel('Объём выпитой чашки, мл').fill('200');
  await page.getByRole('button', { name: 'Сохранить настройки' }).click();
  await expect(page.getByRole('status')).toContainText('Сохранено');
  await expect.poll(async () => (await (await page.request.get('/api/v1/profile')).json()).default_cup_volume_ml).toBe(200);
  await page.getByRole('button', { name: /Сейчас/ }).click();
  await expect(card.getByRole('button', { name: 'Выпил чашку · 200 мл' })).toBeVisible();
  await page.getByRole('button', { name: /Настройки/ }).click();
  await page.getByLabel('Объём выпитой чашки, мл').fill('250');
  await page.getByRole('button', { name: 'Сохранить настройки' }).click();
  await expect.poll(async () => (await (await page.request.get('/api/v1/profile')).json()).default_cup_volume_ml).toBe(250);
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(errors).toEqual([]);
});


test('quick cup: lost response retries once, undo and rating notification', async ({ page }, info) => {
  await page.goto('/');
  await page.getByLabel('Пароль', { exact: true }).fill(process.env.OWNER_PASSWORD!);
  await page.getByRole('button', { name: 'Открыть дневник' }).click();
  await page.getByRole('button', { name: /Добавить кофе/ }).click();
  const name = `E2E quick ${info.project.name} ${Date.now()}`;
  await page.getByLabel('Название кофе').fill(name);
  await save(page);
  const initial = await totals(page);
  const card = page.locator('article.card').filter({ hasText: name });
  const requests: any[] = [];
  // Let the real server commit, then lose only the first response.
  await page.route('**/api/v1/consumptions', async route => {
    if (route.request().method() !== 'POST') return route.continue();
    requests.push(route.request().postDataJSON());
    const response = await route.fetch();
    expect(response.ok()).toBeTruthy();
    if (requests.length === 1) await route.abort('failed');
    else await route.fulfill({ response });
  });
  await card.getByRole('button', { name: /Выпил чашку/ }).click();
  await expect(page.getByRole('button', { name: 'Повторить запись чашки' })).toBeVisible();
  expect((await totals(page)).cups).toBe(initial.cups + 1);
  await page.getByRole('button', { name: 'Повторить запись чашки' }).click();
  await expect(page.getByRole('button', { name: 'Отменить чашку' })).toBeEnabled();
  expect(requests).toHaveLength(2);
  expect(requests[0]).toEqual(requests[1]);
  expect((await totals(page)).cups).toBe(initial.cups + 1);
  await page.getByRole('button', { name: 'Отменить чашку' }).click();
  await expect.poll(async () => (await totals(page)).cups).toBe(initial.cups);
  await card.getByRole('button', { name: /Выпил чашку/ }).click();
  await expect(page.getByRole('button', { name: 'Оценить чашку' })).toBeEnabled();
  expect(requests[2].clientEventId).not.toBe(requests[0].clientEventId);
  await page.getByRole('button', { name: 'Оценить чашку' }).click();
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Закрыть форму' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect((await totals(page)).cups).toBe(initial.cups + 1);
  await page.getByRole('button', { name: 'Оценить чашку' }).click();
  await page.getByRole('combobox', { name: 'Общее впечатление' }).selectOption('4');
  await save(page);
  expect((await totals(page)).cups).toBe(initial.cups + 1);
  await expect(page.getByRole('button', { name: 'Оценить чашку' })).toHaveCount(0);
  await expect(card).toContainText('★ 4.0');
});

test('cup history filters rows and totals by the same local day', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Пароль', { exact: true }).fill(process.env.OWNER_PASSWORD!);
  await page.getByRole('button', { name: 'Открыть дневник' }).click();
  await page.getByRole('button', { name: /История/ }).click();
  const csrf = await (await page.request.get('/api/v1/auth/csrf')).json();
  const label = 'E2E period ' + crypto.randomUUID();
  for (const [suffix, consumedAt] of [
    ['before', '2020-01-01T18:59:59Z'],
    ['inside', '2020-01-01T19:00:00Z'],
    ['after', '2020-01-02T19:00:00Z'],
  ]) {
    const response = await page.request.post('/api/v1/consumptions', {
      headers: { [csrf.headerName]: csrf.token },
      data: { clientEventId: crypto.randomUUID(), consumedAt, volumeMl: 123, coffeeLabel: label + ' ' + suffix },
    });
    expect(response.ok()).toBeTruthy();
  }
  await page.getByLabel('С', { exact: true }).fill('2020-01-02');
  await page.getByLabel('По', { exact: true }).fill('2020-01-02');
  await expect(page.getByText(label + ' inside', { exact: true })).toBeVisible();
  await expect(page.getByText(label + ' before', { exact: true })).toHaveCount(0);
  await expect(page.getByText(label + ' after', { exact: true })).toHaveCount(0);
  await expect(page.getByText(/123 мл за период|246 мл за период/)).toBeVisible();
  const row = page.locator('article.history-row').filter({ hasText: label + ' inside' });
  const records = await (await page.request.get('/api/v1/consumptions?start=2020-01-02&end=2020-01-02')).json();
  const original = records.find((record: { coffee_label: string }) => record.coffee_label === label + ' inside');
  const before = await (await page.request.get('/api/v1/consumption-stats?start=2020-01-02&end=2020-01-02')).json();
  await row.getByRole('button', { name: 'Изменить', exact: true }).click();
  await expect(page.getByLabel(/Дата и время/)).toHaveValue('2020-01-02T00:00');
  await page.getByLabel('Объём напитка, мл').fill('321');
  await page.getByLabel('Заметка').fill('Исправленный объём');
  await save(page);
  await expect(row).toContainText('321 мл');
  const stale = await page.request.patch(`/api/v1/consumptions/${original.id}`, {
    headers: { [csrf.headerName]: csrf.token },
    data: { version: original.version, consumedAt: original.consumed_at, volumeMl: 999 },
  });
  expect(stale.status()).toBe(409);
  await expect(page.getByText(`${before.cups} чашек · ${before.volume + 198} мл за период`)).toBeVisible();
  await row.getByRole('button', { name: 'Изменить', exact: true }).click();
  await page.getByLabel(/Дата и время/).fill('2020-01-03T00:00');
  await save(page);
  await expect(row).toHaveCount(0);
  await expect(page.getByText(`${before.cups - 1} чашек · ${before.volume - 123} мл за период`)).toBeVisible();
  await page.getByRole('button', { name: 'Сегодня', exact: true }).click();
  await expect(page.getByText(label + ' inside', { exact: true })).toHaveCount(0);
});
