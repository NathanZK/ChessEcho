import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MonthPicker } from '../components/MonthPicker';

describe('MonthPicker Responsive Clamping and Semantic Design Tokens', () => {
  const originalInnerWidth = window.innerWidth;
  const originalInnerHeight = window.innerHeight;

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 375 });
    Object.defineProperty(window, 'innerHeight', { writable: true, configurable: true, value: 667 });
  });

  afterEach(() => {
    Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: originalInnerWidth });
    Object.defineProperty(window, 'innerHeight', { writable: true, configurable: true, value: originalInnerHeight });
    vi.restoreAllMocks();
  });

  it('clamps popover left position within mobile viewport (375px) bounds when trigger is near the right edge', () => {
    const handleChange = vi.fn();
    render(<MonthPicker label="To month" value="2026-08" onChange={handleChange} />);

    const trigger = screen.getByRole('button', { name: /to month/i });

    // Mock trigger's bounding client rect to simulate being on the right side of a 375px mobile screen
    vi.spyOn(trigger, 'getBoundingClientRect').mockReturnValue({
      top: 100,
      bottom: 140,
      left: 200,
      right: 350,
      width: 150,
      height: 40,
      x: 200,
      y: 100,
      toJSON: () => {},
    });

    // Open popover
    fireEvent.click(trigger);

    const dialog = screen.getByRole('dialog', { name: /to month picker/i });
    expect(dialog).toBeInTheDocument();

    // Mock popover element size
    Object.defineProperty(dialog, 'offsetWidth', { configurable: true, value: 256 });
    Object.defineProperty(dialog, 'offsetHeight', { configurable: true, value: 280 });

    // Trigger repositioning (e.g. resize / animation frame)
    fireEvent(window, new Event('resize'));

    // Clamped left bound: viewportWidth (375) - popoverWidth (256) - 8 = 111px
    expect(dialog.style.left).toBe('111px');
    expect(dialog.style.position).toBe('fixed');
  });

  it('uses semantic tokens for trigger, popover container, and month cells', () => {
    const handleChange = vi.fn();
    render(<MonthPicker label="From month" value="2026-08" onChange={handleChange} />);

    const trigger = screen.getByRole('button', { name: /from month/i });
    expect(trigger.className).toContain('bg-surface-container-lowest');
    expect(trigger.className).toContain('border-outline-variant');

    // Open popover
    fireEvent.click(trigger);

    const dialog = screen.getByRole('dialog', { name: /from month picker/i });
    expect(dialog.className).toContain('bg-surface-container-low');
    expect(dialog.className).toContain('border-outline-variant');
    expect(dialog.className).toContain('max-w-[calc(100vw-1rem)]');

    // Selected month (August) should use primary container tokens
    const augBtn = screen.getByRole('button', { name: 'Aug' });
    expect(augBtn.className).toContain('bg-primary-container');
    expect(augBtn.className).toContain('text-on-primary-container');

    // Unselected month (July) should use surface-container-lowest and on-surface-variant
    const julBtn = screen.getByRole('button', { name: 'Jul' });
    expect(julBtn.className).toContain('bg-surface-container-lowest');
    expect(julBtn.className).toContain('text-on-surface-variant');
  });
});
