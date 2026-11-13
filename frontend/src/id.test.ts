import { afterEach, expect, test, vi } from 'vitest';
import { webcrypto } from 'node:crypto';
import { createId } from './id';
afterEach(() => vi.unstubAllGlobals());
test('uses native UUID when available', () => {
  const randomUUID = vi.fn(() => 'native-id');
  vi.stubGlobal('crypto', { randomUUID });
  expect(createId()).toBe('native-id');
});
test('HTTP fallback produces unique RFC UUID v4 values', () => {
  vi.stubGlobal('crypto', { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) });
  const ids = Array.from({length: 1000}, createId);
  expect(new Set(ids).size).toBe(1000);
  ids.forEach(id => expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/));
});
