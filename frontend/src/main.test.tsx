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
  ['Выпил чашку', 'Ещё одна чашка'],
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
