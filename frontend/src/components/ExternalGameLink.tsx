import React from 'react';
import { ExternalLink } from 'lucide-react';

export interface ExternalGameLinkProps {
  url: string;
  label?: string;
  platform?: 'chesscom' | 'lichess' | 'generic';
  className?: string;
  rel?: string;
}

export const ExternalGameLink: React.FC<ExternalGameLinkProps> = ({
  url,
  label,
  platform = 'generic',
  className = '',
  rel = 'noopener noreferrer',
}) => {
  const defaultLabel =
    platform === 'chesscom'
      ? 'View on Chess.com'
      : platform === 'lichess'
        ? 'View on Lichess'
        : 'View Game';

  const displayLabel = label || defaultLabel;

  return (
    <a
      href={url}
      target="_blank"
      rel={rel}
      className={`inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-400 hover:text-emerald-300 hover:underline transition ${className}`}
    >
      <span>{displayLabel}</span>
      <ExternalLink className="w-3.5 h-3.5" aria-hidden="true" />
    </a>
  );
};
