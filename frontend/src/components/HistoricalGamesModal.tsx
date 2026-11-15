'use client';

import React, { useEffect, useRef } from 'react';
import { ExternalLink, X } from 'lucide-react';
import { convertTo6chessUrl } from '../utils/urlUtils';

interface HistoricalGamesModalProps {
  urls: string[] | null;
  onClose: () => void;
  username?: string;
}

const GameUrlItem: React.FC<{ url: string; idx: number; username?: string }> = ({
  url,
  idx,
  username,
}) => {
  const sixChessUrl = username ? convertTo6chessUrl(url, username) : null;
  return (
    <div className="p-3 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
      <a
        href={url}
        target="_blank"
        rel="noreferrer"
        className="flex items-center justify-between text-xs font-semibold text-slate-300 hover:text-emerald-400 transition"
      >
        <span className="truncate max-w-[280px]">
          Game #{idx + 1}: {url}
        </span>
        <ExternalLink className="w-3.5 h-3.5 shrink-0 ml-2 text-slate-400" />
      </a>
      {sixChessUrl && (
        <a
          href={sixChessUrl}
          target="_blank"
          rel="noreferrer"
          className="flex items-center gap-1.5 text-[11px] font-medium text-slate-400 hover:text-emerald-400 transition"
        >
          <ExternalLink className="w-3 h-3" />
          <span>Analyze on 6chess</span>
        </a>
      )}
    </div>
  );
};

export const HistoricalGamesModal: React.FC<HistoricalGamesModalProps> = ({
  urls,
  onClose,
  username,
}) => {
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const previousActiveElementRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!urls || urls.length === 0) return;

    previousActiveElementRef.current = document.activeElement as HTMLElement | null;

    const focusTimer = setTimeout(() => {
      const focusable = dialogRef.current?.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
      );
      if (focusable && focusable.length > 0) {
        focusable[0].focus();
      } else {
        dialogRef.current?.focus();
      }
    }, 0);

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
        return;
      }

      if (event.key === 'Tab' && dialogRef.current) {
        const focusableElements = dialogRef.current.querySelectorAll<HTMLElement>(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
        );
        if (focusableElements.length === 0) return;

        const firstElement = focusableElements[0];
        const lastElement = focusableElements[focusableElements.length - 1];

        if (event.shiftKey) {
          if (document.activeElement === firstElement) {
            lastElement.focus();
            event.preventDefault();
          }
        } else {
          if (document.activeElement === lastElement) {
            firstElement.focus();
            event.preventDefault();
          }
        }
      }
    };

    window.addEventListener('keydown', onKeyDown);

    return () => {
      clearTimeout(focusTimer);
      window.removeEventListener('keydown', onKeyDown);
      if (previousActiveElementRef.current && typeof previousActiveElementRef.current.focus === 'function') {
        previousActiveElementRef.current.focus();
      }
    };
  }, [urls, onClose]);

  if (!urls || urls.length === 0) return null;

  return (
    <div
      className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm z-50 flex items-center justify-center p-4"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        ref={dialogRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby="historical-games-modal-title"
        className="bg-slate-900 border border-slate-800 rounded-2xl p-6 max-w-md w-full shadow-2xl space-y-4 focus:outline-none"
      >
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <h3 id="historical-games-modal-title" className="text-base font-bold text-white flex items-center gap-2">
            <ExternalLink className="w-4 h-4 text-emerald-400" />
            Source Games
          </h3>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close modal"
            className="text-slate-400 hover:text-white p-1 rounded-lg hover:bg-slate-800 transition cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <p className="text-xs text-slate-400">
          The following source games contain this recurring position:
        </p>

        <div className="max-h-60 overflow-y-auto space-y-2 pr-1">
          {urls.map((url, idx) => (
            <GameUrlItem key={idx} url={url} idx={idx} username={username} />
          ))}
        </div>

        <div className="pt-2 flex justify-end">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white text-xs font-bold rounded-xl transition cursor-pointer"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
