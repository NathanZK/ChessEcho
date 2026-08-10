'use client';

import React, { useState } from 'react';
import { Header, TabType } from '@/components/Header';
import { EvalBar } from '@/components/EvalBar';
import { ChessBoardArea } from '@/components/ChessBoardArea';
import { PuzzleFeedbackPanel } from '@/components/PuzzleFeedbackPanel';
import { WeaknessesList } from '@/components/WeaknessesList';
import { ImportGamesView } from '@/components/ImportGamesView';
import { Puzzle } from '@/mock/mockData';
import { fetchPuzzles } from '@/services/api';

export default function Home() {
  const [activeTab, setActiveTab] = useState<TabType>('puzzles');
  const [activeUsername, setActiveUsername] = useState<string | undefined>(undefined);

  // Sync state from localStorage & window hash after client mount to prevent SSR hydration mismatch
  React.useEffect(() => {
    if (typeof window !== 'undefined') {
      const hash = window.location.hash.replace('#', '');
      if (hash === 'weaknesses' || hash === 'import' || hash === 'puzzles') {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setActiveTab(hash as TabType);
      } else {
        const savedTab = localStorage.getItem('chessecho_active_tab');
        if (savedTab === 'weaknesses' || savedTab === 'import' || savedTab === 'puzzles') {
          setActiveTab(savedTab as TabType);
        }
      }

      const savedUser = localStorage.getItem('chessecho_username');
      if (savedUser) {
        setActiveUsername(savedUser);
      }
    }
  }, []);

  // Listen to hashchange / popstate for browser back & forward navigation
  React.useEffect(() => {
    const syncHash = () => {
      if (typeof window !== 'undefined') {
        const hash = window.location.hash.replace('#', '');
        if (hash === 'weaknesses' || hash === 'import' || hash === 'puzzles') {
          setActiveTab(hash as TabType);
          localStorage.setItem('chessecho_active_tab', hash);
        }
      }
    };
    window.addEventListener('hashchange', syncHash);
    window.addEventListener('popstate', syncHash);
    return () => {
      window.removeEventListener('hashchange', syncHash);
      window.removeEventListener('popstate', syncHash);
    };
  }, []);

  const changeTab = (tab: TabType) => {
    setActiveTab(tab);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_active_tab', tab);
      window.history.replaceState(null, '', `#${tab}`);
    }
  };

  const handleSetUsername = (user: string | undefined) => {
    setActiveUsername(user);
    if (typeof window !== 'undefined') {
      if (user) {
        localStorage.setItem('chessecho_username', user);
      } else {
        localStorage.removeItem('chessecho_username');
      }
    }
  };

  const handleDisconnect = () => {
    handleSetUsername(undefined);
    setPuzzlesList([]);
    setActivePuzzle(null);
  };


  const [puzzlesList, setPuzzlesList] = useState<Puzzle[]>([]);
  const [currentPuzzleIndex, setCurrentPuzzleIndex] = useState<number>(0);
  const [activePuzzle, setActivePuzzle] = useState<Puzzle | null>(null);
  const [isLoadingPuzzles, setIsLoadingPuzzles] = useState<boolean>(true);

  // Puzzle filter settings
  const [minEvalLoss, setMinEvalLoss] = useState<number>(0.8);
  const [minMistakeCount, setMinMistakeCount] = useState<number>(3);
  const [puzzleColorFilter, setPuzzleColorFilter] = useState<'BOTH' | 'WHITE' | 'BLACK'>('BOTH');
  const [showPuzzleSettings, setShowPuzzleSettings] = useState<boolean>(false);

  // Restore puzzle color filter from localStorage after mount
  React.useEffect(() => {
    if (typeof window !== 'undefined') {
      const savedColor = localStorage.getItem('chessecho_puzzle_color_filter');
      if (savedColor === 'BOTH' || savedColor === 'WHITE' || savedColor === 'BLACK') {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setPuzzleColorFilter(savedColor as 'BOTH' | 'WHITE' | 'BLACK');
      }
    }
  }, []);

  const handleColorFilterChange = (color: 'BOTH' | 'WHITE' | 'BLACK') => {
    setPuzzleColorFilter(color);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_puzzle_color_filter', color);
    }
  };

  // History stacks for EvalBar and feedback state matching board undo/redo index
  const [evalHistory, setEvalHistory] = useState<number[]>([35]);
  const [feedbackHistory, setFeedbackHistory] = useState<unknown[]>([{ status: 'IDLE' }]);
  const [historyIndex, setHistoryIndex] = useState<number>(0);

  const [currentEvalCp, setCurrentEvalCp] = useState<number>(35);
  const [isEvalUnknown, setIsEvalUnknown] = useState<boolean>(false);
  const [moveHistory, setMoveHistory] = useState<string[]>([]);
  const [hintSquare, setHintSquare] = useState<string | undefined>(undefined);

  const [feedback, setFeedback] = useState<{
    status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
    lastMove?: string;
    historicalInfo?: { timesPlayed: number; averageLoss: number };
  }>({ status: 'IDLE' });

  const resetPuzzleInteractionState = (puzzle: Puzzle) => {
    const initialEval = puzzle.evalCp ?? 35;
    setCurrentEvalCp(initialEval);
    setEvalHistory([initialEval]);
    setFeedbackHistory([{ status: 'IDLE' }]);
    setFeedback({ status: 'IDLE' });
    setHistoryIndex(0);
    setMoveHistory([]);
    setHintSquare(undefined);
    setIsEvalUnknown(false);
  };

  // Fetch live puzzles whenever activeUsername or puzzleColorFilter changes
  React.useEffect(() => {
    if (!activeUsername) {
      setPuzzlesList([]);
      setActivePuzzle(null);
      setFeedback({ status: 'IDLE' });
      setMoveHistory([]);
      setHintSquare(undefined);
      setIsLoadingPuzzles(false);
      return;
    }
    async function loadData() {
      setIsLoadingPuzzles(true);
      try {
        let data: Puzzle[] = [];
        if (puzzleColorFilter === 'WHITE') {
          data = await fetchPuzzles(activeUsername!, 'CHESS_COM', 'WHITE', minEvalLoss, minMistakeCount, 10, 0);
        } else if (puzzleColorFilter === 'BLACK') {
          data = await fetchPuzzles(activeUsername!, 'CHESS_COM', 'BLACK', minEvalLoss, minMistakeCount, 10, 0);
        } else {
          const whiteData = await fetchPuzzles(activeUsername!, 'CHESS_COM', 'WHITE', minEvalLoss, minMistakeCount, 10, 0);
          const blackData = await fetchPuzzles(activeUsername!, 'CHESS_COM', 'BLACK', minEvalLoss, minMistakeCount, 10, 0);
          data = [...whiteData, ...blackData];
        }

        if (data && data.length > 0) {
          setPuzzlesList(data);
          const savedId = typeof window !== 'undefined' ? localStorage.getItem('chessecho_puzzle_id') : null;
          const foundIdx = savedId ? data.findIndex((p) => p.puzzleId === savedId) : -1;
          const targetIdx = foundIdx !== -1 ? foundIdx : 0;
          const targetPuzzle = data[targetIdx];

          setCurrentPuzzleIndex(targetIdx);
          setActivePuzzle(targetPuzzle);
          resetPuzzleInteractionState(targetPuzzle);
          if (typeof window !== 'undefined' && targetPuzzle) {
            localStorage.setItem('chessecho_puzzle_id', targetPuzzle.puzzleId);
          }
        } else {
          setPuzzlesList([]);
          setActivePuzzle(null);
          setFeedback({ status: 'IDLE' });
          setMoveHistory([]);
          setHintSquare(undefined);
        }
      } catch (err) {
        console.error('Failed to load puzzles from backend API:', err);
        setPuzzlesList([]);
        setActivePuzzle(null);
        setFeedback({ status: 'IDLE' });
        setMoveHistory([]);
        setHintSquare(undefined);
      } finally {
        setIsLoadingPuzzles(false);
      }
    }
    loadData();
  }, [activeUsername, puzzleColorFilter]);

  const handleApplyPuzzleSettings = async () => {
    if (!activeUsername) return;
    setIsLoadingPuzzles(true);
    try {
      let data: Puzzle[] = [];
      if (puzzleColorFilter === 'WHITE') {
        data = await fetchPuzzles(activeUsername, 'CHESS_COM', 'WHITE', minEvalLoss, minMistakeCount, 10, 0);
      } else if (puzzleColorFilter === 'BLACK') {
        data = await fetchPuzzles(activeUsername, 'CHESS_COM', 'BLACK', minEvalLoss, minMistakeCount, 10, 0);
      } else {
        const whiteData = await fetchPuzzles(activeUsername, 'CHESS_COM', 'WHITE', minEvalLoss, minMistakeCount, 10, 0);
        const blackData = await fetchPuzzles(activeUsername, 'CHESS_COM', 'BLACK', minEvalLoss, minMistakeCount, 10, 0);
        data = [...whiteData, ...blackData];
      }

      if (data && data.length > 0) {
        setPuzzlesList(data);
        const currentId = activePuzzle?.puzzleId || (typeof window !== 'undefined' ? localStorage.getItem('chessecho_puzzle_id') : null);
        const foundIdx = currentId ? data.findIndex((p) => p.puzzleId === currentId) : -1;
        const targetIdx = foundIdx !== -1 ? foundIdx : 0;
        const targetPuzzle = data[targetIdx];

        setCurrentPuzzleIndex(targetIdx);
        setActivePuzzle(targetPuzzle);
        resetPuzzleInteractionState(targetPuzzle);
        if (typeof window !== 'undefined' && targetPuzzle) {
          localStorage.setItem('chessecho_puzzle_id', targetPuzzle.puzzleId);
        }
      } else {
        setPuzzlesList([]);
        setActivePuzzle(null);
        setFeedback({ status: 'IDLE' });
        setMoveHistory([]);
        setHintSquare(undefined);
        if (typeof window !== 'undefined') {
          localStorage.removeItem('chessecho_puzzle_id');
        }
      }
    } catch (err) {
      console.error('Failed to apply puzzle settings:', err);
      setPuzzlesList([]);
      setActivePuzzle(null);
      setFeedback({ status: 'IDLE' });
      setMoveHistory([]);
      setHintSquare(undefined);
    } finally {
      setIsLoadingPuzzles(false);
    }
  };

  const handleNextPuzzle = () => {
    if (puzzlesList.length === 0) return;
    const nextIndex = (currentPuzzleIndex + 1) % puzzlesList.length;
    const nextPuzzle = puzzlesList[nextIndex];

    setCurrentPuzzleIndex(nextIndex);
    setActivePuzzle(nextPuzzle);
    resetPuzzleInteractionState(nextPuzzle);
    if (typeof window !== 'undefined' && nextPuzzle) {
      localStorage.setItem('chessecho_puzzle_id', nextPuzzle.puzzleId);
    }
  };

  const handleSelectPracticeFromLibrary = (puzzle: Puzzle) => {
    const foundIdx = puzzlesList.findIndex((p) => p.puzzleId === puzzle.puzzleId);
    const selectedPuzzle = foundIdx !== -1 ? puzzlesList[foundIdx] : puzzle;

    if (foundIdx !== -1) {
      setCurrentPuzzleIndex(foundIdx);
    }
    setActivePuzzle(selectedPuzzle);
    resetPuzzleInteractionState(selectedPuzzle);
    if (typeof window !== 'undefined' && selectedPuzzle) {
      localStorage.setItem('chessecho_puzzle_id', selectedPuzzle.puzzleId);
    }
    setActiveTab('puzzles');
  };

  const handleBoardUndo = () => {
    const prevIndex = Math.max(0, historyIndex - 1);
    setHistoryIndex(prevIndex);
    setCurrentEvalCp(evalHistory[prevIndex] ?? (activePuzzle?.evalCp ?? 35));
    const prevFeedback = feedbackHistory[prevIndex] as {
      status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
      lastMove?: string;
      historicalInfo?: { timesPlayed: number; averageLoss: number };
    };
    setFeedback(prevFeedback ?? { status: 'IDLE' });
    setIsEvalUnknown(false);
  };

  const handleBoardRedo = () => {
    if (historyIndex < evalHistory.length - 1) {
      const nextIndex = historyIndex + 1;
      setHistoryIndex(nextIndex);
      setCurrentEvalCp(evalHistory[nextIndex]);
      const nextFeedback = feedbackHistory[nextIndex] as {
        status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
        lastMove?: string;
        historicalInfo?: { timesPlayed: number; averageLoss: number };
      };
      setFeedback(nextFeedback ?? { status: 'IDLE' });
    }
  };

  const handleMoveAttempt = (
    moveSan: string,
    isCorrect: boolean,
    isHistoricalMistake: boolean,
    historicalInfo?: { timesPlayed: number; averageLoss: number },
    isInitialDecision: boolean = true
  ) => {
    if (!activePuzzle) return;
    setMoveHistory((prev) => [...prev, moveSan]);
    setHintSquare(undefined);

    const startCp = activePuzzle.evalCp ?? 35;
    const isAlreadySolved = feedback.status === 'CORRECT' || feedback.status === 'EXPLORING';

    if (isInitialDecision) {
      // Calculate evaluation update based on move played for initial decision
      let calculatedEval = startCp;
      let moveHasEngineData = true;
      const acceptableMatch = activePuzzle.acceptableMoves.find((m) => m.move === moveSan);

      if (moveSan === activePuzzle.targetMove) {
        // Best move: no loss — eval stays at starting position
        calculatedEval = startCp;
      } else if (acceptableMatch) {
        // Acceptable alternative: apply its specific eval loss from engine data
        const lossCp = Math.round(acceptableMatch.evalLoss * 100);
        calculatedEval = activePuzzle.playerColor === 'BLACK'
          ? startCp + lossCp
          : startCp - lossCp;
      } else if (isHistoricalMistake && historicalInfo) {
        // Historical mistake: use average loss from actual game data
        const lossCp = Math.round(historicalInfo.averageLoss * 100);
        calculatedEval = activePuzzle.playerColor === 'BLACK'
          ? startCp + lossCp
          : startCp - lossCp;
      } else {
        // No engine data for this move — freeze eval and show ? badge
        moveHasEngineData = false;
        calculatedEval = currentEvalCp; // keep current value unchanged
      }

      let newFeedbackState: {
        status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
        lastMove?: string;
        historicalInfo?: { timesPlayed: number; averageLoss: number };
      } = { status: 'IDLE' };

      if (isCorrect) {
        newFeedbackState = { status: 'CORRECT', lastMove: moveSan };
      } else if (isHistoricalMistake) {
        newFeedbackState = { status: 'HISTORICAL_MISTAKE', lastMove: moveSan, historicalInfo };
      } else {
        newFeedbackState = { status: 'INCORRECT', lastMove: moveSan };
      }

      // Truncate future stacks if making a move after undoing
      const trimmedEvalHist = evalHistory.slice(0, historyIndex + 1);
      const trimmedFeedHist = feedbackHistory.slice(0, historyIndex + 1);

      trimmedEvalHist.push(calculatedEval);
      trimmedFeedHist.push(newFeedbackState);

      setEvalHistory(trimmedEvalHist);
      setFeedbackHistory(trimmedFeedHist);
      setHistoryIndex(trimmedEvalHist.length - 1);

      setCurrentEvalCp(calculatedEval);
      setIsEvalUnknown(!moveHasEngineData);
      setFeedback(newFeedbackState);
    } else {
      // Continuation / Opponent move / Exploration
      // Do NOT re-evaluate opponent moves against initial targetMove or overwrite the initial mistake feedback!
      let newFeedbackState = feedback;

      if (isAlreadySolved) {
        newFeedbackState = { status: 'EXPLORING', lastMove: moveSan };
      }
      // If previous move was an INCORRECT or HISTORICAL_MISTAKE attempt, preserve that feedback!

      const trimmedEvalHist = evalHistory.slice(0, historyIndex + 1);
      const trimmedFeedHist = feedbackHistory.slice(0, historyIndex + 1);

      trimmedEvalHist.push(currentEvalCp);
      trimmedFeedHist.push(newFeedbackState);

      setEvalHistory(trimmedEvalHist);
      setFeedbackHistory(trimmedFeedHist);
      setHistoryIndex(trimmedEvalHist.length - 1);

      if (isAlreadySolved) {
        setFeedback(newFeedbackState);
      }
    }
  };

  return (
    <div className="h-screen bg-slate-950 text-slate-100 flex flex-col font-sans selection:bg-emerald-500 selection:text-white overflow-hidden">
      {/* Top Header */}
      <Header
        activeTab={activeTab}
        setActiveTab={changeTab}
        username={activeUsername}
        weaknessCount={puzzlesList.length}
        onDisconnect={handleDisconnect}
      />

      {/* Main Content Area */}
      <main className={`flex-1 flex flex-col ${activeTab === 'import' ? 'justify-center overflow-hidden py-2' : 'justify-start overflow-y-auto py-2'}`}>
        {/* TAB 1: PRACTICE PUZZLES */}
        {activeTab === 'puzzles' && (
          <div className="max-w-[1450px] w-full mx-auto px-3 lg:px-6 flex-1 flex flex-col min-h-0">
            {/* Compact Filter & Settings Toolbar */}
            <div className="mb-2 flex flex-wrap items-center justify-between gap-3 bg-slate-900/80 px-3.5 py-1.5 rounded-2xl border border-slate-800 shadow-md">
              {/* Player Color Selector */}
              <div className="flex items-center space-x-2">
                <span className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">
                  Color:
                </span>
                <div className="flex bg-slate-950 p-0.5 rounded-xl border border-slate-800">
                  {(['BOTH', 'WHITE', 'BLACK'] as const).map((color) => (
                    <button
                      key={color}
                      type="button"
                      onClick={() => handleColorFilterChange(color)}
                      className={`px-2.5 py-0.5 text-[11px] font-bold rounded-lg transition cursor-pointer ${
                        puzzleColorFilter === color
                          ? 'bg-emerald-600 text-white shadow-sm'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      {color === 'BOTH' ? 'Both' : color === 'WHITE' ? 'White' : 'Black'}
                    </button>
                  ))}
                </div>
              </div>

              {/* Collapsible Settings */}
              <div className="flex items-center space-x-3">
                <button
                  type="button"
                  onClick={() => setShowPuzzleSettings((v) => !v)}
                  className="text-xs font-bold text-slate-400 hover:text-emerald-400 transition cursor-pointer"
                >
                  {showPuzzleSettings ? 'Hide Settings' : 'Puzzle Settings ⚙️'}
                </button>

                {showPuzzleSettings && (
                  <div className="flex items-center gap-2 bg-slate-950 px-3 py-1 rounded-xl border border-slate-800">
                    <label className="flex items-center gap-1.5 text-[11px] font-semibold text-slate-300">
                      <span>Min Eval Loss</span>
                      <input
                        type="number"
                        step="0.1"
                        min="0"
                        value={minEvalLoss}
                        onChange={(e) => setMinEvalLoss(Number(e.target.value))}
                        className="w-14 bg-slate-900 border border-slate-800 rounded px-1.5 py-0.5 text-emerald-400 font-mono text-[11px] outline-none focus:border-emerald-500"
                      />
                    </label>
                    <label className="flex items-center gap-1.5 text-[11px] font-semibold text-slate-300">
                      <span>Min Mistakes</span>
                      <input
                        type="number"
                        step="1"
                        min="1"
                        value={minMistakeCount}
                        onChange={(e) => setMinMistakeCount(Number(e.target.value))}
                        className="w-14 bg-slate-900 border border-slate-800 rounded px-1.5 py-0.5 text-emerald-400 font-mono text-[11px] outline-none focus:border-emerald-500"
                      />
                    </label>
                    <button
                      type="button"
                      onClick={handleApplyPuzzleSettings}
                      className="px-2.5 py-0.5 bg-emerald-600 hover:bg-emerald-500 text-white text-[11px] font-bold rounded-md transition cursor-pointer"
                    >
                      Apply
                    </button>
                  </div>
                )}
              </div>
            </div>

            <div className="flex-1 flex flex-col justify-center min-h-0">
              {isLoadingPuzzles ? (
                <div className="flex-1 flex items-center justify-center py-16">
                  <div className="flex items-center space-x-3 text-emerald-400 bg-slate-900/80 px-5 py-3 rounded-2xl border border-slate-800 shadow-lg">
                    <div className="w-5 h-5 border-2 border-emerald-400 border-t-transparent rounded-full animate-spin" />
                    <span className="text-xs font-bold text-slate-300">Loading Practice Puzzles...</span>
                  </div>
                </div>
              ) : !activePuzzle ? (
                <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-slate-900/60 p-8 rounded-2xl border border-slate-800 shadow-xl">
                  <div className="w-14 h-14 rounded-2xl bg-slate-950 border border-slate-800 flex items-center justify-center mx-auto text-emerald-400">
                    <span className="text-3xl font-bold">♟</span>
                  </div>
                  <div>
                    <h3 className="text-lg font-bold text-white">No Practice Puzzles Available</h3>
                    <p className="text-xs text-slate-400 mt-1">
                      {!activeUsername
                        ? 'Import your games using the Import Games tab to detect your opening habits and build your custom puzzle set.'
                        : `No weakness positions detected yet for ${activeUsername}. Import more games or adjust search criteria.`}
                    </p>
                  </div>
                  <button
                    onClick={() => changeTab('import')}
                    className="px-5 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs rounded-xl transition shadow-lg shadow-emerald-900/30 cursor-pointer"
                  >
                    Import Your Games Now
                  </button>
                </div>
              ) : (
              <div className="flex flex-col lg:flex-row items-center lg:items-start justify-center gap-5 xl:gap-8">
                {/* Left Stockfish Eval Bar */}
                <div className="hidden sm:flex flex-col items-center pt-1">
                  <span className="text-[10px] font-bold text-slate-400 mb-1.5 uppercase tracking-wider">
                    Eval
                  </span>
                  <EvalBar evalCp={currentEvalCp} isExploring={feedback.status === 'EXPLORING'} isUnknown={isEvalUnknown} />
                </div>

                {/* Center Interactive Chessboard & Controls */}
                <div className="w-full max-w-[560px] xl:max-w-[600px] shrink-0">
                  <ChessBoardArea
                    initialFen={activePuzzle.fen}
                    playerColor={activePuzzle.playerColor}
                    targetMove={activePuzzle.targetMove}
                    acceptableMoves={activePuzzle.acceptableMoves}
                    movesPlayed={activePuzzle.movesPlayed}
                    onMoveAttempt={handleMoveAttempt}
                    onNextPuzzle={handleNextPuzzle}
                    onUndo={handleBoardUndo}
                    onRedo={handleBoardRedo}
                    hintSquare={hintSquare}
                  />
                </div>

                {/* Right Feedback & Stats Panel */}
                <div className="w-full max-w-[440px] shrink-0">
                  <PuzzleFeedbackPanel
                    puzzle={activePuzzle}
                    feedback={feedback}
                    moveHistory={moveHistory}
                    onNextPuzzle={handleNextPuzzle}
                  />
                </div>
              </div>
            )}
          </div>
          </div>
        )}

        {/* TAB 2: WEAKNESSES LIBRARY */}
        {activeTab === 'weaknesses' && (
          <WeaknessesList
            username={activeUsername}
            onSelectPractice={handleSelectPracticeFromLibrary}
          />
        )}

        {/* TAB 3: IMPORT GAMES */}
        {activeTab === 'import' && (
          <ImportGamesView
            connectedUsername={activeUsername}
            onDisconnect={handleDisconnect}
            onImportStarted={(user) => handleSetUsername(user)}
            onNavigateTab={(tab) => changeTab(tab)}
          />
        )}




      </main>
    </div>
  );
}
