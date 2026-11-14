/**
 * Generic SUT (System Under Test) adapter contract.
 * Strictly separates test action execution and state observation from abstract models.
 */

export interface SutAdapter<TState, TAction, TObservedState> {
  /**
   * Optionally returns current abstract state if tracked by adapter.
   */
  getCurrentState?(): TState;

  /**
   * Executes an action against the system under test.
   * Throws an explicit Error if the action is invalid or rejected.
   */
  executeAction(action: TAction, context?: unknown): Promise<void> | void;

  /**
   * Captures the observed state from the system under test.
   */
  observeState(): Promise<TObservedState> | TObservedState;

  /**
   * Resets the system under test back to its initial baseline state.
   */
  reset(initialContext?: unknown): Promise<void> | void;

  /**
   * Indicates whether this adapter interacts directly with the live DOM.
   * In-process Vitest adapters return false; Playwright browser adapters return true.
   */
  isDomLevel(): boolean;
}
