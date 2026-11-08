export interface PRNG {
  readonly seed: number;
  next(): number;
  nextInt(min: number, max: number): number;
}

/**
 * Creates a deterministic 32-bit Mulberry32 pseudo-random number generator.
 * Produces uniform pseudo-random values in [0, 1) and bounded integers [min, max].
 */
export function createPrng(seed: number): PRNG {
  let state = Math.floor(seed) >>> 0;

  const next = (): number => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };

  const nextInt = (min: number, max: number): number => {
    const minCeil = Math.ceil(min);
    const maxFloor = Math.floor(max);
    return Math.floor(next() * (maxFloor - minCeil + 1)) + minCeil;
  };

  return {
    seed,
    next,
    nextInt,
  };
}
