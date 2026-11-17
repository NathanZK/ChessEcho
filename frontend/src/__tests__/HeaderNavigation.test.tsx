import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { Header } from '../components/Header';

describe('Header Navigation Redesign (#808)', () => {
  const defaultProps = {
    activeTab: 'weaknesses' as const,
    setActiveTab: vi.fn(),
    sessionStatus: 'unauthenticated' as const,
    accountStatus: 'unconnected' as const,
  };

  it('renders primary desktop navigation with correct order: Weakness, Practice Puzzles, My Games, Import Games, Explore/Tools', () => {
    const { container } = render(<Header {...defaultProps} weaknessCount={3} />);

    const nav = container.querySelector('nav.hidden.md\\:flex');
    expect(nav).toBeInTheDocument();

    const buttons = nav?.querySelectorAll(':scope > button, :scope > div > button');
    expect(buttons).toBeDefined();

    // The order of the top-level desktop navigation triggers:
    // 1. Weakness Dropdown
    // 2. Practice Puzzles
    // 3. My Games
    // 4. Import Games (immediately right of My Games)
    // 5. Explore / Tools (secondary tools dropdown)
    const buttonTexts = Array.from(buttons ?? []).map((b) => b.textContent?.trim());
    expect(buttonTexts[0]).toMatch(/Weakness/i);
    expect(buttonTexts[1]).toMatch(/Practice Puzzles/i);
    expect(buttonTexts[2]).toMatch(/My Games/i);
    expect(buttonTexts[3]).toMatch(/Import Games/i);
    expect(buttonTexts[4]).toMatch(/Explore \/ Tools/i);
  });

  it('renders Weakness direct button with badge and switches to weaknesses on click', () => {
    const setActiveTab = vi.fn();
    render(<Header {...defaultProps} setActiveTab={setActiveTab} weaknessCount={5} />);

    // Weakness direct button trigger
    const weaknessButton = screen.getByRole('button', { name: /Weakness/i });
    expect(weaknessButton).toBeInTheDocument();
    expect(weaknessButton).toHaveTextContent('5'); // badge

    // Clicking trigger switches to weaknesses
    fireEvent.click(weaknessButton);
    expect(setActiveTab).toHaveBeenCalledWith('weaknesses');
  });

  it('switches to weakness-analysis when Exact-Position Analysis is selected from Explore / Tools dropdown', () => {
    const setActiveTab = vi.fn();
    render(<Header {...defaultProps} setActiveTab={setActiveTab} />);

    const toolsTrigger = screen.getByRole('button', { name: 'Explore / Tools' });
    fireEvent.click(toolsTrigger);

    const analysisOption = screen.getByRole('menuitem', { name: /Exact-Position Analysis/i });
    expect(analysisOption).toBeInTheDocument();

    // Prohibition: strictly avoid "Exact Weakness"
    expect(screen.queryByText(/Exact Weakness/i)).not.toBeInTheDocument();

    fireEvent.click(analysisOption);
    expect(setActiveTab).toHaveBeenCalledWith('weakness-analysis');
  });

  it('highlights Weakness button when activeTab is weaknesses, and highlights Explore / Tools when activeTab is weakness-analysis', () => {
    const { rerender } = render(<Header {...defaultProps} activeTab="weaknesses" />);
    const weaknessButton = screen.getByRole('button', { name: /Weakness/i });
    expect(weaknessButton.className).toMatch(/border-primary|text-primary/);

    rerender(<Header {...defaultProps} activeTab="weakness-analysis" />);
    const toolsTrigger = screen.getByRole('button', { name: 'Explore / Tools' });
    expect(toolsTrigger.className).toMatch(/border-primary|text-primary/);
  });

  it('switches to game-analysis when My Games primary tab is clicked', () => {
    const setActiveTab = vi.fn();
    render(<Header {...defaultProps} setActiveTab={setActiveTab} />);

    const myGamesTab = screen.getByRole('button', { name: /My Games/i });
    fireEvent.click(myGamesTab);

    expect(setActiveTab).toHaveBeenCalledWith('game-analysis');
  });

  it('switches to import when Import Games primary tab is clicked', () => {
    const setActiveTab = vi.fn();
    render(<Header {...defaultProps} setActiveTab={setActiveTab} />);

    const importTab = screen.getByRole('button', { name: /Import Games/i });
    fireEvent.click(importTab);

    expect(setActiveTab).toHaveBeenCalledWith('import');
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
    expect(screen.getByRole('menu', { name: /Explore and tools/i })).toBeInTheDocument();

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

  it('opens ExplorePositionModal when selected from dropdown', () => {
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
    expect(screen.queryByRole('button', { name: 'Explore a position' })).not.toBeInTheDocument();
  });

  it('provides complete mobile navigation exposing all primary and secondary views', () => {
    const setActiveTab = vi.fn();
    render(<Header {...defaultProps} setActiveTab={setActiveTab} weaknessCount={5} />);

    const mobileMenuTrigger = screen.getByRole('button', { name: /Mobile navigation/i });
    expect(mobileMenuTrigger).toBeInTheDocument();

    fireEvent.click(mobileMenuTrigger);

    // Primary tabs accessible in mobile menu
    const mobileWeaknessLibrary = screen.getByRole('menuitem', { name: /Weakness Library/i });
    const mobileAnalysis = screen.getByRole('menuitem', { name: /Exact-Position Analysis/i });
    const mobilePuzzles = screen.getByRole('menuitem', { name: /Practice Puzzles/i });
    const mobileMyGames = screen.getByRole('menuitem', { name: /My Games/i });
    const mobileImport = screen.getByRole('menuitem', { name: /Import Games/i });

    expect(mobileWeaknessLibrary).toBeInTheDocument();
    expect(mobileWeaknessLibrary).toHaveTextContent('5'); // badge
    expect(mobileAnalysis).toBeInTheDocument();
    expect(mobilePuzzles).toBeInTheDocument();
    expect(mobileMyGames).toBeInTheDocument();
    expect(mobileImport).toBeInTheDocument();

    // Secondary tools accessible in mobile menu
    expect(screen.getByRole('menuitem', { name: /Explore a position/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Decision Timing/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Similar Positions/i })).toBeInTheDocument();

    // Prohibition: strictly avoid "Exact Weakness"
    expect(screen.queryByText(/Exact Weakness/i)).not.toBeInTheDocument();

    // Clicking a tab closes menu and activates view
    fireEvent.click(mobileAnalysis);
    expect(setActiveTab).toHaveBeenCalledWith('weakness-analysis');
    expect(screen.queryByRole('menuitem', { name: /Exact-Position Analysis/i })).not.toBeInTheDocument();
  });
});
