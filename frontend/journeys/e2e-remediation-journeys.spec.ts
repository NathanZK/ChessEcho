import { test, expect } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';

const SCREENSHOT_DIR = path.resolve(__dirname, 'artifacts/screenshots');

test.beforeAll(async () => {
  if (!fs.existsSync(SCREENSHOT_DIR)) {
    fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
  }
});

test.describe('End-to-End Remediation Verification Suite (Issue #801)', () => {
  test.describe.configure({ mode: 'serial' });

  const testUserEmail = `e2e_auditor_${Date.now()}@example.com`;
  const testPassword = 'Password123!';
  const chessComUsername = 'e2e_player';

  test('full remediation journey against real Docker backend and test double', async ({ page }) => {
    test.setTimeout(120_000);
    // -------------------------------------------------------------------------
    // 1. REGISTRATION
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 1: Initializing session bootstrap and navigating to /register');
    await page.goto('/');
    await expect(page.getByRole('link', { name: /Register/i })).toBeVisible({ timeout: 10_000 });

    await page.goto('/register');
    await expect(page.getByRole('heading', { name: /Create your ChessProbe account/i })).toBeVisible();

    await page.getByLabel(/Email/i).fill(testUserEmail);
    await page.getByLabel(/^Password/i).fill(testPassword);
    await page.getByRole('button', { name: /Create account/i }).click();

    // Verify redirect to root and authenticated state
    await page.waitForURL('/');
    await expect(page.getByText('Signed in to ChessProbe').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: /Sign out/i })).toBeVisible();

    // Checkpoint 1: Registration Success Screenshot
    const screenshot1Path = path.join(SCREENSHOT_DIR, 'e2e-01-registration-success.png');
    await page.screenshot({ path: screenshot1Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot1Path}`);

    // -------------------------------------------------------------------------
    // 2. LOGOUT, LOGIN, AND HARD RELOAD (SESSION PERSISTENCE)
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 2: Logging out');
    await page.getByRole('button', { name: /Sign out/i }).click();
    await expect(page.getByRole('link', { name: /Sign In/i })).toBeVisible({ timeout: 10_000 });

    console.log('[E2E] Navigating to /login');
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: /Sign in to ChessProbe/i })).toBeVisible();

    await page.getByLabel(/Email/i).fill(testUserEmail);
    await page.getByLabel(/^Password/i).fill(testPassword);
    await page.getByRole('button', { name: /Sign in/i }).click();

    await page.waitForURL('/');
    await expect(page.getByText('Signed in to ChessProbe').first()).toBeVisible({ timeout: 15_000 });

    // Hard reload to verify cookie/session persistence across refreshes
    console.log('[E2E] Executing hard reload');
    await page.reload();
    await expect(page.getByText('Signed in to ChessProbe').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: /Sign out/i })).toBeVisible();

    // Checkpoint 2: Login and Reload Persisted Screenshot
    const screenshot2Path = path.join(SCREENSHOT_DIR, 'e2e-02-login-and-reload-persisted.png');
    await page.screenshot({ path: screenshot2Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot2Path}`);

    // -------------------------------------------------------------------------
    // 3. CONNECT ACCOUNT & IMPORT DETERMINISTIC ARCHIVE
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 3: Navigating to Import Games tab');
    const importNavTab = page.getByRole('button', { name: /Import Games/i });
    if (await importNavTab.isVisible()) {
      await importNavTab.click();
    } else {
      await page.goto('/#import');
    }

    const usernameInput = page.getByPlaceholder(/e\.g\. hikaru/i).or(page.locator('input[type="text"]').first());
    await expect(usernameInput).toBeVisible({ timeout: 10_000 });
    await usernameInput.fill(chessComUsername);

    // Connect Chess.com Account
    console.log('[E2E] Clicking Connect Chess.com account');
    const connectButton = page.getByRole('button', { name: /Connect Chess\.com account/i });
    await expect(connectButton).toBeVisible({ timeout: 10_000 });
    await connectButton.click();

    // Start Import
    console.log('[E2E] Starting import job from test double');
    const startImportButton = page.getByRole('button', { name: /Start Import/i });
    await expect(startImportButton).toBeEnabled({ timeout: 15_000 });
    await startImportButton.click();

    // Wait for the import job to process and complete
    console.log('[E2E] Polling for import completion...');
    await expect(page.getByText(/Import completed|Completed/i).first()).toBeVisible({ timeout: 45_000 });

    // Checkpoint 3: Import Complete Screenshot
    const screenshot3Path = path.join(SCREENSHOT_DIR, 'e2e-03-import-complete.png');
    await page.screenshot({ path: screenshot3Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot3Path}`);

    // -------------------------------------------------------------------------
    // 4. WEAKNESSES VIEW & POPULATION
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 4: Navigating to Exact-Position Analysis tab');
    const toolsButtonNav = page.getByRole('button', { name: /Explore \/ Tools/i });
    if (await toolsButtonNav.isVisible()) {
      await toolsButtonNav.click();
      await page.getByRole('menuitem', { name: /Exact-Position Analysis/i }).click();
    } else {
      await page.goto('/#weakness-analysis');
    }

    // Wait for weakness cards to load
    const practiceButton = page.getByRole('button', { name: /Practice Position/i }).first();
    await expect(practiceButton).toBeVisible({ timeout: 20_000 });

    // Checkpoint 4: Weaknesses Populated Screenshot
    const screenshot4Path = path.join(SCREENSHOT_DIR, 'e2e-04-weaknesses-populated.png');
    await page.screenshot({ path: screenshot4Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot4Path}`);

    // -------------------------------------------------------------------------
    // 5. HISTORICAL GAMES MODAL & KEYBOARD FOCUS RESTORATION
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 5: Testing Historical Games Modal accessibility');
    const sourceGamesTrigger = page.getByRole('button', { name: /View Games/i }).first();
    await expect(sourceGamesTrigger).toBeVisible({ timeout: 10_000 });
    await sourceGamesTrigger.click();

    // Verify modal dialog structure
    const modalDialog = page.getByRole('dialog');
    await expect(modalDialog).toBeVisible({ timeout: 10_000 });
    await expect(modalDialog).toHaveAttribute('aria-modal', 'true');
    await expect(modalDialog).toHaveAttribute('aria-labelledby');

    // Verify source game links from our fixture
    await expect(page.getByText(/https:\/\/www\.chess\.com\/game\/live\/10000000001/i)).toBeVisible();

    // Checkpoint 5: Historical Games Modal Screenshot
    const screenshot5Path = path.join(SCREENSHOT_DIR, 'e2e-05-historical-games-history.png');
    await page.screenshot({ path: screenshot5Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot5Path}`);

    // Dismiss with Escape key
    console.log('[E2E] Pressing Escape to dismiss modal');
    await page.keyboard.press('Escape');
    await expect(modalDialog).not.toBeVisible({ timeout: 5_000 });

    // Verify focus restoration to the trigger element
    await expect(sourceGamesTrigger).toBeFocused();

    // Checkpoint 6: Modal Dismiss and Focus Restoration Screenshot
    const screenshot6Path = path.join(SCREENSHOT_DIR, 'e2e-06-modal-keyboard-dismiss-verified.png');
    await page.screenshot({ path: screenshot6Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot6Path}`);

    // -------------------------------------------------------------------------
    // 6. PUZZLE PRACTICE & MOVE EXECUTION
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 6: Entering practice from weakness card');
    await practiceButton.click();
    await expect(page).toHaveURL(/#puzzles/, { timeout: 10_000 });

    // Verify chessboard is visible
    const b8Square = page.locator('[data-square="b8"]');
    const c6Square = page.locator('[data-square="c6"]');
    await expect(b8Square).toBeVisible({ timeout: 10_000 });

    // Checkpoint 7: Interactive Puzzle Practice Screenshot
    const screenshot7Path = path.join(SCREENSHOT_DIR, 'e2e-07-interactive-puzzle-practice.png');
    await page.screenshot({ path: screenshot7Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot7Path}`);

    // Execute move: Nc6 (b8 -> c6)
    console.log('[E2E] Executing move b8 -> c6');
    await b8Square.click();
    await c6Square.click();

    // -------------------------------------------------------------------------
    // 7. LINE EXPLORATION WORKBENCH
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 7: Entering Line Exploration');
    const continueExplorationBtn = page.getByRole('button', { name: /Continue Exploration →|Explore Lines/i }).first();
    if (await continueExplorationBtn.isVisible({ timeout: 5_000 })) {
      await continueExplorationBtn.click();
    }

    // Verify LineExplorationWorkbench rendered (or exploration active)
    await expect(page.getByTitle(/Exit Exploration/i).or(page.getByRole('button', { name: /Exit Exploration/i }))).toBeVisible({ timeout: 10_000 });

    // Checkpoint 8: Line Exploration Workbench Active Screenshot
    const screenshot8Path = path.join(SCREENSHOT_DIR, 'e2e-08-line-exploration-workbench-active.png');
    await page.screenshot({ path: screenshot8Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot8Path}`);

    // Exit Exploration
    const exitExplorationBtn = page.getByTitle(/Exit Exploration/i).or(page.getByRole('button', { name: /Exit Exploration/i }));
    await exitExplorationBtn.click();

    // -------------------------------------------------------------------------
    // 8. SIMILAR POSITIONS VIEW
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 8: Navigating to Similar Positions');
    const toolsButton = page.getByRole('button', { name: /Explore \/ Tools/i });
    if (await toolsButton.isVisible()) {
      await toolsButton.click();
      await page.getByRole('menuitem', { name: /Similar Positions/i }).click();
    } else {
      await page.goto('/#similar-positions');
    }

    await expect(page).toHaveURL(/#similar-positions/, { timeout: 10_000 });
    await expect(page.getByText('Find Similar Personal Positions')).toBeVisible({ timeout: 10_000 });

    // Click sample position "Italian Game" to search
    const italianGameBtn = page.getByRole('button', { name: /Italian Game/i });
    if (await italianGameBtn.isVisible()) {
      await italianGameBtn.click();
    }

    // Checkpoint 9: Similar Positions Screenshot
    const screenshot9Path = path.join(SCREENSHOT_DIR, 'e2e-09-similar-positions.png');
    await page.screenshot({ path: screenshot9Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot9Path}`);

    // -------------------------------------------------------------------------
    // 9. DECISION TIMING / LONG DECISIONS VIEW
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 9: Navigating to Decision Timing');
    const toolsButton2 = page.getByRole('button', { name: /Explore \/ Tools/i });
    if (await toolsButton2.isVisible()) {
      await toolsButton2.click();
      await page.getByRole('menuitem', { name: /Decision Timing/i }).click();
    } else {
      await page.goto('/#long-decisions');
    }

    await expect(page).toHaveURL(/#long-decisions/, { timeout: 10_000 });
    await expect(page.getByRole('heading', { name: /Decision Timing/i })).toBeVisible({ timeout: 10_000 });

    // Select BLITZ to match our deterministic fixture time control
    const timeControlSelect = page.getByLabel('Time control');
    await expect(timeControlSelect).toBeVisible({ timeout: 10_000 });
    await timeControlSelect.selectOption('BLITZ');

    // Wait for the query to resolve (loading banner disappears, results or occurrences counter updates)
    await expect(page.getByText('Searching long-decision occurrences')).not.toBeVisible({ timeout: 15_000 });

    // Checkpoint 10: Long Decisions Screenshot
    const screenshot10Path = path.join(SCREENSHOT_DIR, 'e2e-10-long-decisions.png');
    await page.screenshot({ path: screenshot10Path, fullPage: true });
    console.log(`[E2E] Saved ${screenshot10Path}`);

    console.log('[E2E] All 10 journey checkpoints successfully executed and captured!');

    // -------------------------------------------------------------------------
    // 10. BACKEND API & PERSISTENCE VERIFICATION
    // -------------------------------------------------------------------------
    console.log('[E2E] Step 10: Verifying backend REST API contracts with active session');
    const accountsRes = await page.request.get('http://localhost:8080/api/accounts');
    expect(accountsRes.status()).toBe(200);
    const accounts = await accountsRes.json();
    expect(accounts.length).toBeGreaterThan(0);
    const accountId = accounts[0].id;
    console.log(`[E2E] Discovered connected accountId: ${accountId} for user`);

    const gamesRes = await page.request.get(`http://localhost:8080/api/games?accountId=${accountId}`);
    expect(gamesRes.status()).toBe(200);
    const gamesData = await gamesRes.json();
    expect(gamesData.content.length).toBe(5);

    const weaknessesRes = await page.request.get(`http://localhost:8080/api/positions/weaknesses?accountId=${accountId}&playerColor=BLACK`);
    expect(weaknessesRes.status()).toBe(200);
    const weaknessesData = await weaknessesRes.json();
    expect(weaknessesData.length).toBeGreaterThan(0);
    expect(weaknessesData[0].fen).toContain('rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R');
    expect(weaknessesData[0].movesPlayed[0].move).toBe('f6');
    console.log(`[E2E] Verified ${weaknessesData.length} persisted weakness cluster(s) with Damiano blunder 2... f6 in backend for accountId ${accountId}`);
  });

  test('provider failure during import transitions job to FAILED with error status', async ({ page }) => {
    test.setTimeout(60_000);
    const failUserEmail = `e2e_fail_${Date.now()}@example.com`;

    console.log('[E2E-Failure] Navigating directly to /register in fresh browser context (CSRF direct-entry verification)');
    await page.goto('/register');
    await expect(page.getByRole('heading', { name: /Create your ChessProbe account/i })).toBeVisible({ timeout: 10_000 });
    await page.getByLabel(/Email/i).fill(failUserEmail);
    await page.getByLabel(/^Password/i).fill(testPassword);
    await page.getByRole('button', { name: /Create account/i }).click();
    await page.waitForURL('/');
    await expect(page.getByText('Signed in to ChessProbe').first()).toBeVisible({ timeout: 15_000 });

    console.log('[E2E-Failure] Navigating to Import Games tab');
    const importNavTab = page.getByRole('button', { name: /Import Games/i });
    if (await importNavTab.isVisible()) {
      await importNavTab.click();
    } else {
      await page.goto('/#import');
    }

    const usernameInput = page.getByPlaceholder(/e\.g\. hikaru/i).or(page.locator('input[type="text"]').first());
    await expect(usernameInput).toBeVisible({ timeout: 10_000 });
    await usernameInput.fill('provider_fail_player');

    console.log('[E2E-Failure] Connecting provider_fail_player account');
    const connectButton = page.getByRole('button', { name: /Connect Chess\.com account/i });
    await expect(connectButton).toBeVisible({ timeout: 10_000 });
    await connectButton.click();

    console.log('[E2E-Failure] Triggering import expected to fail at upstream provider');
    const startImportButton = page.getByRole('button', { name: /Start Import/i });
    await expect(startImportButton).toBeEnabled({ timeout: 15_000 });
    await startImportButton.click();

    // Verify the UI transitions to FAILED status
    console.log('[E2E-Failure] Polling for FAILED status in UI');
    await expect(page.getByText(/Import Job Failed|FAILED/i).first()).toBeVisible({ timeout: 30_000 });

    const screenshotFailPath = path.join(SCREENSHOT_DIR, 'e2e-11-provider-failure-handled.png');
    await page.screenshot({ path: screenshotFailPath, fullPage: true });
    console.log(`[E2E-Failure] Saved ${screenshotFailPath}`);
  });

  test('direct entry to /login in fresh browser context bootstraps CSRF and logs in successfully', async ({ page }) => {
    test.setTimeout(60_000);
    console.log('[E2E-DirectLogin] Navigating directly to /login in fresh browser context');
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: /Sign in to ChessProbe/i })).toBeVisible({ timeout: 10_000 });

    await page.getByLabel(/Email/i).fill(testUserEmail);
    await page.getByLabel(/^Password/i).fill(testPassword);
    await page.getByRole('button', { name: /Sign in/i }).click();

    await page.waitForURL('/');
    await expect(page.getByText('Signed in to ChessProbe').first()).toBeVisible({ timeout: 15_000 });
    console.log('[E2E-DirectLogin] Direct login in fresh browser context verified successfully');
  });
});
