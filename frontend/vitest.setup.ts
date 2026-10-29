import '@testing-library/jest-dom';
import { vi } from 'vitest';

vi.mock('next/font/google', () => ({
  Geist: () => ({ variable: '--font-geist-sans' }),
  Geist_Mono: () => ({ variable: '--font-geist-mono' }),
}));

const storageMock = (() => {
  let store: Record<string, string> = {};
  return {
    getItem: (key: string) => store[key] || null,
    setItem: (key: string, value: string) => {
      store[key] = value.toString();
    },
    removeItem: (key: string) => {
      delete store[key];
    },
    clear: () => {
      store = {};
    },
  };
})();

Object.defineProperty(window, 'localStorage', {
  value: storageMock,
  writable: true,
});

if (typeof global.localStorage === 'undefined') {
  global.localStorage = storageMock as unknown as Storage;
}

import { configure } from '@testing-library/react';
configure({ asyncUtilTimeout: 5000 });
