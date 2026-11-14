import type { SutAdapter } from './sutAdapter';
import {
  type PracticeState,
  type PracticeAction,
  type PracticeContext,
  createInitialPracticeContext,
  getPracticeFsmNextState,
} from './modelFsm';
import type { ObservedPracticeState } from './invariantEvaluator';

export interface SelectPuzzlePayload {
  puzzleId: string;
  fen?: string;
  source?: string;
}

export class InMemoryPracticeAdapter
  implements SutAdapter<PracticeState, PracticeAction, ObservedPracticeState> {
  private currentState: PracticeState = 'INITIAL';
  private currentContext: PracticeContext = createInitialPracticeContext();
  private lastStatus = 'IDLE';

  isDomLevel(): boolean {
    return false;
  }

  async executeAction(action: PracticeAction, payload?: unknown): Promise<void> {
    if (action === 'SELECT_PUZZLE') {
      const p = payload as SelectPuzzlePayload | undefined;
      const puzzleId = p?.puzzleId ?? this.currentContext.puzzleId ?? 'test-puzzle-1';
      const fen =
        p?.fen ??
        (this.currentContext.boardFen
          ? this.currentContext.boardFen
          : 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1');
      this.currentContext = {
        ...this.currentContext,
        puzzleId,
        boardFen: fen,
        entryFen: fen,
      };
      this.currentState = 'INITIAL';
      this.lastStatus = 'IDLE';
      return;
    }

    const next = getPracticeFsmNextState(this.currentState, action, this.currentContext);
    if (!next) {
      throw new Error(`Cannot execute ${action} from state ${this.currentState}`);
    }

    this.currentState = next.nextState;
    this.currentContext = next.nextContext;

    switch (this.currentState) {
      case 'ATTEMPTED_RIGHT':
        this.lastStatus = 'CORRECT';
        break;
      case 'ATTEMPTED_WRONG':
        this.lastStatus = 'INCORRECT';
        break;
      case 'LINE_EXPLORATION':
      case 'CHALLENGE_MODE':
      case 'BLINDFOLD':
        this.lastStatus = 'EXPLORING';
        break;
      case 'SOLVED':
        this.lastStatus = 'SOLVED';
        break;
      case 'EXIT':
      case 'INITIAL':
        this.lastStatus = 'IDLE';
        break;
      default:
        this.lastStatus = 'IDLE';
    }
  }

  async observeState(): Promise<ObservedPracticeState> {
    return {
      activePuzzle: this.currentContext.puzzleId
        ? { id: this.currentContext.puzzleId }
        : null,
      boardFen: this.currentContext.boardFen,
      currentView: this.currentContext.currentView,
      feedbackPanel: {
        status: this.lastStatus,
        isInteractive: this.currentState !== 'LINE_EXPLORATION',
      },
    };
  }

  async reset(initialContext?: PracticeContext): Promise<void> {
    this.currentState = 'INITIAL';
    this.currentContext = initialContext
      ? { ...initialContext }
      : createInitialPracticeContext();
    this.lastStatus = 'IDLE';
  }
}
