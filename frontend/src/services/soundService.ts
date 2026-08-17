/**
 * Sound service for ChessEcho puzzle interactions.
 * Provides preloaded audio playback, graceful failure handling,
 * and preference persistence in localStorage.
 */

export type SoundType = 'correct' | 'incorrect' | 'completion' | 'move';

const SOUND_STORAGE_KEY = 'chessecho_sound_enabled';

const SOUND_PATHS: Record<SoundType, string> = {
  correct: '/sounds/correct.wav',
  incorrect: '/sounds/incorrect.wav',
  completion: '/sounds/completion.wav',
  move: '/sounds/move.wav',
};

class SoundService {
  private audioCache: Partial<Record<SoundType, HTMLAudioElement>> = {};
  private soundEnabled: boolean = true;
  private isInitialized: boolean = false;
  private listeners: Set<(enabled: boolean) => void> = new Set();

  constructor() {
    this.init();
  }

  private init(): void {
    if (typeof window === 'undefined') return;

    try {
      const stored = window.localStorage.getItem(SOUND_STORAGE_KEY);
      if (stored !== null) {
        this.soundEnabled = stored === 'true';
      } else {
        this.soundEnabled = true;
      }
    } catch {
      this.soundEnabled = true;
    }

    this.isInitialized = true;
  }

  private getAudio(type: SoundType): HTMLAudioElement | null {
    if (typeof window === 'undefined' || typeof Audio === 'undefined') return null;

    if (!this.audioCache[type]) {
      try {
        const audio = new Audio(SOUND_PATHS[type]);
        audio.preload = 'auto';
        this.audioCache[type] = audio;
      } catch {
        return null;
      }
    }

    return this.audioCache[type] ?? null;
  }

  /**
   * Check if sound feedback is enabled.
   */
  public isSoundEnabled(): boolean {
    if (!this.isInitialized) {
      this.init();
    }
    return this.soundEnabled;
  }

  /**
   * Set sound feedback enabled state and persist to localStorage.
   */
  public setSoundEnabled(enabled: boolean): void {
    this.soundEnabled = enabled;
    if (typeof window !== 'undefined') {
      try {
        window.localStorage.setItem(SOUND_STORAGE_KEY, String(enabled));
      } catch {
        // Handle potential quota or security errors gracefully
      }
    }
    this.notifyListeners();
  }

  /**
   * Toggle sound feedback enabled state and return new state.
   */
  public toggleSound(): boolean {
    const next = !this.isSoundEnabled();
    this.setSoundEnabled(next);
    return next;
  }

  /**
   * Subscribe to sound enabled state changes.
   */
  public subscribe(callback: (enabled: boolean) => void): () => void {
    this.listeners.add(callback);
    return () => {
      this.listeners.delete(callback);
    };
  }

  private notifyListeners(): void {
    for (const listener of this.listeners) {
      try {
        listener(this.soundEnabled);
      } catch {
        // Ignore listener errors
      }
    }
  }

  /**
   * Play a sound effect if sound is enabled.
   */
  public playSound(type: SoundType): void {
    if (!this.isSoundEnabled()) return;

    try {
      const audio = this.getAudio(type);
      if (!audio) return;

      audio.currentTime = 0;
      const playPromise = audio.play();
      if (playPromise && typeof playPromise.catch === 'function') {
        playPromise.catch(() => {
          // Playback failed or was blocked by autoplay policy; fail gracefully
        });
      }
    } catch {
      // Audio play error handled gracefully
    }
  }
  /**
   * Reset internal cache and reinitialize state (useful for tests and cleanup).
   */
  public reset(): void {
    this.audioCache = {};
    this.soundEnabled = true;
    this.isInitialized = false;
    this.listeners.clear();
    this.init();
  }
}

export { SoundService };
export const soundService = new SoundService();

// Export standalone utility functions for direct import convenience
export const playSound = (type: SoundType): void => soundService.playSound(type);
export const isSoundEnabled = (): boolean => soundService.isSoundEnabled();
export const setSoundEnabled = (enabled: boolean): void => soundService.setSoundEnabled(enabled);
export const toggleSound = (): boolean => soundService.toggleSound();

