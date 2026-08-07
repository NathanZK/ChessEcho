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

  // Puzzle filter settings
  const [minEvalLoss, setMinEvalLoss] = useState<number>(0.8);
  const [acceptableThreshold, setAcceptableThreshold] = useState<number>(0.3);
  const [minMistakeCount, setMinMistakeCount] = useState<number>(3);
  const [showPuzzleSettings, setShowPuzzleSettings] = useState<boolean>(false);

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

  // Fetch live puzzles whenever activeUsername is set
  React.useEffect(() => {
    if (!activeUsername) {
      setPuzzlesList([]);
      setActivePuzzle(null);
      return;
    }
    async function loadData() {
      try {
        const whiteData = await fetchPuzzles(
          activeUsername!,
          'chessdotcom',
          'white',
          minEvalLoss,
          acceptableThreshold,
          minMistakeCount,
          10,
          0,
        );
        const blackData = await fetchPuzzles(
          activeUsername!,
          'chessdotcom',
          'black',
          minEvalLoss,
          acceptableThreshold,
          minMistakeCount,
          10,
          0,
        );
        const data = [...whiteData, ...blackData];

        if (data && data.length > 0) {
          setPuzzlesList(data);
          setActivePuzzle(data[0]);
          setCurrentEvalCp(data[0].evalCp ?? 35);
          setEvalHistory([data[0].evalCp ?? 35]);
          setFeedbackHistory([{ status: 'IDLE' }]);
          setHistoryIndex(0);
        } else {
          setPuzzlesList([]);
          setActivePuzzle(null);
        }
      } catch (err) {
        console.error('Failed to load puzzles from backend API:', err);
        setPuzzlesList([]);
        setActivePuzzle(null);
      }
    }
    loadData();
  }, [activeUsername]);



  const handleNextPuzzle = () => {
    if (puzzlesList.length === 0) return;
    const nextIndex = (currentPuzzleIndex + 1) % puzzlesList.length;
    const nextPuzzle = puzzlesList[nextIndex];
    const initialEval = nextPuzzle.evalCp ?? 35;

    setCurrentPuzzleIndex(nextIndex);
    setActivePuzzle(nextPuzzle);

    setCurrentEvalCp(initialEval);
    setEvalHistory([initialEval]);
    setFeedbackHistory([{ status: 'IDLE' }]);
    setHistoryIndex(0);
    setMoveHistory([]);
    setHintSquare(undefined);
    setFeedback({ status: 'IDLE' });
  };

  const handleSelectPracticeFromLibrary = (puzzle: Puzzle) => {
    const foundIdx = puzzlesList.findIndex((p) => p.puzzleId === puzzle.puzzleId);
    const selectedPuzzle = foundIdx !== -1 ? puzzlesList[foundIdx] : puzzle;
    const initialEval = selectedPuzzle.evalCp ?? 35;

    if (foundIdx !== -1) {
      setCurrentPuzzleIndex(foundIdx);
    }
    setActivePuzzle(selectedPuzzle);
    setCurrentEvalCp(initialEval);
    setEvalHistory([initialEval]);
    setFeedbackHistory([{ status: 'IDLE' }]);
    setHistoryIndex(0);
    setIsEvalUnknown(false);
    setMoveHistory([]);
    setHintSquare(undefined);
    setFeedback({ status: 'IDLE' });
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
    historicalInfo?: { timesPlayed: number; averageLoss: number }
  ) => {
    if (!activePuzzle) return;
    setMoveHistory((prev) => [...prev, moveSan]);
    setHintSquare(undefined);

    // Calculate evaluation update based on move played
    const startCp = activePuzzle.evalCp ?? 35;
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
    const isAlreadySolved = feedback.status === 'CORRECT' || feedback.status === 'EXPLORING';


    if (isAlreadySolved) {
      // Puzzle solved: allow free exploration of follow-up lines on board while holding optimal eval
      newFeedbackState = { status: 'EXPLORING', lastMove: moveSan };
      calculatedEval = startCp;
    } else if (isCorrect) {
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
      <main className={`flex-1 flex flex-col ${activeTab === 'import' ? 'justify-center overflow-hidden py-2' : 'justify-start overflow-y-auto py-4'}`}>
        {/* TAB 1: PRACTICE PUZZLES */}

        {activeTab === 'puzzles' && (
          <div className="max-w-[1500px] w-full mx-auto px-4 lg:px-8 flex-1 flex flex-col">
            {/* Puzzle Settings Toggle */}
            <div className="flex justify-end mb-2">
              <button
                onClick={() => setShowPuzzleSettings((v) => !v)}
                className="text-[11px] font-bold text-slate-400 hover:text-emerald-400 transition"
              >
                {showPuzzleSettings ? 'Hide Puzzle Settings' : 'Puzzle Settings'}
              </button>
            </div>

            {/* Puzzle Settings */}
            {showPuzzleSettings && (
              <div className="mb-2 flex flex-wrap items-center gap-2 bg-slate-900/60 p-2 rounded-xl border border-slate-800">
                <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Puzzle Settings</span>
                <label className="flex items-center gap-1 text-[11px] text-slate-300">
                  <span>Min Eval Loss</span>
                  <input
                    type="number"
                    step="0.1"
                    value={minEvalLoss}
                    onChange={(e) => setMinEvalLoss(Number(e.target.value))}
                    className="w-16 bg-slate-950 border border-slate-800 rounded px-1.5 py-0.5 text-emerald-400 font-mono text-[11px]"
                  />
                </label>
                <label className="flex items-center gap-1 text-[11px] text-slate-300">
                  <span>Acceptable Threshold</span>
                  <input
                    type="number"
                    step="0.1"
                    value={acceptableThreshold}
                    onChange={(e) => setAcceptableThreshold(Number(e.target.value))}
                    className="w-16 bg-slate-950 border border-slate-800 rounded px-1.5 py-0.5 text-emerald-400 font-mono text-[11px]"
                  />
                </label>
                <label className="flex items-center gap-1 text-[11px] text-slate-300">
                  <span>Min Mistakes</span>
                  <input
                    type="number"
                    step="1"
                    value={minMistakeCount}
                    onChange={(e) => setMinMistakeCount(Number(e.target.value))}
                    className="w-16 bg-slate-950 border border-slate-800 rounded px-1.5 py-0.5 text-emerald-400 font-mono text-[11px]"
                  />
                </label>
                <button
                  onClick={() => {
                    if (!activeUsername) return;
                    setPuzzlesList([]);
                    setActivePuzzle(null);
                    setFeedback({ status: 'IDLE' });
                    setMoveHistory([]);
                    setEvalHistory([35]);
                    setFeedbackHistory([{ status: 'IDLE' }]);
                    setHistoryIndex(0);
                    setCurrentEvalCp(35);
                    setIsEvalUnknown(false);
                    setHintSquare(undefined);
                    async function reload() {
                      try {
                        const whiteData = await fetchPuzzles(activeUsername!, 'chessdotcom', 'white', minEvalLoss, acceptableThreshold, minMistakeCount, 10, 0);
                        const blackData = await fetchPuzzles(activeUsername!, 'chessdotcom', 'black', minEvalLoss, acceptableThreshold, minMistakeCount, 10, 0);
                        const data = [...whiteData, ...blackData];
                        if (data && data.length > 0) {
                          setPuzzlesList(data);
                          setActivePuzzle(data[0]);
                          setCurrentEvalCp(data[0].evalCp ?? 35);
                          setEvalHistory([data[0].evalCp ?? 35]);
                          setFeedbackHistory([{ status: 'IDLE' }]);
                          setHistoryIndex(0);
                        } else {
                          setPuzzlesList([]);
                          setActivePuzzle(null);
                        }
                      } catch (err) {
                        console.error('Failed to reload puzzles:', err);
                        setPuzzlesList([]);
                        setActivePuzzle(null);
                      }
                    }
                    reload();
                  }}
                  className="px-2 py-0.5 bg-emerald-600 hover:bg-emerald-500 text-white text-[11px] font-bold rounded-md transition"
                >
                  Apply
                </button>
              </div>
            )}

            <div className="flex-1 flex flex-col justify-center">
              {!activePuzzle ? (

              <div className="py-20 text-center space-y-4 max-w-lg mx-auto bg-slate-900/60 p-8 rounded-2xl border border-slate-800 shadow-xl">
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
