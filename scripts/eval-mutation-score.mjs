#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

/**
 * Evaluates mutation testing reports against persisted calibrated thresholds
 * and verified equivalent mutant ledgers.
 *
 * Implements Unified Mutant Outcome Classification & Adjusted Score Formula:
 * Adjusted Score = (KILLED + TIMED_OUT_proven) / (KILLED + TIMED_OUT_proven + SURVIVED + NO_COVERAGE) * 100%
 */

export function parseArgs(argv) {
  const args = {
    strykerPaths: [],
    pitestPaths: [],
    configPath: 'config/mutation-thresholds.json',
    ledgerPath: 'config/verified-equivalents.json',
    calibrate: false,
    test: false,
    help: false,
    allowProvisional: false,
  };

  for (const arg of argv.slice(2)) {
    if (arg === '--help' || arg === '-h') {
      args.help = true;
    } else if (arg === '--test') {
      args.test = true;
    } else if (arg === '--calibrate') {
      args.calibrate = true;
    } else if (arg === '--allow-provisional') {
      args.allowProvisional = true;
    } else if (arg.startsWith('--stryker=')) {
      const val = arg.slice('--stryker='.length);
      for (const p of val.split(',')) {
        if (p.trim()) args.strykerPaths.push(p.trim());
      }
    } else if (arg.startsWith('--pitest=')) {
      const val = arg.slice('--pitest='.length);
      for (const p of val.split(',')) {
        if (p.trim()) args.pitestPaths.push(p.trim());
      }
    } else if (arg.startsWith('--config=')) {
      args.configPath = arg.slice('--config='.length);
    } else if (arg.startsWith('--ledger=')) {
      args.ledgerPath = arg.slice('--ledger='.length);
    } else if (arg.startsWith('--stryker-min=')) {
      args.strykerMin = parseFloat(arg.slice('--stryker-min='.length));
    } else if (arg.startsWith('--pitest-min=')) {
      args.pitestMin = parseFloat(arg.slice('--pitest-min='.length));
    }
  }

  // Default single-run paths if none provided
  if (args.strykerPaths.length === 0) {
    args.strykerPaths.push('frontend/reports/mutation/mutation.json');
  }
  if (args.pitestPaths.length === 0) {
    args.pitestPaths.push('backend/build/reports/pitest/mutations.xml');
  }

  // Backward-compatible single-path properties
  args.strykerPath = args.strykerPaths[0];
  args.pitestPath = args.pitestPaths[0];

  return args;
}

export function printHelp() {
  console.log(`Usage: node scripts/eval-mutation-score.mjs [options]

Options:
  --help, -h          Display this help message and exit
  --test              Run built-in unit tests for formula and ledger verification
  --calibrate         Run 3-campaign calibration to derive and persist empirical thresholds
                      Requires 3 distinct report pairs: --stryker=s1,s2,s3 --pitest=p1,p2,p3
  --allow-provisional Permit evaluation against provisional bootstrap thresholds
  --stryker=<file>    Path to Stryker mutation.json (accepts comma-separated list or repeated flags)
  --pitest=<file>     Path to Pitest mutations.xml (accepts comma-separated list or repeated flags)
  --config=<file>     Path to persisted thresholds (default: config/mutation-thresholds.json)
  --ledger=<file>     Path to verified equivalents (default: config/verified-equivalents.json)
  --stryker-min=<N>   CLI override for Stryker minimum percentage
  --pitest-min=<N>    CLI override for Pitest minimum percentage
`);
}

export function calculateAdjustedScore({
  killed = 0,
  timedOutProven = 0,
  survived = 0,
  noCoverage = 0,
  unresolvedTimeouts = 0,
  unresolvedExclusions = 0,
}) {
  if (unresolvedTimeouts > 0) {
    return { ok: false, score: 0, reason: `Encountered ${unresolvedTimeouts} unresolved execution timeouts` };
  }
  if (unresolvedExclusions > 0) {
    return { ok: false, score: 0, reason: `Encountered ${unresolvedExclusions} unverified Ignored mutants lacking ledger proof` };
  }

  const denominator = killed + timedOutProven + survived + noCoverage;
  if (denominator === 0) {
    return { ok: true, score: 100.0, reason: 'Zero active candidate mutants evaluated' };
  }

  const numerator = killed + timedOutProven;
  const score = (numerator / denominator) * 100.0;
  return { ok: true, score: Math.round(score * 100) / 100 };
}

export function buildLedgerIndex(verifiedLedger = []) {
  if (!Array.isArray(verifiedLedger)) {
    throw new Error('Malformed ledger: expected an array of ledger entries');
  }

  const equivalentIds = new Set();
  const provenTimeoutIds = new Set();
  const seenEntries = new Map();

  for (let i = 0; i < verifiedLedger.length; i++) {
    const item = verifiedLedger[i];
    if (!item || typeof item !== 'object' || Object.keys(item).length === 0) {
      throw new Error(`Malformed ledger entry at index ${i}: entry is empty or not an object`);
    }

    const type = item.type;
    const validTimeoutTypes = ['PROVEN_INFINITE_LOOP', 'PROVEN_TIMEOUT'];
    const validEquivalentTypes = ['EQUIVALENT', 'VERIFIED_EQUIVALENT'];
    const allValidTypes = [...validTimeoutTypes, ...validEquivalentTypes];

    if (!type || !allValidTypes.includes(type)) {
      throw new Error(
        `Malformed ledger entry at index ${i}: invalid or missing "type" (${type}). Must be one of: ${allValidTypes.join(', ')}`
      );
    }

    const hasProof =
      (item.semanticProof && String(item.semanticProof).trim() !== '') ||
      (item.justification && String(item.justification).trim() !== '') ||
      (item.evidence && String(item.evidence).trim() !== '');

    if (!hasProof) {
      throw new Error(
        `Malformed ledger entry at index ${i}: missing required evidence or semantic proof ("semanticProof", "justification", or "evidence")`
      );
    }

    const isTimeout = validTimeoutTypes.includes(type);

    if (isTimeout) {
      // Timeouts REQUIRE an exact mutantId. Location-only matching is strictly forbidden.
      if (!item.mutantId || typeof item.mutantId !== 'string' || item.mutantId.trim() === '') {
        throw new Error(
          `Malformed timeout ledger entry at index ${i}: timeouts require an exact non-empty "mutantId". Location-only matching is forbidden.`
        );
      }
      const mutantId = item.mutantId.trim();
      if (seenEntries.has(mutantId)) {
        const prevType = seenEntries.get(mutantId);
        if (prevType !== type) {
          throw new Error(`Conflicting ledger entries for mutantId "${mutantId}": defined as both ${prevType} and ${type}`);
        } else {
          throw new Error(`Duplicate ledger entry for mutantId "${mutantId}"`);
        }
      }
      seenEntries.set(mutantId, type);
      provenTimeoutIds.add(mutantId);
    } else {
      // Equivalent mutant
      const id = item.mutantId ? item.mutantId.trim() : null;
      const file = item.file || item.mutatedClass;
      const line = item.line || item.lineNumber;
      const mutator = item.mutator;

      if (!id && (!file || !line)) {
        throw new Error(
          `Malformed equivalent ledger entry at index ${i}: must specify "mutantId" or ("file"/"mutatedClass" and "line"/"lineNumber")`
        );
      }

      if (id) {
        if (seenEntries.has(id)) {
          const prevType = seenEntries.get(id);
          if (prevType !== type) {
            throw new Error(`Conflicting ledger entries for mutantId "${id}": defined as both ${prevType} and ${type}`);
          } else {
            throw new Error(`Duplicate ledger entry for mutantId "${id}"`);
          }
        }
        seenEntries.set(id, type);
        equivalentIds.add(id);
      }

      if (file && line) {
        const locId = `${file}:${line}`;
        const locMutatorId = mutator ? `${file}:${line}:${mutator}` : null;
        if (!id) {
          const keyToCheck = locMutatorId || locId;
          if (seenEntries.has(keyToCheck)) {
            const prevType = seenEntries.get(keyToCheck);
            if (prevType !== type) {
              throw new Error(`Conflicting ledger entries for "${keyToCheck}": defined as both ${prevType} and ${type}`);
            } else {
              throw new Error(`Duplicate ledger entry for "${keyToCheck}"`);
            }
          }
          seenEntries.set(keyToCheck, type);
        }
        equivalentIds.add(locId);
        if (locMutatorId) equivalentIds.add(locMutatorId);
      }
    }
  }

  return { equivalentIds, provenTimeoutIds };
}

export function parseStrykerJson(jsonString, verifiedLedger = []) {
  const data = JSON.parse(jsonString);
  let killed = 0;
  let timedOutProven = 0;
  let survived = 0;
  let noCoverage = 0;
  let timeout = 0;
  let unresolvedTimeouts = 0;
  let ignored = 0;
  let compileError = 0;
  let unresolvedExclusions = 0;

  const { equivalentIds, provenTimeoutIds } = buildLedgerIndex(verifiedLedger);

  const files = data.files || {};
  for (const [filePath, fileObj] of Object.entries(files)) {
    for (const mutant of fileObj.mutants || []) {
      const status = mutant.status;
      const mutantId = String(mutant.id ?? '').trim();
      const locId = `${filePath}:${mutant.location?.start?.line}`;

      if (status === 'Killed') {
        killed++;
      } else if (status === 'Survived') {
        survived++;
      } else if (status === 'NoCoverage') {
        noCoverage++;
      } else if (status === 'Timeout') {
        timeout++;
        // EXACT mutant-ID match ONLY. Location-only matching is strictly forbidden.
        if (mutantId && provenTimeoutIds.has(mutantId)) {
          timedOutProven++;
        } else {
          unresolvedTimeouts++;
        }
      } else if (status === 'CompileError') {
        compileError++;
      } else if (status === 'Ignored') {
        ignored++;
        if ((mutantId && equivalentIds.has(mutantId)) || (locId && equivalentIds.has(locId))) {
          // Verified equivalent exclusion
        } else {
          unresolvedExclusions++;
        }
      }
    }
  }

  return {
    killed,
    timedOutProven,
    survived,
    noCoverage,
    timeout,
    unresolvedTimeouts,
    ignored,
    compileError,
    unresolvedExclusions,
  };
}

export function parsePitestXml(xmlString, verifiedLedger = []) {
  let killed = 0;
  let timedOutProven = 0;
  let survived = 0;
  let noCoverage = 0;
  let timeout = 0;
  let unresolvedTimeouts = 0;
  let nonViable = 0;
  let unresolvedExclusions = 0;

  const { equivalentIds, provenTimeoutIds } = buildLedgerIndex(verifiedLedger);

  const mutationBlockRegex = /<mutation\s+([^>]*?)>([\s\S]*?)<\/mutation>/g;
  let blockMatch;
  while ((blockMatch = mutationBlockRegex.exec(xmlString)) !== null) {
    const attrs = blockMatch[1];
    const body = blockMatch[2];
    const statusMatch = /status=['"]([^'"]+)['"]/.exec(attrs);
    if (!statusMatch) continue;
    const status = statusMatch[1];

    const sourceFileMatch = /<sourceFile>([^<]+)<\/sourceFile>/.exec(body);
    const mutatedClassMatch = /<mutatedClass>([^<]+)<\/mutatedClass>/.exec(body);
    const lineNumberMatch = /<lineNumber>([^<]+)<\/lineNumber>/.exec(body);
    const mutatorMatch = /<mutator>([^<]+)<\/mutator>/.exec(body);
    const mutantIdMatch = /<mutantId>([^<]+)<\/mutantId>/.exec(body);

    const sourceFile = sourceFileMatch ? sourceFileMatch[1].trim() : '';
    const mutatedClass = mutatedClassMatch ? mutatedClassMatch[1].trim() : '';
    const lineNumber = lineNumberMatch ? lineNumberMatch[1].trim() : '';
    const mutator = mutatorMatch ? mutatorMatch[1].trim() : '';
    const explicitMutantId = mutantIdMatch ? mutantIdMatch[1].trim() : '';

    const exactMutatorId = `${mutatedClass}:${lineNumber}:${mutator}`;
    const exactSourceMutatorId = `${sourceFile}:${lineNumber}:${mutator}`;

    const locId1 = `${sourceFile}:${lineNumber}`;
    const locId2 = `${mutatedClass}:${lineNumber}`;

    if (status === 'KILLED') {
      killed++;
    } else if (status === 'SURVIVED') {
      survived++;
    } else if (status === 'NO_COVERAGE') {
      noCoverage++;
    } else if (status === 'TIMED_OUT') {
      timeout++;
      // EXACT mutant-ID match ONLY.
      // Must match exact mutatedClass:lineNumber:mutator, sourceFile:lineNumber:mutator, or explicit mutantId.
      // Location-only (without mutator) is strictly forbidden from proving timeouts.
      const isExactMatch =
        (explicitMutantId && provenTimeoutIds.has(explicitMutantId)) ||
        (exactMutatorId && provenTimeoutIds.has(exactMutatorId)) ||
        (exactSourceMutatorId && provenTimeoutIds.has(exactSourceMutatorId));

      if (isExactMatch) {
        timedOutProven++;
      } else {
        unresolvedTimeouts++;
      }
    } else if (status === 'NON_VIABLE') {
      nonViable++;
    }
  }

  return {
    killed,
    timedOutProven,
    survived,
    noCoverage,
    timeout,
    unresolvedTimeouts,
    nonViable,
    unresolvedExclusions,
  };
}

export function computeSha256(content) {
  return crypto.createHash('sha256').update(content).digest('hex');
}

export function calibrateCampaigns({ strykerPaths, pitestPaths, ledger = [], configPath = null }) {
  if (!Array.isArray(strykerPaths) || strykerPaths.length !== 3) {
    throw new Error(
      `Calibration requires exactly 3 distinct baseline campaigns for Stryker. Received: ${strykerPaths?.length ?? 0}. Single-run calibration is rejected.`
    );
  }
  if (!Array.isArray(pitestPaths) || pitestPaths.length !== 3) {
    throw new Error(
      `Calibration requires exactly 3 distinct baseline campaigns for Pitest. Received: ${pitestPaths?.length ?? 0}. Single-run calibration is rejected.`
    );
  }

  // Validate existence, compute hashes, and reject duplicate campaigns
  const strykerHashes = new Set();
  const pitestHashes = new Set();
  const strykerDataList = [];
  const pitestDataList = [];

  for (let i = 0; i < 3; i++) {
    const sPath = strykerPaths[i];
    if (!fs.existsSync(sPath)) {
      throw new Error(`Stryker campaign file not found: ${sPath}`);
    }
    const sContent = fs.readFileSync(sPath, 'utf8');
    const sHash = computeSha256(sContent);
    if (strykerHashes.has(sHash)) {
      throw new Error(`Duplicate Stryker campaign detected: "${sPath}" has identical content/SHA-256 to another campaign. 3 distinct campaigns are required.`);
    }
    strykerHashes.add(sHash);

    const sParsed = parseStrykerJson(sContent, ledger);
    const sTotal = sParsed.killed + sParsed.timedOutProven + sParsed.survived + sParsed.noCoverage + sParsed.ignored + sParsed.compileError;
    if (sTotal === 0) {
      throw new Error(`Incomplete Stryker campaign at "${sPath}": report contains 0 mutants`);
    }
    const sScore = calculateAdjustedScore(sParsed);
    if (!sScore.ok) {
      throw new Error(`Stryker campaign at "${sPath}" failed evaluation: ${sScore.reason}`);
    }
    strykerDataList.push({ path: sPath, hash: sHash, score: sScore.score, metrics: sParsed });

    const pPath = pitestPaths[i];
    if (!fs.existsSync(pPath)) {
      throw new Error(`Pitest campaign file not found: ${pPath}`);
    }
    const pContent = fs.readFileSync(pPath, 'utf8');
    const pHash = computeSha256(pContent);
    if (pitestHashes.has(pHash)) {
      throw new Error(`Duplicate Pitest campaign detected: "${pPath}" has identical content/SHA-256 to another campaign. 3 distinct campaigns are required.`);
    }
    pitestHashes.add(pHash);

    const pParsed = parsePitestXml(pContent, ledger);
    const pTotal = pParsed.killed + pParsed.timedOutProven + pParsed.survived + pParsed.noCoverage + pParsed.nonViable;
    if (pTotal === 0) {
      throw new Error(`Incomplete Pitest campaign at "${pPath}": report contains 0 mutants`);
    }
    const pScore = calculateAdjustedScore(pParsed);
    if (!pScore.ok) {
      throw new Error(`Pitest campaign at "${pPath}" failed evaluation: ${pScore.reason}`);
    }
    pitestDataList.push({ path: pPath, hash: pHash, score: pScore.score, metrics: pParsed });
  }

  // Derive empirical baselines using documented aggregation rule:
  // Baseline Adjusted Score = arithmetic mean of 3 campaigns
  // Threshold Floor = max(MinimumFloorLimit, Baseline - 5.0%)
  const strykerScores = strykerDataList.map(c => c.score);
  const pitestScores = pitestDataList.map(c => c.score);

  const strykerMean = Math.round(((strykerScores[0] + strykerScores[1] + strykerScores[2]) / 3.0) * 100) / 100;
  const strykerFloor = Math.max(60.0, Math.round((strykerMean - 5.0) * 10) / 10);

  const pitestMean = Math.round(((pitestScores[0] + pitestScores[1] + pitestScores[2]) / 3.0) * 100) / 100;
  const pitestFloor = Math.max(65.0, Math.round((pitestMean - 5.0) * 10) / 10);

  const nowIso = new Date().toISOString();
  const config = {
    status: 'EMPIRICALLY_CALIBRATED',
    calibrated: true,
    calibratedAt: nowIso,
    aggregationRule: 'arithmetic_mean_with_5pct_margin',
    campaigns: [
      {
        campaignIndex: 1,
        stryker: { path: strykerDataList[0].path, sha256: strykerDataList[0].hash, score: strykerDataList[0].score, metrics: strykerDataList[0].metrics },
        pitest: { path: pitestDataList[0].path, sha256: pitestDataList[0].hash, score: pitestDataList[0].score, metrics: pitestDataList[0].metrics },
      },
      {
        campaignIndex: 2,
        stryker: { path: strykerDataList[1].path, sha256: strykerDataList[1].hash, score: strykerDataList[1].score, metrics: strykerDataList[1].metrics },
        pitest: { path: pitestDataList[1].path, sha256: pitestDataList[1].hash, score: pitestDataList[1].score, metrics: pitestDataList[1].metrics },
      },
      {
        campaignIndex: 3,
        stryker: { path: strykerDataList[2].path, sha256: strykerDataList[2].hash, score: strykerDataList[2].score, metrics: strykerDataList[2].metrics },
        pitest: { path: pitestDataList[2].path, sha256: pitestDataList[2].hash, score: pitestDataList[2].score, metrics: pitestDataList[2].metrics },
      },
    ],
    stryker: {
      status: 'EMPIRICALLY_CALIBRATED',
      baselineAdjustedScore: strykerMean,
      thresholdFloor: strykerFloor,
      minScore: Math.min(...strykerScores),
      maxScore: Math.max(...strykerScores),
      calibratedAt: nowIso,
    },
    pitest: {
      status: 'EMPIRICALLY_CALIBRATED',
      baselineAdjustedScore: pitestMean,
      thresholdFloor: pitestFloor,
      minScore: Math.min(...pitestScores),
      maxScore: Math.max(...pitestScores),
      calibratedAt: nowIso,
    },
  };

  if (configPath) {
    fs.writeFileSync(configPath, JSON.stringify(config, null, 2) + '\n');
  }

  return config;
}

export function runSelfTests() {
  console.log('Running self-contained unit tests...');
  let passed = 0;

  // Test 1: Standard formula calculation
  const r1 = calculateAdjustedScore({ killed: 80, timedOutProven: 0, survived: 15, noCoverage: 5 });
  if (r1.ok && r1.score === 80.0) passed++;
  else throw new Error(`Test 1 failed: expected 80.0, got ${JSON.stringify(r1)}`);

  // Test 2: Division by zero returns 100.0%
  const r2 = calculateAdjustedScore({ killed: 0, survived: 0, noCoverage: 0 });
  if (r2.ok && r2.score === 100.0) passed++;
  else throw new Error(`Test 2 failed: expected 100.0, got ${JSON.stringify(r2)}`);

  // Test 3: Unproven timeouts fail
  const r3 = calculateAdjustedScore({ killed: 50, survived: 10, noCoverage: 0, unresolvedTimeouts: 1 });
  if (!r3.ok && r3.reason.includes('unresolved execution timeouts')) passed++;
  else throw new Error(`Test 3 failed: expected failure on timeout, got ${JSON.stringify(r3)}`);

  // Test 4: Unverified exclusions fail
  const r4 = calculateAdjustedScore({ killed: 50, survived: 10, noCoverage: 0, unresolvedExclusions: 2 });
  if (!r4.ok && r4.reason.includes('unverified Ignored mutants')) passed++;
  else throw new Error(`Test 4 failed: expected failure on unresolved exclusions, got ${JSON.stringify(r4)}`);

  // Test 5: Stryker JSON parsing with verified equivalent ledger
  const sampleStryker = JSON.stringify({
    files: {
      'src/utils/urlUtils.ts': {
        mutants: [
          { id: '1', status: 'Killed' },
          { id: '2', status: 'Survived' },
          { id: '3', status: 'Ignored', location: { start: { line: 10 } } },
        ],
      },
    },
  });
  const ledgerEquivalent = [{ mutantId: '3', type: 'EQUIVALENT', semanticProof: 'Equivalence proven' }];
  const strykerParsed = parseStrykerJson(sampleStryker, ledgerEquivalent);
  if (strykerParsed.killed === 1 && strykerParsed.survived === 1 && strykerParsed.unresolvedExclusions === 0) passed++;
  else throw new Error(`Test 5 failed: got ${JSON.stringify(strykerParsed)}`);

  // Test 6: Stryker JSON parsing with unverified exclusion flags failure
  const strykerUnverified = parseStrykerJson(sampleStryker, []);
  if (strykerUnverified.unresolvedExclusions === 1) passed++;
  else throw new Error(`Test 6 failed: expected 1 unresolved exclusion, got ${strykerUnverified.unresolvedExclusions}`);

  // Test 7: Stryker JSON with unproven Timeout fails evaluation
  const sampleTimeout = JSON.stringify({
    files: {
      'src/utils/chessGame.ts': {
        mutants: [{ id: 't1', status: 'Timeout', location: { start: { line: 42 } } }],
      },
    },
  });
  const strykerTimeoutUnproven = parseStrykerJson(sampleTimeout, []);
  const scoreTimeoutUnproven = calculateAdjustedScore(strykerTimeoutUnproven);
  if (strykerTimeoutUnproven.unresolvedTimeouts === 1 && !scoreTimeoutUnproven.ok) passed++;
  else throw new Error(`Test 7 failed: expected unresolved timeout failure, got score: ${JSON.stringify(scoreTimeoutUnproven)}`);

  // Test 8: Report containing ONLY unproven timed-out mutants must never return 100%
  if (!scoreTimeoutUnproven.ok && scoreTimeoutUnproven.score === 0) passed++;
  else throw new Error(`Test 8 failed: timeout-only report returned passing or non-zero score: ${JSON.stringify(scoreTimeoutUnproven)}`);

  // Test 9: Stryker JSON with proven timeout in ledger passes by exact mutantId
  const ledgerProvenTimeout = [{ mutantId: 't1', type: 'PROVEN_INFINITE_LOOP', semanticProof: 'While loop guaranteed infinite' }];
  const strykerTimeoutProven = parseStrykerJson(sampleTimeout, ledgerProvenTimeout);
  const scoreTimeoutProven = calculateAdjustedScore(strykerTimeoutProven);
  if (strykerTimeoutProven.timedOutProven === 1 && strykerTimeoutProven.unresolvedTimeouts === 0 && scoreTimeoutProven.ok && scoreTimeoutProven.score === 100.0) passed++;
  else throw new Error(`Test 9 failed: expected proven timeout 100%, got ${JSON.stringify(scoreTimeoutProven)}`);

  // Test 10: Pitest XML parsing with unproven TIMED_OUT fails evaluation
  const pitestXmlTimeout = `
    <mutations>
      <mutation detected='true' status='TIMED_OUT'>
        <sourceFile>AdaptivePuzzleSchedulingPolicy.kt</sourceFile>
        <mutatedClass>com.chessprobe.service.AdaptivePuzzleSchedulingPolicy</mutatedClass>
        <lineNumber>55</lineNumber>
        <mutator>org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator</mutator>
      </mutation>
    </mutations>
  `;
  const pitestTimeoutUnproven = parsePitestXml(pitestXmlTimeout, []);
  const scorePitestTimeout = calculateAdjustedScore(pitestTimeoutUnproven);
  if (pitestTimeoutUnproven.unresolvedTimeouts === 1 && !scorePitestTimeout.ok) passed++;
  else throw new Error(`Test 10 failed: expected Pitest unproven timeout failure, got ${JSON.stringify(scorePitestTimeout)}`);

  // Test 11: Pitest XML parsing with proven TIMED_OUT via exact mutatedClass:lineNumber:mutator passes
  const ledgerPitestProven = [{
    mutantId: 'com.chessprobe.service.AdaptivePuzzleSchedulingPolicy:55:org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator',
    type: 'PROVEN_INFINITE_LOOP',
    semanticProof: 'Infinite loop condition on boundary mutator'
  }];
  const pitestTimeoutProven = parsePitestXml(pitestXmlTimeout, ledgerPitestProven);
  const scorePitestProven = calculateAdjustedScore(pitestTimeoutProven);
  if (pitestTimeoutProven.timedOutProven === 1 && pitestTimeoutProven.unresolvedTimeouts === 0 && scorePitestProven.ok && scorePitestProven.score === 100.0) passed++;
  else throw new Error(`Test 11 failed: expected Pitest proven timeout 100%, got ${JSON.stringify(scorePitestProven)}`);

  // Test 12 (F-04): Empty ledger entry `{}` throws malformed ledger error
  let caughtEmpty = false;
  try {
    buildLedgerIndex([{}]);
  } catch (e) {
    if (e.message.includes('empty or not an object')) caughtEmpty = true;
  }
  if (caughtEmpty) passed++;
  else throw new Error('Test 12 failed: buildLedgerIndex([{}]) did not throw expected malformed error');

  // Test 13 (F-04): Missing or invalid type in ledger entry throws error
  let caughtMissingType = false;
  try {
    buildLedgerIndex([{ mutantId: 'x1', semanticProof: 'some proof' }]);
  } catch (e) {
    if (e.message.includes('invalid or missing "type"')) caughtMissingType = true;
  }
  if (caughtMissingType) passed++;
  else throw new Error('Test 13 failed: entry with missing type did not throw expected error');

  // Test 14 (F-04): Missing evidence / proof throws error
  let caughtMissingProof = false;
  try {
    buildLedgerIndex([{ mutantId: 'x1', type: 'EQUIVALENT' }]);
  } catch (e) {
    if (e.message.includes('missing required evidence')) caughtMissingProof = true;
  }
  if (caughtMissingProof) passed++;
  else throw new Error('Test 14 failed: entry lacking proof did not throw expected error');

  // Test 15 (F-02): Timeouts with location-only (no mutantId) must throw error
  let caughtLocationOnlyTimeout = false;
  try {
    buildLedgerIndex([{
      file: 'com.chessprobe.service.AdaptivePuzzleSchedulingPolicy',
      line: 55,
      type: 'PROVEN_INFINITE_LOOP',
      semanticProof: 'Infinite loop on line 55'
    }]);
  } catch (e) {
    if (e.message.includes('timeouts require an exact non-empty "mutantId"') && e.message.includes('Location-only matching is forbidden')) {
      caughtLocationOnlyTimeout = true;
    }
  }
  if (caughtLocationOnlyTimeout) passed++;
  else throw new Error('Test 15 failed: location-only timeout did not throw forbidden error');

  // Test 16 (F-02): Conflicting ledger entries throw error
  let caughtConflicting = false;
  try {
    buildLedgerIndex([
      { mutantId: 'm1', type: 'EQUIVALENT', semanticProof: 'Equiv proof' },
      { mutantId: 'm1', type: 'PROVEN_INFINITE_LOOP', semanticProof: 'Loop proof' }
    ]);
  } catch (e) {
    if (e.message.includes('Conflicting ledger entries')) caughtConflicting = true;
  }
  if (caughtConflicting) passed++;
  else throw new Error('Test 16 failed: conflicting ledger entries did not throw error');

  // Test 17 (F-02): Duplicate ledger entries throw error
  let caughtDuplicate = false;
  try {
    buildLedgerIndex([
      { mutantId: 'm1', type: 'EQUIVALENT', semanticProof: 'Proof 1' },
      { mutantId: 'm1', type: 'EQUIVALENT', semanticProof: 'Proof 2' }
    ]);
  } catch (e) {
    if (e.message.includes('Duplicate ledger entry')) caughtDuplicate = true;
  }
  if (caughtDuplicate) passed++;
  else throw new Error('Test 17 failed: duplicate ledger entry did not throw error');

  // Test 18 (F-02): Location-only does NOT match Pitest TIMED_OUT at runtime
  // Even if an equivalent entry exists for the same class and line, a timeout must not be proven by location
  const ledgerLocationOnlyEquivalent = [{
    file: 'com.chessprobe.service.AdaptivePuzzleSchedulingPolicy',
    line: 55,
    type: 'EQUIVALENT',
    semanticProof: 'Equivalence on line 55'
  }];
  const pitestLocationOnlyTest = parsePitestXml(pitestXmlTimeout, ledgerLocationOnlyEquivalent);
  if (pitestLocationOnlyTest.unresolvedTimeouts === 1 && pitestLocationOnlyTest.timedOutProven === 0) passed++;
  else throw new Error('Test 18 failed: location-only matching proved a timeout!');

  // Test 19 (F-01): Calibration rejects single-run or < 3 campaigns
  let caughtSingleRunCalib = false;
  try {
    calibrateCampaigns({ strykerPaths: ['c1.json'], pitestPaths: ['c1.xml'] });
  } catch (e) {
    if (e.message.includes('requires exactly 3 distinct baseline campaigns') && e.message.includes('Single-run calibration is rejected')) {
      caughtSingleRunCalib = true;
    }
  }
  if (caughtSingleRunCalib) passed++;
  else throw new Error('Test 19 failed: single-run calibration did not throw error');

  // Test 20 (F-01): Calibration rejects duplicate campaigns (identical SHA-256)
  const tmpDir = path.resolve('node_modules/.tmp-eval-test');
  fs.mkdirSync(tmpDir, { recursive: true });

  const strykerReportValid1 = JSON.stringify({
    files: { 'src/a.ts': { mutants: [{ id: '1', status: 'Killed' }, { id: '2', status: 'Survived' }] } }
  });
  const strykerReportValid2 = JSON.stringify({
    files: { 'src/a.ts': { mutants: [{ id: '1', status: 'Killed' }, { id: '2', status: 'Killed' }] } }
  });
  const strykerReportValid3 = JSON.stringify({
    files: { 'src/a.ts': { mutants: [{ id: '1', status: 'Killed' }, { id: '2', status: 'Killed' }, { id: '3', status: 'Killed' }] } }
  });

  const pitestReportValid1 = `<mutations><mutation detected='true' status='KILLED'><mutatedClass>C</mutatedClass><lineNumber>1</lineNumber><mutator>M</mutator></mutation></mutations>`;
  const pitestReportValid2 = `<mutations><mutation detected='true' status='KILLED'><mutatedClass>C</mutatedClass><lineNumber>2</lineNumber><mutator>M</mutator></mutation></mutations>`;
  const pitestReportValid3 = `<mutations><mutation detected='true' status='KILLED'><mutatedClass>C</mutatedClass><lineNumber>3</lineNumber><mutator>M</mutator></mutation></mutations>`;

  const sFile1 = path.join(tmpDir, 's1.json');
  const sFile2 = path.join(tmpDir, 's2.json');
  const sFile3 = path.join(tmpDir, 's3.json');
  const pFile1 = path.join(tmpDir, 'p1.xml');
  const pFile2 = path.join(tmpDir, 'p2.xml');
  const pFile3 = path.join(tmpDir, 'p3.xml');

  fs.writeFileSync(sFile1, strykerReportValid1);
  fs.writeFileSync(sFile2, strykerReportValid2);
  fs.writeFileSync(sFile3, strykerReportValid3);
  fs.writeFileSync(pFile1, pitestReportValid1);
  fs.writeFileSync(pFile2, pitestReportValid2);
  fs.writeFileSync(pFile3, pitestReportValid3);

  // Provide duplicate files for Stryker
  let caughtDuplicateCalib = false;
  try {
    calibrateCampaigns({
      strykerPaths: [sFile1, sFile1, sFile3],
      pitestPaths: [pFile1, pFile2, pFile3]
    });
  } catch (e) {
    if (e.message.includes('Duplicate Stryker campaign detected')) caughtDuplicateCalib = true;
  }
  if (caughtDuplicateCalib) passed++;
  else throw new Error('Test 20 failed: duplicate campaign content did not throw error');

  // Test 21 (F-01): Calibration rejects incomplete / empty campaign report (0 mutants)
  const sEmptyFile = path.join(tmpDir, 'sEmpty.json');
  fs.writeFileSync(sEmptyFile, JSON.stringify({ files: {} }));
  let caughtEmptyReport = false;
  try {
    calibrateCampaigns({
      strykerPaths: [sFile1, sEmptyFile, sFile3],
      pitestPaths: [pFile1, pFile2, pFile3]
    });
  } catch (e) {
    if (e.message.includes('report contains 0 mutants')) caughtEmptyReport = true;
  }
  if (caughtEmptyReport) passed++;
  else throw new Error('Test 21 failed: empty report did not throw error');

  // Test 22 (F-01): Valid 3-campaign calibration computes empirical baselines and floors
  const calibConfig = calibrateCampaigns({
    strykerPaths: [sFile1, sFile2, sFile3],
    pitestPaths: [pFile1, pFile2, pFile3]
  });

  // s1: 1 killed, 1 survived = 50.0%
  // s2: 2 killed = 100.0%
  // s3: 3 killed = 100.0%
  // Mean = (50.0 + 100.0 + 100.0) / 3 = 83.33%
  // Stryker floor = max(60.0, 83.33 - 5.0) = 78.3%
  if (
    calibConfig.status === 'EMPIRICALLY_CALIBRATED' &&
    calibConfig.calibrated === true &&
    calibConfig.campaigns.length === 3 &&
    calibConfig.stryker.baselineAdjustedScore === 83.33 &&
    calibConfig.stryker.thresholdFloor === 78.3 &&
    calibConfig.pitest.baselineAdjustedScore === 100.0 &&
    calibConfig.pitest.thresholdFloor === 95.0 &&
    calibConfig.campaigns[0].stryker.sha256.length === 64
  ) {
    passed++;
  } else {
    throw new Error(`Test 22 failed: unexpected calibration config: ${JSON.stringify(calibConfig)}`);
  }

  // Cleanup tmp dir
  fs.rmSync(tmpDir, { recursive: true, force: true });

  console.log(`All ${passed} self-tests passed successfully!`);
  return true;
}

export function main() {
  const args = parseArgs(process.argv);

  if (args.help) {
    printHelp();
    process.exit(0);
  }

  if (args.test) {
    runSelfTests();
    process.exit(0);
  }

  // Load verified ledger if present
  let ledger = [];
  if (fs.existsSync(args.ledgerPath)) {
    try {
      ledger = JSON.parse(fs.readFileSync(args.ledgerPath, 'utf8'));
    } catch (e) {
      console.error(`ERROR: Failed to parse ledger at ${args.ledgerPath}: ${e.message}`);
      process.exit(1);
    }
  }

  // Validate ledger early
  try {
    buildLedgerIndex(ledger);
  } catch (e) {
    console.error(`ERROR: Ledger validation failed: ${e.message}`);
    process.exit(1);
  }

  if (args.calibrate) {
    try {
      const config = calibrateCampaigns({
        strykerPaths: args.strykerPaths,
        pitestPaths: args.pitestPaths,
        ledger,
        configPath: args.configPath,
      });
      console.log(`Successfully calibrated and persisted empirical thresholds across 3 baseline campaigns to ${args.configPath}:`);
      console.log(`  Stryker baseline: ${config.stryker.baselineAdjustedScore}%, floor: ${config.stryker.thresholdFloor}% (min: ${config.stryker.minScore}%, max: ${config.stryker.maxScore}%)`);
      console.log(`  Pitest  baseline: ${config.pitest.baselineAdjustedScore}%, floor: ${config.pitest.thresholdFloor}% (min: ${config.pitest.minScore}%, max: ${config.pitest.maxScore}%)`);
      process.exit(0);
    } catch (e) {
      console.error(`ERROR [Calibration]: ${e.message}`);
      process.exit(1);
    }
  }

  // Verify input report existence for single-run evaluation
  if (!fs.existsSync(args.strykerPath)) {
    console.error(`ERROR: Stryker report not found at ${args.strykerPath}`);
    process.exit(1);
  }
  if (!fs.existsSync(args.pitestPath)) {
    console.error(`ERROR: Pitest report not found at ${args.pitestPath}`);
    process.exit(1);
  }

  let strykerData;
  let pitestData;
  try {
    strykerData = parseStrykerJson(fs.readFileSync(args.strykerPath, 'utf8'), ledger);
  } catch (e) {
    console.error(`ERROR [Stryker Parse]: ${e.message}`);
    process.exit(1);
  }

  try {
    pitestData = parsePitestXml(fs.readFileSync(args.pitestPath, 'utf8'), ledger);
  } catch (e) {
    console.error(`ERROR [Pitest Parse]: ${e.message}`);
    process.exit(1);
  }

  const strykerScore = calculateAdjustedScore(strykerData);
  const pitestScore = calculateAdjustedScore(pitestData);

  if (!strykerScore.ok) {
    console.error(`ERROR [Stryker]: ${strykerScore.reason}`);
    process.exit(1);
  }
  if (!pitestScore.ok) {
    console.error(`ERROR [Pitest]: ${pitestScore.reason}`);
    process.exit(1);
  }

  console.log(`Calculated Adjusted Scores:
  Stryker: ${strykerScore.score}% (Killed: ${strykerData.killed}, TimedOutProven: ${strykerData.timedOutProven}, Survived: ${strykerData.survived}, NoCoverage: ${strykerData.noCoverage}, Ignored: ${strykerData.ignored})
  Pitest:  ${pitestScore.score}% (Killed: ${pitestData.killed}, TimedOutProven: ${pitestData.timedOutProven}, Survived: ${pitestData.survived}, NoCoverage: ${pitestData.noCoverage})`);

  // Load persisted thresholds
  if (!fs.existsSync(args.configPath)) {
    console.error(`ERROR: Mutation threshold config not found at ${args.configPath}. Missing calibration evidence.`);
    process.exit(1);
  }

  let config;
  try {
    config = JSON.parse(fs.readFileSync(args.configPath, 'utf8'));
  } catch (e) {
    console.error(`ERROR: Failed to parse config at ${args.configPath}: ${e.message}`);
    process.exit(1);
  }

  const isProvisional = config.status === 'PROVISIONAL_BOOTSTRAP' || config.calibrated === false;
  if (isProvisional && !args.allowProvisional && args.strykerMin === undefined && args.pitestMin === undefined) {
    console.error(`ERROR: Mutation threshold calibration is explicitly incomplete (status: PROVISIONAL_BOOTSTRAP).
Nightly enforcement requires empirically calibrated thresholds.
Execute 3 baseline campaigns and run with --calibrate to establish empirical floors, or explicitly supply --allow-provisional for non-blocking bootstrap testing.`);
    process.exit(1);
  }

  if (isProvisional) {
    console.warn(`WARNING: Running with PROVISIONAL_BOOTSTRAP thresholds. Empirical calibration is explicitly incomplete.`);
  }

  const strykerMin = args.strykerMin ?? config.stryker?.thresholdFloor ?? (isProvisional ? config.stryker?.provisionalFloor : undefined);
  const pitestMin = args.pitestMin ?? config.pitest?.thresholdFloor ?? (isProvisional ? config.pitest?.provisionalFloor : undefined);

  if (typeof strykerMin !== 'number' || typeof pitestMin !== 'number' || strykerMin <= 0 || pitestMin <= 0) {
    console.error(`ERROR: Invalid or uncalibrated threshold floor in ${args.configPath}: strykerMin=${strykerMin}, pitestMin=${pitestMin}`);
    process.exit(1);
  }

  let failed = false;
  if (strykerScore.score < strykerMin) {
    console.error(`FAILURE: Stryker score ${strykerScore.score}% is below threshold floor ${strykerMin}%`);
    failed = true;
  }
  if (pitestScore.score < pitestMin) {
    console.error(`FAILURE: Pitest score ${pitestScore.score}% is below threshold floor ${pitestMin}%`);
    failed = true;
  }

  if (failed) {
    process.exit(1);
  }

  console.log(`SUCCESS: Both mutation suites meet or exceed ${isProvisional ? 'provisional' : 'calibrated'} threshold floors (Stryker >= ${strykerMin}%, Pitest >= ${pitestMin}%).`);
  process.exit(0);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  main();
}
