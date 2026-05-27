/**
 * AC-9: pl.json and en.json must have identical key structures.
 *
 * Both locale bundles are read from the filesystem (Node.js fs — available in
 * Vitest even under the jsdom environment) so there is no HTTP layer to mock.
 * The test recursively extracts every leaf key path and asserts that the full
 * set is identical between the two files.
 */
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { describe, it, expect } from 'vitest';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

type JsonValue = string | number | boolean | null | JsonObject | JsonValue[];
interface JsonObject {
  [key: string]: JsonValue;
}

/**
 * Recursively collects all leaf key paths from a nested JSON object.
 * E.g. { "common": { "save": "Zapisz" } } → ["common.save"]
 */
function collectLeafPaths(obj: JsonObject, prefix = ''): string[] {
  const paths: string[] = [];

  for (const key of Object.keys(obj)) {
    const fullKey = prefix ? `${prefix}.${key}` : key;
    const value = obj[key];

    if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
      paths.push(...collectLeafPaths(value as JsonObject, fullKey));
    } else {
      paths.push(fullKey);
    }
  }

  return paths;
}

// ---------------------------------------------------------------------------
// Load bundles from disk
// ---------------------------------------------------------------------------

// __dirname is the directory of this spec file:
// frontend/src/app/i18n/
// The bundles live at frontend/public/assets/i18n/
// Path: ../../../ goes up to frontend/, then into public/assets/i18n/
const bundleDir = resolve(__dirname, '../../../public/assets/i18n');

const plJson = JSON.parse(
  readFileSync(resolve(bundleDir, 'pl.json'), 'utf-8'),
) as JsonObject;

const enJson = JSON.parse(
  readFileSync(resolve(bundleDir, 'en.json'), 'utf-8'),
) as JsonObject;

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('i18n key symmetry — pl.json vs en.json (AC-9)', () => {
  it('pl.json and en.json have identical sets of leaf key paths', () => {
    const plPaths = collectLeafPaths(plJson).sort();
    const enPaths = collectLeafPaths(enJson).sort();

    // Report which keys are missing in each direction for actionable output
    const missingFromEn = plPaths.filter((k) => !enPaths.includes(k));
    const missingFromPl = enPaths.filter((k) => !plPaths.includes(k));

    expect(
      missingFromEn,
      `Keys present in pl.json but MISSING from en.json: ${missingFromEn.join(', ')}`,
    ).toHaveLength(0);

    expect(
      missingFromPl,
      `Keys present in en.json but MISSING from pl.json: ${missingFromPl.join(', ')}`,
    ).toHaveLength(0);
  });

  it('pl.json has no empty-string leaf values (every key is translated)', () => {
    const plPaths = collectLeafPaths(plJson);
    const emptyInPl = plPaths.filter((path) => {
      const parts = path.split('.');
      let node: JsonValue = plJson;
      for (const part of parts) {
        node = (node as JsonObject)[part];
      }
      return node === '';
    });

    expect(
      emptyInPl,
      `These pl.json keys have empty-string values: ${emptyInPl.join(', ')}`,
    ).toHaveLength(0);
  });

  it('en.json has no empty-string leaf values (every key is translated)', () => {
    const enPaths = collectLeafPaths(enJson);
    const emptyInEn = enPaths.filter((path) => {
      const parts = path.split('.');
      let node: JsonValue = enJson;
      for (const part of parts) {
        node = (node as JsonObject)[part];
      }
      return node === '';
    });

    expect(
      emptyInEn,
      `These en.json keys have empty-string values: ${emptyInEn.join(', ')}`,
    ).toHaveLength(0);
  });
});
