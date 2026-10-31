import { describe, it, expect } from 'vitest';
import { STRICT_OPPONENT_EVAL_LOSS_THRESHOLD } from '../utils/practiceConstants';

describe('practiceConstants', () => {
  it('defines STRICT_OPPONENT_EVAL_LOSS_THRESHOLD as exactly 0.20', () => {
    expect(STRICT_OPPONENT_EVAL_LOSS_THRESHOLD).toBe(0.20);
  });
});
