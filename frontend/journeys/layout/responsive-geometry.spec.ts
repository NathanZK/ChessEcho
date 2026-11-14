import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_PUZZLE, DEFAULT_MOCK_WEAKNESS } from '../fixtures/mockApi';

test.describe('Responsive Viewport Geometry Harness', () => {
  test('at 375x667 mobile viewport, primary navigation is accessible inside mobile drawer (AUD-20)', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });

    await setupJourneyApiMocks(page, {
      authenticated: true,
      weaknesses: [DEFAULT_MOCK_WEAKNESS],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    await page.goto('/#weaknesses', { waitUntil: 'domcontentloaded' });

    // 1. Verify desktop navigation links are hidden on mobile
    const desktopNav = page.locator('header nav.hidden.md\\:flex');
    await expect(desktopNav).toBeHidden();

    // 2. Locate and open mobile navigation menu drawer
    const mobileMenuTrigger = page.getByRole('button', { name: /Mobile navigation menu/i });
    await expect(mobileMenuTrigger).toBeVisible();
    await mobileMenuTrigger.click();

    // 3. Verify mobile drawer/menu items are accessible
    const mobileMenu = page.getByRole('menu');
    await expect(mobileMenu).toBeVisible();

    const practicePuzzlesItem = mobileMenu.getByRole('menuitem', { name: /Practice Puzzles/i });
    const importGamesItem = mobileMenu.getByRole('menuitem', { name: /Import Games/i });
    const recurringWeaknessesItem = mobileMenu.getByRole('menuitem', { name: /Recurring Weaknesses/i });

    await expect(practicePuzzlesItem).toBeVisible();
    await expect(importGamesItem).toBeVisible();
    await expect(recurringWeaknessesItem).toBeVisible();

    // 4. Click Import Games and verify navigation succeeds
    await importGamesItem.click();
    await expect(page).toHaveURL(/#import/);
    await expect(mobileMenu).toBeHidden();
  });

  test('at 1440x900 desktop viewport, document root maintains zero unwanted vertical scrollbars across primary views (UOBS-06)', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });

    await setupJourneyApiMocks(page, {
      authenticated: true,
      weaknesses: [DEFAULT_MOCK_WEAKNESS],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    const routesToTest = [
      '/#weaknesses',
      '/#puzzles',
      '/#import',
      '/#long-decisions',
      '/#similar-positions',
      '/#game-analysis',
    ];

    for (const route of routesToTest) {
      await page.goto(route, { waitUntil: 'domcontentloaded' });

      // Assert document root has no vertical overflow: scrollHeight <= clientHeight
      const overflow = await page.evaluate(() => {
        const doc = document.documentElement;
        return {
          scrollHeight: doc.scrollHeight,
          clientHeight: doc.clientHeight,
          hasVerticalScrollbar: doc.scrollHeight > doc.clientHeight,
        };
      });

      expect(
        overflow.hasVerticalScrollbar,
        `Expected zero vertical scrollbar on route ${route}, but scrollHeight (${overflow.scrollHeight}) > clientHeight (${overflow.clientHeight})`
      ).toBe(false);
    }
  });

  test('layout geometry maintains board-to-sidebar gap <= 24px and sidebar width >= 340px (UOBS-07)', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });

    await setupJourneyApiMocks(page, {
      authenticated: true,
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });

    const boardColumn = page.locator('[data-testid="practice-board-column"]');
    const sidebarPanel = page.locator('[data-testid="practice-sidebar-panel"]');

    await expect(boardColumn).toBeVisible();
    await expect(sidebarPanel).toBeVisible();

    const boardColBox = await boardColumn.boundingBox();
    const sidebarBox = await sidebarPanel.boundingBox();

    expect(boardColBox).not.toBeNull();
    expect(sidebarBox).not.toBeNull();

    if (boardColBox && sidebarBox) {
      // Gap between right edge of board column and left edge of sidebar panel
      const gap = sidebarBox.x - (boardColBox.x + boardColBox.width);
      expect(gap).toBeLessThanOrEqual(24);

      // Sidebar panel width >= 340px
      expect(sidebarBox.width).toBeGreaterThanOrEqual(340);
    }
  });
});
