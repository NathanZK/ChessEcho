'use client';

import React from 'react';
import { Target } from 'lucide-react';

export interface AccountConnectionPromptModalProps {
  isOpen: boolean;
  onDismiss: () => void;
}

export const AccountConnectionPromptModal: React.FC<AccountConnectionPromptModalProps> = ({
  isOpen,
  onDismiss,
}) => {
  if (!isOpen) return null;
  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4 backdrop-blur-sm font-mono"
    >
      <div className="bg-surface-container-high border border-outline-variant rounded-2xl w-full max-w-sm p-6 space-y-4 text-center shadow-2xl">
        <div className="w-12 h-12 rounded-xl bg-primary/20 text-primary flex items-center justify-center mx-auto">
          <Target className="w-6 h-6" />
        </div>
        <div>
          <h3 className="text-base font-bold text-on-surface">Connect Account</h3>
          <p className="text-xs text-on-surface-variant mt-1.5 leading-relaxed">
            Connect your Chess.com account in the Import Games tab to analyze your games.
          </p>
        </div>
        <button
          type="button"
          onClick={onDismiss}
          className="w-full py-2 bg-primary text-on-primary font-mono font-bold text-xs rounded-xl hover:bg-primary/90 transition cursor-pointer"
        >
          Dismiss
        </button>
      </div>
    </div>
  );
};
