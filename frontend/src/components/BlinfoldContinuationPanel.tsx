'use client';

import React, { useState } from 'react';
import {
  applyChessEchoMove,
  applyPlayerMove,
  createBlinfoldGameState,
  resetBlinfoldGame,
  type BlindfoldGameState,
} from '@/utils/blindfoldGameState';
import { BlinfoldBoardWrapper } from './BlinfoldBoardWrapper';
import { NotationExchangeUI } from './NotationExchangeUI';

interface BlinfoldContinuationPanelProps {
  initialFen: string;
  onExit?: () => void;
  requestChessEchoMove?: (fen: string, history: string[]) => Promise<string | null>;
}

export const BlinfoldContinuationPanel: React.FC<BlinfoldContinuationPanelProps> = ({
  initialFen,
  onExit,
  requestChessEchoMove,
}) => {
  const [state, setState] = useState<BlindfoldGameState>(() => createBlinfoldGameState(initialFen));

  const handlePlayerMove = async (notation: string) => {
    const result = applyPlayerMove(state, notation, state.currentFen);
    if ('error' in result) {
      setState((current) => ({ ...current, notationError: result.error }));
      return;
    }
    setState({ ...result, isLoading: Boolean(requestChessEchoMove) });
    if (!requestChessEchoMove) return;
    try {
      const reply = await requestChessEchoMove(result.currentFen, result.moveHistory.map((move) => move.san));
      if (!reply) {
        setState((current) => ({ ...current, isLoading: false, isFailed: true, notationError: 'ChessEcho could not provide a move.' }));
        return;
      }
      const echoResult = applyChessEchoMove(result, reply, result.currentFen);
      setState('error' in echoResult
        ? { ...result, isLoading: false, isFailed: true, notationError: echoResult.error }
        : { ...echoResult, isLoading: false });
    } catch (error) {
      setState((current) => ({ ...current, isLoading: false, isFailed: true, notationError: error instanceof Error ? error.message : 'ChessEcho request failed.' }));
    }
  };

  const reset = () => setState(resetBlinfoldGame(state, initialFen));
  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-4 p-4 lg:flex-row">
      <BlinfoldBoardWrapper fen={state.currentFen} isVisible={state.isVisible} onReveal={() => setState((current) => ({ ...current, isVisible: true }))} />
      <div className="w-full max-w-xl">
        <NotationExchangeUI state={state} onPlayerMove={handlePlayerMove} onReveal={() => setState((current) => ({ ...current, isVisible: true }))} onReset={reset} onExit={onExit} />
      </div>
    </div>
  );
};
