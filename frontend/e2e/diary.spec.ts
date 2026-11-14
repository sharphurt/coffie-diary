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
  if (process.env.E2E_FRESH_DATABASE === 'true' && info.project.name === 'desktop') {
    await test.step('AC-14 unknown coffee in an empty diary', async () => {
      expect(await (await page.request.get('/api/v1/lots')).json()).toEqual([]);
      expect(await totals(page)).toMatchObject({ cups: 0, volume: 0 });
      await page.getByRole('button', { name: /Выпил другой кофе/ }).click();
      await page.getByLabel('Объём напитка, мл').fill('200');
      await save(page);
      expect(await totals(page)).toMatchObject({ cups: 1, volume: 200 });
      expect(await (await page.request.get('/api/v1/lots')).json()).toEqual([]);
      expect(await (await page.request.get('/api/v1/consumptions')).json())
        .toEqual([expect.objectContaining({ bag_id: null, volume_ml: 200 })]);
    });
  }
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
  const prior = await (await page.request.get(`/api/v1/bags/${tasting.bag_id}`)).json();
  const savedRating = await (await page.request.get(`/api/v1/tastings/${tasting.id}`)).json();
  const transitions = await page.request.get(`/api/v1/bags/${prior.id}/status-history`);
  expect(transitions.ok()).toBeTruthy();
  expect((await transitions.json()).map((event: { from_status: string; to_status: string }) =>
    [event.from_status, event.to_status])).toEqual([[null, 'ACTIVE'], ['ACTIVE', 'FINISHED'], ['FINISHED', 'ACTIVE']]);
  await row.getByRole('button', { name: 'Купил ещё', exact: true }).click();
  await save(page);
  await expect(row).toHaveCount(2);
  expect(await (await page.request.get(`/api/v1/bags/${prior.id}`)).json()).toEqual(prior);
  expect(await (await page.request.get(`/api/v1/tastings/${tasting.id}`)).json()).toEqual(savedRating);
  const purchases = await (await page.request.get('/api/v1/bags?q=' + encodeURIComponent(name))).json();
  expect(purchases.filter((bag: { id: string; lot_id: string }) => bag.id !== prior.id && bag.lot_id === prior.lot_id)).toHaveLength(1);
  const csrf = await (await page.request.get('/api/v1/auth/csrf')).json();
  const deletion = await page.request.delete(`/api/v1/bags/${prior.id}?version=${prior.version}`, {
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(deletion.status()).toBe(204);
  await page.getByRole('button', { name: 'Выпито', exact: true }).click();
  const deletedBagCup = page.locator('article.history-row').filter({ hasText: 'Удалённая пачка' })
    .filter({ hasText: '250 мл' });
  await expect(deletedBagCup.first().getByText('Удалённая пачка', { exact: true })).toBeVisible();
  await expect(deletedBagCup.getByRole('button', { name: 'Оценить', exact: true })).toHaveCount(0);
  expect(await totals(page)).toMatchObject({ cups: initial.cups + 1, volume: initial.volume + 250 });
  await page.getByRole('button', { name: /Сейчас/ }).click();
  await expect(card).toHaveCount(1);

  await test.step('AC-15/19 cup totals and atomic tasting submission', async () => {
    const bag = purchases.find((row: { id: string }) => row.id !== prior.id);
    const before = await totals(page);
    await card.getByRole('button', { name: 'Оценить', exact: true }).click();
    const flag = page.getByLabel('Также учесть выпитую чашку');
    await expect(flag).not.toBeChecked();
    await page.getByRole('combobox', { name: 'Общее впечатление' }).selectOption('4');
    await save(page);
    expect(await totals(page)).toEqual(before);

    await card.getByRole('button', { name: 'Оценить', exact: true }).click();
    await page.getByRole('combobox', { name: 'Общее впечатление' }).selectOption('5');
    await flag.check();
    const submission = page.waitForRequest(request =>
      request.method() === 'POST' && request.url().endsWith('/api/v1/tastings'));
    await save(page);
    const request = await submission;
    const response = await request.response();
    expect(response?.ok()).toBeTruthy();
    const tasting = await response!.json();
    expect(request.postDataJSON()).toMatchObject({ bagId: bag.id, recordConsumption: true });
    const combined = { cups: before.cups + 1, volume: before.volume + 250 };
    expect(await totals(page)).toMatchObject(combined);
    await expect(page.locator('.numbers')).toHaveText(new RegExp(`${combined.cups}\\s*чашек\\s*${combined.volume}\\s*мл`));

    const headers = { [csrf.headerName]: csrf.token, 'Idempotency-Key': request.headers()['idempotency-key'] };
    const retry = await page.request.post('/api/v1/tastings', { headers, data: request.postDataJSON() });
    expect(retry.ok()).toBeTruthy();
    expect((await retry.json()).id).toBe(tasting.id);
    const events = await (await page.request.get('/api/v1/consumptions')).json();
    expect(events.filter((event: { tasting_id: string }) => event.tasting_id === tasting.id))
      .toEqual([expect.objectContaining({ bag_id: bag.id, volume_ml: 250 })]);
    expect(await totals(page)).toMatchObject(combined);

    const list = async () => (await page.request.get('/api/v1/tastings')).json();
    const saved = await list();
    const rejected = await page.request.post('/api/v1/tastings', {
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
      data: { bagId: bag.id, overallRating: 5, recordConsumption: true, volumeMl: 0 },
    });
    expect(rejected.status()).toBe(422);
    expect(await rejected.json()).toMatchObject({ fieldErrors: { volumeMl: expect.any(String) } });
    expect(await list()).toEqual(saved);
    expect(await (await page.request.get('/api/v1/consumptions')).json()).toEqual(events);

    if (process.env.E2E_FRESH_DATABASE === 'true' && info.project.name === 'desktop') {
      expect(await totals(page)).toMatchObject({ cups: 3, volume: 700 });
      expect(events.map((event: { volume_ml: number }) => event.volume_ml).sort((a: number, b: number) => a - b))
        .toEqual([200, 250, 250]);
    }
  });

  await page.getByRole('button', { name: /Выпил другой кофе/ }).click();
  await page.getByLabel('Объём напитка, мл').fill('200');
  await page.getByLabel('Что пили?').fill(name + ' неизвестный');
  await save(page);
  const final = await totals(page);
  expect(final.cups).toBe(initial.cups + 3);
  expect(final.volume).toBe(initial.volume + 700);
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
  const originalProfile = await (await page.request.get('/api/v1/profile')).json();
  const bags = await (await page.request.get('/api/v1/bags?q=' + encodeURIComponent(name))).json();
  const bagTastings = async () => (await (await page.request.get('/api/v1/tastings')).json())
    .filter((tasting: { bag_id: string }) => tasting.bag_id === bags[0].id);
  const [originalTasting] = await bagTastings();
  expect(originalTasting.brew_snapshot).toEqual({ ...originalProfile.recipe, profileName: originalProfile.name });
  await page.getByRole('button', { name: /Настройки/ }).click();
  await page.getByLabel('Название профиля').fill('Изменённый профиль E2E');
  await page.getByLabel('Доза кофе, г').fill('15.5');
  await page.getByRole('combobox', { name: 'Молоко и сахар', exact: true }).selectOption('MILK');
  await page.getByLabel('Объём выпитой чашки, мл').fill('200');
  await page.getByRole('button', { name: 'Сохранить настройки' }).click();
  await expect(page.getByRole('status')).toContainText('Сохранено');
  await expect.poll(async () => (await (await page.request.get('/api/v1/profile')).json()).default_cup_volume_ml).toBe(200);
  await page.getByRole('button', { name: /Сейчас/ }).click();
  await expect(card.getByRole('button', { name: 'Выпил чашку · 200 мл' })).toBeVisible();
  await card.getByRole('button', { name: 'Оценить', exact: true }).click();
  await page.getByRole('combobox', { name: 'Общее впечатление' }).selectOption('5');
  await save(page);
  const tastings = await bagTastings();
  expect(tastings.find((t: { id: string }) => t.id === originalTasting.id).brew_snapshot)
    .toEqual(originalTasting.brew_snapshot);
  expect(tastings.find((t: { id: string }) => t.id !== originalTasting.id).brew_snapshot)
    .toMatchObject({ profileName: 'Изменённый профиль E2E', doseG: 15.5, milkSugar: 'MILK' });
  await page.getByRole('button', { name: /Настройки/ }).click();
  await expect(page.getByLabel('Доза кофе, г')).toHaveValue('15.5');
  await page.getByLabel('Название профиля').fill(originalProfile.name);
  await page.getByLabel('Доза кофе, г').fill(originalProfile.recipe.doseG?.toString() ?? '');
  await page.getByRole('combobox', { name: 'Молоко и сахар', exact: true }).selectOption(originalProfile.recipe.milkSugar ?? '');
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

test('sensory averages count only measured values and update after deletion', async ({ page }, info) => {
  await page.goto('/');
  await page.getByLabel('Пароль', { exact: true }).fill(process.env.OWNER_PASSWORD!);
  await page.getByRole('button', { name: 'Открыть дневник' }).click();
  await page.getByRole('button', { name: /Добавить кофе/ }).click();
  const name = `E2E sensory ${info.project.name} ${Date.now()}`;
  await page.getByLabel('Название кофе').fill(name);
  await save(page);
  const card = page.locator('article.card').filter({ hasText: name });
  for (const [value, comment] of [['2', 'first'], ['4', 'second'], ['', 'skipped'], ['UNKNOWN', 'unknown']]) {
    await card.getByRole('button', { name: 'Оценить', exact: true }).click();
    await page.getByRole('combobox', { name: 'Общее впечатление' }).selectOption('4');
    await page.getByText('Подробнее о вкусе').click();
    await page.getByRole('combobox', { name: /^Кислотность/ }).selectOption(value);
    if (comment === 'first') await page.getByRole('combobox', { name: /^Сладость/ }).selectOption('5');
    await page.getByLabel('Ваши впечатления').fill(name + ' ' + comment);
    await save(page);
  }
  await card.getByText('Сводка вкуса', { exact: true }).click();
  await expect(card.getByText('Кислотность: 3 из 5 (2 изм.)')).toBeVisible();
  await expect(card.getByText('Сладость: 5 из 5 (1 изм.)')).toBeVisible();
  await page.getByRole('button', { name: /История/ }).click();
  await page.getByRole('button', { name: 'Оценки', exact: true }).click();
  const first = page.locator('article.history-row').filter({ hasText: name + ' first' });
  await first.getByRole('button', { name: 'Удалить', exact: true }).click();
  await expect(first).toHaveCount(0);
  await page.getByRole('button', { name: /Сейчас/ }).click();
  await card.getByText('Сводка вкуса', { exact: true }).click();
  await expect(card.getByText('Кислотность: 4 из 5 (1 изм.)')).toBeVisible();
  await expect(card.getByText(/Сладость:/)).toHaveCount(0);
  await test.step('AC-08 form and API validation', () => checkValidation(page, info.project.name));
  await test.step('create and edit lots and bags separately', () => checkLotAndBagEditing(page));
});

async function checkValidation(page: Page, project: string) {
  await page.getByRole('button', { name: /Добавить кофе/ }).click();
  const name = 'E2E validation ' + project + ' ' + crypto.randomUUID();
  await page.getByLabel('Название кофе').fill(name);
  const mass = page.getByLabel('Масса, г');
  await mass.fill('-1');
  await page.getByRole('dialog').getByRole('button', { name: 'Сохранить', exact: true }).click();
  expect(await mass.evaluate((input: HTMLInputElement) => input.validity.rangeUnderflow)).toBe(true);
  await expect(page.getByRole('dialog')).toBeVisible();
  await mass.fill('250.5');
  await save(page);

  const card = page.locator('article.card').filter({ hasText: name });
  await card.getByRole('button', { name: 'Оценить', exact: true }).click();
  const rating = page.getByRole('combobox', { name: 'Общее впечатление' });
  expect(await rating.locator('option:not(:disabled)').evaluateAll(options =>
    options.map(option => (option as HTMLOptionElement).value))).toEqual(['1', '2', '3', '4', '5']);
  await page.getByRole('dialog').getByRole('button', { name: 'Сохранить', exact: true }).click();
  expect(await rating.evaluate((input: HTMLSelectElement) => input.validity.valueMissing)).toBe(true);
  const comment = page.getByLabel('Ваши впечатления');
  await comment.fill('x'.repeat(5001));
  expect((await comment.inputValue()).length).toBe(5000);
  await rating.selectOption('5');
  await save(page);

  const csrf = await (await page.request.get('/api/v1/auth/csrf')).json();
  const bags = await (await page.request.get('/api/v1/bags?q=' + encodeURIComponent(name))).json();
  expect(Number(bags[0].mass_g)).toBe(250.5);
  const bagId = bags[0].id;
  async function rejected(path: string, data: Record<string, unknown>, field: string, method = 'POST') {
    const response = await page.request.fetch('/api/v1' + path, {
      method, data,
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
    });
    expect(response.status()).toBe(422);
    expect(await response.json()).toMatchObject({ fieldErrors: { [field]: expect.any(String) } });
  }
  for (const value of [-1, 5001]) {
    await rejected('/bags', { name: 'Rejected ' + name, massG: value }, 'massG');
  }
  for (const value of [0, 6, 2.5]) {
    await rejected('/tastings', { bagId, overallRating: value }, 'overallRating');
  }
  await rejected('/tastings', { bagId, overallRating: 5, comment: 'x'.repeat(5001) }, 'comment');
  const tastings = await (await page.request.get('/api/v1/tastings')).json();
  const tasting = tastings.find((record: { bag_id: string }) => record.bag_id === bagId);
  expect(tasting.comment).toHaveLength(5000);
  const path = '/tastings/' + tasting.id;
  await rejected(path, { version: tasting.version, overallRating: 6 }, 'overallRating', 'PATCH');
  await rejected(path, { version: tasting.version, overallRating: 5, comment: ' '.repeat(5001) }, 'comment', 'PATCH');
  const persisted = await (await page.request.get('/api/v1' + path)).json();
  expect(persisted).toEqual(tasting);
  const unchanged = await (await page.request.get('/api/v1/bags?q=' + encodeURIComponent(name))).json();
  expect(unchanged).toHaveLength(1);
  expect(unchanged[0].tasting_count).toBe(1);
  expect(await (await page.request.get('/api/v1/lots')).json()).not.toEqual(
    expect.arrayContaining([expect.objectContaining({ name: 'Rejected ' + name })]),
  );
}

async function checkLotAndBagEditing(page: Page) {
  const original = 'E2E lot ' + crypto.randomUUID();
  const updated = original + ' renamed';
  const get = async (path: string) => (await page.request.get('/api/v1' + path)).json();
  await page.getByRole('button', { name: /История/ }).click();
  await page.getByRole('button', { name: 'Лоты', exact: true }).click();
  await page.getByRole('button', { name: 'Создать лот' }).click();
  await page.getByLabel('Название кофе').fill(original);
  await save(page);
  const lot = (await get('/lots')).find((row: { name: string }) => row.name === original);
  expect(lot).toBeDefined();
  expect(await get('/bags?q=' + encodeURIComponent(original))).toEqual([]);
  const lotRow = page.locator('article.history-row').filter({ hasText: original });
  await lotRow.getByRole('button', { name: 'Изменить лот' }).click();
  await expect(page.getByLabel('Название кофе')).toHaveValue(original);
  await page.getByLabel('Название кофе').fill(updated);
  await page.getByLabel(/Обжарщик/).fill('Тестовый обжарщик');
  await save(page);
  expect(await get('/lots/' + lot.id)).toMatchObject({ name: updated, roaster_name: 'Тестовый обжарщик' });
  await lotRow.getByRole('button', { name: 'Добавить пачку' }).click();
  await save(page);
  const [bag] = await get('/bags?q=' + encodeURIComponent(updated));
  expect(bag.lot_id).toBe(lot.id);
  await page.getByRole('button', { name: 'Пачки', exact: true }).click();
  await lotRow.getByRole('button', { name: 'Изменить пачку' }).click();
  await page.getByLabel('Масса, г').fill('275.5');
  await page.getByLabel('Дата покупки').fill('2020-02-01');
  await page.getByLabel('Дата обжарки').fill('2020-02-02');
  await expect(page.getByRole('dialog').getByRole('status')).toContainText('Дата обжарки позже');
  await page.getByLabel(/Цена покупки/).fill('642.75');
  await page.getByLabel('Заметка', { exact: true }).fill('Исправленная заметка');
  await save(page);
  const edited = await get('/bags/' + bag.id);
  expect(edited).toMatchObject({ lot_id: lot.id, status: bag.status, opened_at: bag.opened_at,
    purchased_on: '2020-02-01', roasted_on: '2020-02-02', note: 'Исправленная заметка' });
  expect(Number(edited.mass_g)).toBe(275.5);
  expect(Number(edited.purchase_price)).toBe(642.75);

  const csrf = await get('/auth/csrf');
  const headers = { [csrf.headerName]: csrf.token };
  const stale = await page.request.patch('/api/v1/bags/' + bag.id, {
    headers, data: { version: bag.version, massG: 333 },
  });
  expect(stale.status()).toBe(409);
  for (const [field, value] of [['purchasePrice', -1], ['roastedOn', '2099-02-01']]) {
    const response = await page.request.patch('/api/v1/bags/' + bag.id, {
      headers, data: { version: edited.version, [field]: value },
    });
    expect(response.status()).toBe(422);
    expect(await response.json()).toMatchObject({ fieldErrors: { [field]: expect.any(String) } });
  }
  expect(await get('/bags/' + bag.id)).toEqual(edited);
}
