import type { PracticeState, PracticeAction, PracticeContext } from './modelFsm';
import {
  createInitialPracticeContext,
  getPracticeFsmNextState,
} from './modelFsm';
import { PlaywrightBrowserPracticeSutAdapter } from './browserSutAdapter';
import type { ObservedPracticeState } from './invariantEvaluator';
import { GeneralPracticeInvariants } from './generalInvariants';
import { getPracticeFsmTransitionRules } from './liveBfsRunner';
import { exploreAbstractStates } from './bfsExplorer';
import { minimizeLiveBrowserTrace, type LiveTraceMinimizationResult } from './liveTraceMinimizer';

export interface ExplorationPathReport {
  path: PracticeAction[];
  terminalState: PracticeState;
  stepsExecuted: number;
  finalObservedState: ObservedPracticeState;
}

export interface DiscoveredDefectReport {
  invariantName: string;
  errorMessage: string;
  failingAction: PracticeAction;
  fullFailingTrace: PracticeAction[];
  minimizedResult?: LiveTraceMinimizationResult;
  observedStateAtFailure: ObservedPracticeState;
}

export interface GeneralExplorationSummary {
  totalPathsDiscovered: number;
  totalPathsExecuted: number;
  totalActionsExecuted: number;
  invariantsAsserted: number;
  paths: ExplorationPathReport[];
  discoveredDefects: DiscoveredDefectReport[];
}

/**
 * General Model-Based BFS Explorer.
 * Generates and executes action sequences dynamically against the live application
 * based strictly on the abstract FSM graph, evaluating general behavioral invariants
 * without any knowledge of specific bug identifiers or historical defects.
 */
export class GeneralBfsExplorer {
  private invariants = new GeneralPracticeInvariants();

  /**
   * Generates reachable action sequences from initialState up to depthBound.
   */
  generateBfsActionSequences(
    initialState: PracticeState = 'INITIAL',
    initialContext: PracticeContext = createInitialPracticeContext(),
    depthBound = 3
  ): PracticeAction[][] {
    const transitions = getPracticeFsmTransitionRules();
    const exploration = exploreAbstractStates(
      initialState,
      transitions,
      depthBound,
      initialContext
    );

    // Extract all unique action sequences from discovered paths
    const sequences: PracticeAction[][] = [];
    const seen = new Set<string>();

    for (const p of exploration.paths) {
      if (p.actions.length > 0) {
        const key = p.actions.join(' -> ');
        if (!seen.has(key)) {
          seen.add(key);
          sequences.push(p.actions);
        }
      }
    }

    return sequences;
  }

  /**
   * Executes generated sequences against the live browser application,
   * evaluating general invariants after every action.
   */
  async exploreLiveApplication(
    sut: PlaywrightBrowserPracticeSutAdapter,
    sequences: PracticeAction[][],
    initialContextOverrides?: Partial<PracticeContext>
  ): Promise<GeneralExplorationSummary> {
    const baseContext = createInitialPracticeContext(initialContextOverrides);
    const summary: GeneralExplorationSummary = {
      totalPathsDiscovered: sequences.length,
      totalPathsExecuted: 0,
      totalActionsExecuted: 0,
      invariantsAsserted: 0,
      paths: [],
      discoveredDefects: [],
    };

    for (let seqIdx = 0; seqIdx < sequences.length; seqIdx++) {
      const sequence = sequences[seqIdx];
      // Log progress periodically or on every sequence
      console.log(`[BFS ${seqIdx + 1}/${sequences.length}] ${sequence.join(' -> ')}`);
      await sut.reset(baseContext);
      let curState: PracticeState = 'INITIAL';
      let curContext = { ...baseContext };
      let finalObserved: ObservedPracticeState = await sut.observeState();
      let stepCount = 0;
      let pathFailed = false;

      for (const action of sequence) {
        const transition = getPracticeFsmNextState(curState, action, curContext);
        if (!transition) {
          break;
        }

        try {
          await sut.executeAction(action, curContext);
          summary.totalActionsExecuted++;
          stepCount++;

          curState = transition.nextState;
          curContext = transition.nextContext;
          finalObserved = await sut.observeState();

          console.log(`[ACTION SUCCESS] ${action}, observed boardFen: ${finalObserved.boardFen}`);
          // Evaluate general invariants after action
          this.invariants.assertAll(curState, curContext, finalObserved, action);
          summary.invariantsAsserted++;
        } catch (err: unknown) {
          console.log(`[ACTION FAILED] ${action}, error: ${err}`);
          pathFailed = true;
          const errorMessage = err instanceof Error ? err.message : String(err);
          const invariantMatch = errorMessage.match(/\[(G-INV-[^\]]+)\]/);
          const invariantName = invariantMatch ? invariantMatch[1] : 'Unknown Invariant';

          // Attempt trace minimization for discovered defect
          let minimizedResult: LiveTraceMinimizationResult | undefined;
          try {
            minimizedResult = await minimizeLiveBrowserTrace(
              sut,
              sequence.slice(0, stepCount),
              (exp, obs, act, ctx) => {
                this.invariants.assertAll(exp, ctx || baseContext, obs, act);
              },
              { initialContext: baseContext }
            );
          } catch {
            // Minimization could not reduce further
          }

          summary.discoveredDefects.push({
            invariantName,
            errorMessage,
            failingAction: action,
            fullFailingTrace: sequence.slice(0, stepCount),
            minimizedResult,
            observedStateAtFailure: finalObserved,
          });

          break; // Stop executing this failed sequence
        }
      }

      summary.totalPathsExecuted++;
      if (!pathFailed) {
        summary.paths.push({
          path: sequence,
          terminalState: curState,
          stepsExecuted: stepCount,
          finalObservedState: finalObserved,
        });
      }
    }

    return summary;
  }
}
