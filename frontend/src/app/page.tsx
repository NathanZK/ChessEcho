'use client';

import React, { useState } from 'react';
import { Header, TabType } from '@/components/Header';
import { EvalBar } from '@/components/EvalBar';
import { ChessBoardArea } from '@/components/ChessBoardArea';
import { PuzzleFeedbackPanel } from '@/components/PuzzleFeedbackPanel';
import { WeaknessesList } from '@/components/WeaknessesList';
import { ImportGamesView } from '@/components/ImportGamesView';
import { PuzzleCompletionModal } from '@/components/PuzzleCompletionModal';
import { MOCK_PUZZLES, Puzzle } from '@/mock/mockData';
import { fetchPuzzles } from '@/services/api';

export default function Home() {
  const [activeTab, setActiveTab] = useState<TabType>('puzzles');
  const [puzzlesList, setPuzzlesList] = useState<Puzzle[]>(MOCK_PUZZLES);
  const [currentPuzzleIndex, setCurrentPuzzleIndex] = useState<number>(0);
  const [activePuzzle, setActivePuzzle] = useState<Puzzle>(MOCK_PUZZLES[0]);
  
  // History stacks for EvalBar and feedback state matching board undo/redo index
  const [evalHistory, setEvalHistory] = useState<number[]>([MOCK_PUZZLES[0].evalCp ?? 35]);
  const [feedbackHistory, setFeedbackHistory] = useState<any[]>([{ status: 'IDLE' }]);
  const [historyIndex, setHistoryIndex] = useState<number>(0);

  const [currentEvalCp, setCurrentEvalCp] = useState<number>(MOCK_PUZZLES[0].evalCp ?? 35);
  const [isEvalUnknown, setIsEvalUnknown] = useState<boolean>(false);
  const [moveHistory, setMoveHistory] = useState<string[]>([]);
  const [hintSquare, setHintSquare] = useState<string | undefined>(undefined);
  const [isCompletionModalOpen, setIsCompletionModalOpen] = useState<boolean>(false);

  const [feedback, setFeedback] = useState<{
    status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
    lastMove?: string;
    historicalInfo?: { timesPlayed: number; averageLoss: number };
  }>({ status: 'IDLE' });

  // Fetch live puzzles from API on mount, fallback to mock data if offline
  React.useEffect(() => {
    async function loadData() {
      const data = await fetchPuzzles('NathanZele', 'CHESS_COM', 'black', 10, 0);
      if (data && data.length > 0) {
        setPuzzlesList(data);
        setActivePuzzle(data[0]);
        setCurrentEvalCp(data[0].evalCp ?? 35);
        setEvalHistory([data[0].evalCp ?? 35]);
        setFeedbackHistory([{ status: 'IDLE' }]);
        setHistoryIndex(0);
      }
    }
    loadData();
  }, []);

  const handleNextPuzzle = () => {
    const list = puzzlesList.length > 0 ? puzzlesList : MOCK_PUZZLES;
    const nextIndex = (currentPuzzleIndex + 1) % list.length;
    const nextPuzzle = list[nextIndex];
    const initialEval = nextPuzzle.evalCp ?? 35;

    setCurrentPuzzleIndex(nextIndex);
    setActivePuzzle(nextPuzzle);
    setCurrentEvalCp(initialEval);
    setEvalHistory([initialEval]);
    setFeedbackHistory([{ status: 'IDLE' }]);
    setHistoryIndex(0);
    setMoveHistory([]);
    setHintSquare(undefined);
    setIsCompletionModalOpen(false);
    setFeedback({ status: 'IDLE' });
  };

  const handleSelectPracticeFromLibrary = (puzzle: Puzzle) => {
    const foundIdx = MOCK_PUZZLES.findIndex((p) => p.puzzleId === puzzle.puzzleId);
    const selectedPuzzle = foundIdx !== -1 ? MOCK_PUZZLES[foundIdx] : puzzle;
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
    setIsCompletionModalOpen(false);
    setFeedback({ status: 'IDLE' });
    setActiveTab('puzzles');
  };

  const handleBoardUndo = () => {
    const prevIndex = Math.max(0, historyIndex - 1);
    setHistoryIndex(prevIndex);
    setCurrentEvalCp(evalHistory[prevIndex] ?? (activePuzzle.evalCp ?? 35));
    setFeedback(feedbackHistory[prevIndex] ?? { status: 'IDLE' });
    setIsEvalUnknown(false);
  };

  const handleBoardRedo = () => {
    if (historyIndex < evalHistory.length - 1) {
      const nextIndex = historyIndex + 1;
      setHistoryIndex(nextIndex);
      setCurrentEvalCp(evalHistory[nextIndex]);
      setFeedback(feedbackHistory[nextIndex]);
    }
  };

  const handleMoveAttempt = (
    moveSan: string,
    isCorrect: boolean,
    isHistoricalMistake: boolean,
    historicalInfo?: { timesPlayed: number; averageLoss: number }
  ) => {
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

    let newFeedbackState: any = { status: 'IDLE' };
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
        setActiveTab={setActiveTab}
        username="NathanZele"
        weaknessCount={MOCK_PUZZLES.length}
      />

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col justify-center overflow-y-auto lg:overflow-hidden py-3">
        {/* TAB 1: PRACTICE PUZZLES */}
        {activeTab === 'puzzles' && (
          <div className="max-w-[1500px] w-full mx-auto px-4 lg:px-8">
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
          </div>
        )}

        {/* TAB 2: WEAKNESSES LIBRARY */}
        {activeTab === 'weaknesses' && (
          <WeaknessesList onSelectPractice={handleSelectPracticeFromLibrary} />
        )}

        {/* TAB 3: IMPORT GAMES */}
        {activeTab === 'import' && <ImportGamesView />}
      </main>
    </div>
  );
}
