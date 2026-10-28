import React from 'react';

export interface StatusBannerProps {
  variant: 'loading' | 'empty' | 'error';
  message: string;
  title?: string;
  action?: {
    label: string;
    onClick: () => void;
  };
  className?: string;
}

export const StatusBanner: React.FC<StatusBannerProps> = ({
  variant,
  message,
  title,
  action,
  className = '',
}) => {
  if (variant === 'loading') {
    return (
      <div
        role="status"
        className={`py-16 text-center space-y-4 max-w-lg mx-auto bg-slate-900/60 p-8 rounded-2xl border border-slate-800 shadow-xl ${className}`}
      >
        <div className="w-10 h-10 border-4 border-emerald-500 border-t-transparent rounded-full animate-spin mx-auto" />
        {title && <h3 className="text-lg font-bold text-slate-100">{title}</h3>}
        <p className="text-slate-400 font-medium text-sm">{message}</p>
      </div>
    );
  }

  if (variant === 'empty') {
    return (
      <div
        className={`py-16 text-center space-y-4 max-w-lg mx-auto bg-slate-900/60 p-8 rounded-2xl border border-slate-800 shadow-xl ${className}`}
      >
        <div className="text-4xl">♟</div>
        {title && <h3 className="text-lg font-bold text-slate-100">{title}</h3>}
        <p className="text-slate-400 font-medium text-sm">{message}</p>
        {action && (
          <button
            type="button"
            onClick={action.onClick}
            className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-medium rounded-xl text-sm transition cursor-pointer"
          >
            {action.label}
          </button>
        )}
      </div>
    );
  }

  // error variant
  return (
    <div
      role="alert"
      className={`rounded-2xl border border-rose-900/60 bg-rose-950/30 p-6 text-rose-200 space-y-3 shadow-xl ${className}`}
    >
      {title && <h3 className="text-base font-bold text-rose-100">{title}</h3>}
      <p className="text-sm leading-relaxed">{message}</p>
      {action && (
        <button
          type="button"
          onClick={action.onClick}
          className="px-4 py-2 bg-rose-800/80 hover:bg-rose-700 text-rose-100 text-xs font-semibold rounded-lg transition cursor-pointer"
        >
          {action.label}
        </button>
      )}
    </div>
  );
};
