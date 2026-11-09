import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { Header } from '../components/Header';

describe('Header Navigation Redesign (#735)', () => {
  const defaultProps = {
    activeTab: 'weaknesses' as const,
    setActiveTab: vi.fn(),
    sessionStatus: 'unauthenticated' as const,
    accountStatus: 'unconnected' as const,
  };

  it('renders 3 core primary tabs on desktop', () => {
    render(<Header {...defaultProps} />);

    // Recurring Weaknesses tab
    const weaknessTab = screen.getByRole('button', { name: 'Recurring Weaknesses' });
    expect(weaknessTab).toBeInTheDocument();
    expect(weaknessTab).toHaveTextContent(/Recurring Weaknesses/i);

    // Practice Puzzles tab
    expect(screen.getByRole('button', { name: /Practice Puzzles/i })).toBeInTheDocument();

    // Import Games tab
    expect(screen.getByRole('button', { name: /Import Games/i })).toBeInTheDocument();
  });

  it('demotes Decision Timing and Similar Positions from flat primary tabs into secondary tools dropdown', () => {
    render(<Header {...defaultProps} />);

    // The secondary dropdown trigger should be present
    const toolsTrigger = screen.getByRole('button', { name: 'Explore / Tools' });
    expect(toolsTrigger).toBeInTheDocument();

    // Initially, dropdown menu items are closed/not visible
    expect(screen.queryByRole('menuitem', { name: /Decision Timing/i })).not.toBeInTheDocument();

    // Clicking trigger opens the dropdown menu
    fireEvent.click(toolsTrigger);
    expect(screen.getByRole('menu')).toBeInTheDocument();

    const dtOption = screen.getByRole('menuitem', { name: /Decision Timing/i });
    const spOption = screen.getByRole('menuitem', { name: /Similar Positions/i });
    const exploreOption = screen.getByRole('menuitem', { name: /Explore a position/i });

    expect(dtOption).toBeInTheDocument();
    expect(spOption).toBeInTheDocument();
    expect(exploreOption).toBeInTheDocument();
  });

  it('switches to long-decisions when selected from the tools dropdown', () => {
    const setActiveTab = vi.fn();
    render(<Header {...defaultProps} setActiveTab={setActiveTab} />);

    const toolsTrigger = screen.getByRole('button', { name: 'Explore / Tools' });
    fireEvent.click(toolsTrigger);

    const dtOption = screen.getByRole('menuitem', { name: /Decision Timing/i });
    fireEvent.click(dtOption);

    expect(setActiveTab).toHaveBeenCalledWith('long-decisions');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('switches to similar-positions when selected from the tools dropdown', () => {
    const setActiveTab = vi.fn();
    render(<Header {...defaultProps} setActiveTab={setActiveTab} />);

    const toolsTrigger = screen.getByRole('button', { name: 'Explore / Tools' });
    fireEvent.click(toolsTrigger);

    const spOption = screen.getByRole('menuitem', { name: /Similar Positions/i });
    fireEvent.click(spOption);

    expect(setActiveTab).toHaveBeenCalledWith('similar-positions');
  });

  it('opens ExplorePositionModal when selected from dropdown or standalone button', () => {
    render(<Header {...defaultProps} />);

    const toolsTrigger = screen.getByRole('button', { name: 'Explore / Tools' });
    fireEvent.click(toolsTrigger);

    const exploreOption = screen.getByRole('menuitem', { name: /Explore a position/i });
    fireEvent.click(exploreOption);

    expect(screen.getByRole('dialog', { name: /Explore a position|Explore Position/i })).toBeInTheDocument();
  });

  it('highlights the tools dropdown when an exploratory tab is active', () => {
    render(<Header {...defaultProps} activeTab="long-decisions" />);

    const toolsTrigger = screen.getByRole('button', { name: 'Explore / Tools' });
    expect(toolsTrigger.className).toMatch(/border-primary|text-primary/);
  });

  it('eliminates redundant standalone desktop Explore button', () => {
    render(<Header {...defaultProps} />);
    // The standalone button outside the dropdown should not exist
    expect(screen.queryByRole('button', { name: 'Explore a position' })).not.toBeInTheDocument();
  });

  it('provides complete mobile navigation exposing all primary and secondary views', () => {
    const setActiveTab = vi.fn();
    render(<Header {...defaultProps} setActiveTab={setActiveTab} weaknessCount={5} />);

    const mobileMenuTrigger = screen.getByRole('button', { name: /Mobile navigation/i });
    expect(mobileMenuTrigger).toBeInTheDocument();

    fireEvent.click(mobileMenuTrigger);

    // Primary tabs accessible in mobile menu
    const mobileWeaknesses = screen.getByRole('menuitem', { name: /Recurring Weaknesses/i });
    const mobilePuzzles = screen.getByRole('menuitem', { name: /Practice Puzzles/i });
    const mobileImport = screen.getByRole('menuitem', { name: /Import Games/i });

    expect(mobileWeaknesses).toBeInTheDocument();
    expect(mobileWeaknesses).toHaveTextContent('5'); // badge
    expect(mobilePuzzles).toBeInTheDocument();
    expect(mobileImport).toBeInTheDocument();

    // Secondary tools accessible in mobile menu
    expect(screen.getByRole('menuitem', { name: /Explore a position/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Decision Timing/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Similar Positions/i })).toBeInTheDocument();

    // Clicking a primary tab closes menu and activates view
    fireEvent.click(mobilePuzzles);
    expect(setActiveTab).toHaveBeenCalledWith('puzzles');
    expect(screen.queryByRole('menuitem', { name: /Practice Puzzles/i })).not.toBeInTheDocument();
  });
});
