import type {
  PracticeState,
  PracticeAction,
  PracticeContext,
} from './modelFsm';
import { getPracticeFsmNextState, createInitialPracticeContext } from './modelFsm';
import type { PlaywrightBrowserPracticeSutAdapter } from './browserSutAdapter';
import type { ObservedPracticeState } from './invariantEvaluator';

export interface LiveTraceMinimizationResult {
  originalLength: number;
  minimizedActions: PracticeAction[];
  replaySeed: number;
  replayCommand: string;
}

export interface LiveTraceMinimizerOptions {
  seed?: number;
  initialContext?: Partial<PracticeContext>;
  initialState?: PracticeState;
}

/**
 * 1-delta greedy reduction algorithm executed directly against a live browser SUT.
 * Prunes intermediate neutral/redundant actions while ensuring the failing invariant
 * or defect reproduces deterministically against the running application.
 */
export async function minimizeLiveBrowserTrace(
  sut: PlaywrightBrowserPracticeSutAdapter,
  failingActions: PracticeAction[],
  evaluator: (
    expectedState: PracticeState,
    observed: ObservedPracticeState,
    action: PracticeAction,
    context?: PracticeContext
  ) => void,
  options: LiveTraceMinimizerOptions = {}
): Promise<LiveTraceMinimizationResult> {
  const seed = options.seed ?? 42;
  const initialContext = createInitialPracticeContext(options.initialContext);
  const initialState = options.initialState ?? 'INITIAL';

  async function testSubtraceReproducesFailure(actions: PracticeAction[]): Promise<boolean> {
    await sut.reset(initialContext);
    let curState = initialState;
    let curContext = { ...initialContext };

    for (const action of actions) {
      const transition = getPracticeFsmNextState(curState, action, curContext);
      if (!transition) {
        return false;
      }
      curState = transition.nextState;
      curContext = transition.nextContext;

      try {
        await sut.executeAction(action, curContext);
      } catch {
        // If an action cannot execute against the live browser, this candidate subtrace is invalid
        return false;
      }

      const observed = await sut.observeState();
      try {
        evaluator(curState, observed, action, curContext);
      } catch {
        // Evaluator invariant or defect reproduced
        return true;
      }
    }
    return false;
  }

  // First verify that full trace reproduces the failure against live SUT
  const initialFailed = await testSubtraceReproducesFailure(failingActions);
  if (!initialFailed) {
    throw new Error('LiveTraceMinimizer: Initial action trace did not trigger an invariant violation against live browser.');
  }

  let currentActions = [...failingActions];
  let reduced = true;

  while (reduced) {
    reduced = false;
    for (let i = 0; i < currentActions.length; i++) {
      const candidate = currentActions.filter((_, idx) => idx !== i);
      if (candidate.length === 0) {
        continue;
      }
      const reproduces = await testSubtraceReproducesFailure(candidate);
      if (reproduces) {
        currentActions = candidate;
        reduced = true;
        break;
      }
    }
  }

  return {
    originalLength: failingActions.length,
    minimizedActions: currentActions,
    replaySeed: seed,
    replayCommand: `CI=true npx playwright test journeys/regression/live-minimizer.spec.ts --grep "@seed-${seed}"`,
  };
}
