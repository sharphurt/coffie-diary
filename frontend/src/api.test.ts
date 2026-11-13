import { afterEach, beforeEach, expect, test, vi } from 'vitest';
beforeEach(() => vi.resetModules());
afterEach(() => vi.unstubAllGlobals());
test('writes include CSRF, JSON and idempotency key; 204 returns null', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce({ json: async () => ({ token: 'token', headerName: 'X-CSRF' }) }).mockResolvedValueOnce({ ok: true, status: 204 });
  vi.stubGlobal('fetch', fetchMock);
  const { api } = await import('./api');
  expect(await api('/bags', 'POST', { name: 'Coffee' }, 'key')).toBeNull();
  expect(fetchMock).toHaveBeenLastCalledWith('/api/v1/bags', { method: 'POST', headers: { 'X-CSRF': 'token', 'Content-Type': 'application/json', 'Idempotency-Key': 'key' }, body: JSON.stringify({ name: 'Coffee' }) });
});
test('server validation message reaches caller', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({ json: async () => ({ token: 't', headerName: 'X-CSRF' }) }).mockResolvedValueOnce({ ok: false, status: 400, json: async () => ({ message: 'Invalid volume' }) }));
  const { api } = await import('./api');
  await expect(api('/consumptions', 'POST', {})).rejects.toThrow('Invalid volume');
});
