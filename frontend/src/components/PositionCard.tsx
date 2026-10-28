import React from 'react';

export interface PositionCardProps {
  board?: React.ReactNode;
  title?: React.ReactNode;
  metadata?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
  children?: React.ReactNode;
}

export const PositionCard: React.FC<PositionCardProps> = ({
  board,
  title,
  metadata,
  actions,
  className = '',
  children,
}) => {
  return (
    <article
      className={`bg-slate-900 rounded-2xl p-4 sm:p-5 border border-slate-800 hover:border-slate-700 shadow-xl transition flex flex-col justify-between space-y-4 ${className}`}
    >
      {children ? (
        children
      ) : (
        <>
          <div className="space-y-4">
            <div className="flex flex-col sm:flex-row items-center sm:items-start gap-4">
              {board && (
                <div className="w-[180px] h-[180px] shrink-0 rounded-xl overflow-hidden border border-slate-800 shadow-md flex items-center justify-center">
                  {board}
                </div>
              )}
              <div className="flex-1 space-y-3 w-full min-w-0">
                {title}
                {metadata}
              </div>
            </div>
          </div>
          {actions && (
            <div className="pt-2 border-t border-slate-800/80 flex flex-wrap items-center justify-between gap-3">
              {actions}
            </div>
          )}
        </>
      )}
    </article>
  );
};
