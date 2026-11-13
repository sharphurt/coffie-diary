import React from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { webcrypto } from 'node:crypto';
import { App } from './main';
import { api } from './api';
vi.mock('./api', () => ({ api: vi.fn(), login: vi.fn(), refreshCsrf: vi.fn() }));
const mockApi = vi.mocked(api);
beforeEach(() => {
  vi.stubGlobal('crypto', { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) });
  mockApi.mockReset().mockImplementation(async (path) => {
    if (path === '/auth/me') return { id: 'owner' };
    if (path.startsWith('/bags?')) return [{ id: 'bag', name: 'Эфиопия', status: 'ACTIVE' }];
    if (path === '/profile') return { default_cup_volume_ml: 250 };
    if (path.startsWith('/consumption-stats')) return { cups: 0, volume: 0 };
    return [];
  });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
test.each([
  ['Добавить кофе', 'Новая пачка'],
  ['Выпил другой кофе', 'Ещё одна чашка'],
  ['Оценить', 'Как вам кофе?'],
])('opens %s without randomUUID', async (button, title) => {
  render(<App />);
  await userEvent.click(await screen.findByRole('button', { name: new RegExp(button) }));
  expect(screen.getByRole('dialog').textContent).toContain(title);
});
test('failed cup save retains form and event ID for retry', async () => {
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole('button', { name: /Выпил другой кофе/ }));
  mockApi.mockRejectedValueOnce(new Error('Нет связи'));
  await user.click(screen.getByRole('button', { name: 'Сохранить' }));
  await screen.findAllByText('Нет связи');
  expect(screen.getByRole('dialog')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Сохранить' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  const saves = mockApi.mock.calls.filter(([p]) => p === '/consumptions');
  expect(saves).toHaveLength(2);
  expect(saves[0][2]).toEqual(saves[1][2]);
  expect(saves[1][2]).toMatchObject({ volumeMl: 250, bagId: null, clientEventId: expect.any(String) });
});


test('quick cup blocks duplicates while pending and reuses request after failure', async () => {
  const user = userEvent.setup();
  render(<App />);
  const button = await screen.findByRole('button', { name: /Выпил чашку/ });
  await waitFor(() => expect(button.textContent).toContain('250'));
  let reject!: (reason: Error) => void;
  mockApi.mockImplementationOnce(() => new Promise((_, fail) => { reject = fail; }));
  await user.click(button);
  expect((button as HTMLButtonElement).disabled).toBe(true);
  expect(screen.queryByRole('dialog')).toBeNull();
  await user.click(button);
  expect(mockApi.mock.calls.filter(([p]) => p === '/consumptions')).toHaveLength(1);
  reject(new Error('Response lost'));
  await screen.findByRole('button', { name: 'Повторить запись чашки' });
  mockApi.mockResolvedValueOnce({ id: 'event', bag_id: 'bag', volume_ml: 250, version: 0 });
  await user.click(screen.getByRole('button', { name: 'Повторить запись чашки' }));
  await screen.findByRole('button', { name: 'Отменить чашку' });
  const requests = mockApi.mock.calls.filter(([p]) => p === '/consumptions');
  expect(requests).toHaveLength(2);
  expect(requests[0][2]).toEqual(requests[1][2]);
  await user.click(screen.getByRole('button', { name: 'Оценить чашку' }));
  expect(screen.getByRole('dialog').textContent).toContain('Чашка уже учтена');
});
