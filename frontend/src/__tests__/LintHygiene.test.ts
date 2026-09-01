import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * Issue 98 — analyzer-evasion and lint-hygiene guards (plan §7.5, T28/T29/T32).
 *
 * Every prohibited needle below is assembled by string concatenation so this
 * scanner can never match its own source, even if the file is renamed or moved.
 * The scanner additionally excludes its own resolved path.
 */

const SELF_PATH = fileURLToPath(import.meta.url);
const FRONTEND_ROOT = process.cwd();
const SRC_ROOT = path.resolve(FRONTEND_ROOT, 'src');
const SCANNED_EXTENSIONS = ['.ts', '.tsx', '.js', '.jsx', '.mts', '.mjs'];

const NEEDLES: Array<{ label: string; value: string }> = [
  { label: 'eslint disable comment', value: 'eslint-' + 'disable' },
  { label: 'ts ignore directive', value: '@ts-' + 'ignore' },
  { label: 'ts nocheck directive', value: '@ts-' + 'nocheck' },
  { label: 'any cast', value: 'as ' + 'any' },
  { label: 'double cast', value: 'as ' + 'unknown as' },
];

const EXPECT_ERROR_NEEDLE = '@ts-' + 'expect-error';

const EXPECT_ERROR_ALLOWED_FILES = ['src/__tests__/SoundFeedback.test.tsx'];

function collectSourceFiles(dir: string): string[] {
  const collected: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const absolute = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      collected.push(...collectSourceFiles(absolute));
      continue;
    }
    if (!entry.isFile()) continue;
    if (!SCANNED_EXTENSIONS.includes(path.extname(entry.name))) continue;
    if (path.resolve(absolute) === SELF_PATH) continue;
    collected.push(absolute);
  }
  return collected;
}

function toRelativePosixPath(absolute: string): string {
  return path.relative(FRONTEND_ROOT, absolute).split(path.sep).join('/');
}

const scannedFiles = collectSourceFiles(SRC_ROOT);

describe('Issue 98 — lint hygiene guards', () => {
  it('T28 — the frontend lint script fails on warnings', () => {
    const packageJsonPath = path.resolve(FRONTEND_ROOT, 'package.json');
    const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8')) as {
      scripts?: Record<string, string>;
    };

    expect(packageJson.scripts?.lint).toBeDefined();
    expect(packageJson.scripts?.lint).toContain('--max-warnings 0');
  });

  it('T29 — no prohibited analyzer-evasion construct exists under src/', () => {
    expect(scannedFiles.length).toBeGreaterThan(0);
    expect(scannedFiles.some((file) => toRelativePosixPath(file) === 'src/app/page.tsx')).toBe(true);

    const offenders: string[] = [];
    for (const file of scannedFiles) {
      const contents = fs.readFileSync(file, 'utf8');
      for (const needle of NEEDLES) {
        if (contents.includes(needle.value)) {
          offenders.push(`${toRelativePosixPath(file)} → ${needle.label}`);
        }
      }
    }

    expect(offenders).toEqual([]);
  });

  it('T32 — @ts-expect-error stays confined to the one pre-existing location', () => {
    const filesWithDirective = scannedFiles
      .filter((file) => fs.readFileSync(file, 'utf8').includes(EXPECT_ERROR_NEEDLE))
      .map(toRelativePosixPath)
      .sort();

    const unexpected = filesWithDirective.filter(
      (file) => !EXPECT_ERROR_ALLOWED_FILES.includes(file)
    );

    expect(unexpected).toEqual([]);
  });
});
