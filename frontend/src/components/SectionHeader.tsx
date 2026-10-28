import React from 'react';

export interface SectionHeaderProps {
  title: string;
  description?: string;
  badgeText?: string;
  actions?: React.ReactNode;
  className?: string;
  id?: string;
}

export const SectionHeader: React.FC<SectionHeaderProps> = ({
  title,
  description,
  badgeText,
  actions,
  className = '',
  id,
}) => {
  return (
    <div
      className={`flex flex-col md:flex-row md:items-center justify-between gap-4 pb-4 border-b border-slate-800 ${className}`}
    >
      <div className="space-y-1">
        <div className="flex items-center gap-2.5">
          <h2 id={id} className="text-xl sm:text-2xl font-bold tracking-tight text-white">{title}</h2>
          {badgeText && (
            <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
              {badgeText}
            </span>
          )}
        </div>
        {description && <p className="text-sm text-slate-400">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-3 shrink-0">{actions}</div>}
    </div>
  );
};
