'use client';

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Calendar, ChevronLeft, ChevronRight, X } from 'lucide-react';

const MONTH_NAMES = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

const SHORT_MONTH_NAMES = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

export function formatMonthLabel(value?: string): string | null {
  if (!value || !/^\d{4}-(0[1-9]|1[0-2])$/.test(value)) return null;
  const [yearStr, monthStr] = value.split('-');
  const monthIdx = parseInt(monthStr, 10) - 1;
  if (monthIdx >= 0 && monthIdx < 12) {
    return `${MONTH_NAMES[monthIdx]} ${yearStr}`;
  }
  return null;
}

interface MonthPickerProps {
  label: string;
  value: string; // YYYY-MM format or ''
  onChange: (newValue: string) => void;
  minYear?: number;
  maxYear?: number;
}

export const MonthPicker: React.FC<MonthPickerProps> = ({
  label,
  value,
  onChange,
  minYear = 2000,
  maxYear = new Date().getFullYear(),
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [popoverStyle, setPopoverStyle] = useState<React.CSSProperties>({});
  const triggerRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  // Parsed initial year for popover navigation
  const parsedYear = value && /^\d{4}/.test(value) ? parseInt(value.split('-')[0], 10) : new Date().getFullYear();
  const [navYear, setNavYear] = useState<number>(Math.min(Math.max(parsedYear, minYear), maxYear));

  // Sync navYear if value changes externally
  useEffect(() => {
    if (value && /^\d{4}/.test(value)) {
      const yr = parseInt(value.split('-')[0], 10);
      setNavYear(Math.min(Math.max(yr, minYear), maxYear));
    }
  }, [value, minYear, maxYear]);

  const updatePopoverPosition = useCallback(() => {
    if (!triggerRef.current || !popoverRef.current) return;

    const triggerRect = triggerRef.current.getBoundingClientRect();
    const popoverRect = popoverRef.current.getBoundingClientRect();
    const viewportHeight = window.innerHeight;
    const viewportWidth = window.innerWidth;

    const spaceBelow = viewportHeight - triggerRect.bottom;
    const spaceAbove = triggerRect.top;
    const popoverHeight = popoverRef.current.offsetHeight || 280;
    const popoverWidth = popoverRef.current.offsetWidth || 256;

    // Default: position below the trigger, left-aligned
    let top = triggerRect.bottom + 8;
    let left = triggerRect.left;

    // If not enough room below, try positioning above
    if (spaceBelow < popoverHeight && spaceAbove > spaceBelow) {
      top = triggerRect.top - popoverHeight - 8;
    }

    // Keep within horizontal viewport bounds
    if (left + popoverWidth > viewportWidth - 8) {
      left = viewportWidth - popoverWidth - 8;
    }
    if (left < 8) {
      left = 8;
    }

    setPopoverStyle({
      position: 'fixed',
      top: `${top}px`,
      left: `${left}px`,
      zIndex: 9999,
    });
  }, []);

  // Click outside to close popover
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      const target = e.target as Node;
      if (
        popoverRef.current &&
        !popoverRef.current.contains(target) &&
        triggerRef.current &&
        !triggerRef.current.contains(target)
      ) {
        setIsOpen(false);
      }
    };
    if (isOpen) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  // Reposition on open and on scroll/resize
  useEffect(() => {
    if (isOpen) {
      // Use requestAnimationFrame to ensure popover is rendered before measuring
      requestAnimationFrame(() => {
        updatePopoverPosition();
      });
    }
  }, [isOpen, updatePopoverPosition]);

  useEffect(() => {
    if (!isOpen) return;
    const handleReposition = () => updatePopoverPosition();
    window.addEventListener('scroll', handleReposition, true);
    window.addEventListener('resize', handleReposition);
    return () => {
      window.removeEventListener('scroll', handleReposition, true);
      window.removeEventListener('resize', handleReposition);
    };
  }, [isOpen, updatePopoverPosition]);

  const displayLabel = formatMonthLabel(value);

  const handleSelectMonth = (monthIdx: number) => {
    const monthNum = (monthIdx + 1).toString().padStart(2, '0');
    const isoValue = `${navYear}-${monthNum}`;
    onChange(isoValue);
    setIsOpen(false);
  };

  const handleClear = (e: React.MouseEvent) => {
    e.stopPropagation();
    onChange('');
  };

  const handlePrevYear = () => {
    if (navYear > minYear) {
      setNavYear((y) => y - 1);
    }
  };

  const handleNextYear = () => {
    if (navYear < maxYear) {
      setNavYear((y) => y + 1);
    }
  };

  return (
    <div className="space-y-1">
      <span className="text-[11px] font-semibold text-slate-400">{label}</span>
      <div>
        <button
          ref={triggerRef}
          type="button"
          onClick={() => setIsOpen((prev) => !prev)}
          aria-expanded={isOpen}
          aria-label={label}
          aria-haspopup="dialog"
          className="w-full px-3 py-2 bg-slate-950 border border-slate-800 hover:border-slate-700 focus:border-emerald-500 rounded-xl text-xs font-medium text-slate-200 flex items-center justify-between transition cursor-pointer"
        >
          <div className="flex items-center space-x-2 truncate">
            <Calendar className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
            <span className={displayLabel ? 'text-slate-100 font-semibold font-mono' : 'text-slate-500 font-normal'}>
              {displayLabel || 'Select month...'}
            </span>
          </div>
          {value ? (
            <span
              role="button"
              tabIndex={0}
              onClick={handleClear}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.stopPropagation();
                  onChange('');
                }
              }}
              title="Clear selection"
              aria-label="Clear month selection"
              className="p-0.5 rounded text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition cursor-pointer shrink-0"
            >
              <X className="w-3.5 h-3.5" />
            </span>
          ) : null}
        </button>

        {/* Popover Selection Panel - rendered as fixed overlay */}
        {isOpen && (
          <div
            ref={popoverRef}
            style={popoverStyle}
            className="w-64 bg-slate-900 border border-slate-800 rounded-2xl p-3.5 shadow-2xl animate-in fade-in zoom-in-95 duration-150 space-y-3"
            role="dialog"
            aria-label={`${label} picker`}
          >
            {/* Popover Year Navigation Header */}
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <button
                type="button"
                onClick={handlePrevYear}
                disabled={navYear <= minYear}
                aria-label="Previous year"
                className="p-1 rounded-lg hover:bg-slate-800 disabled:opacity-30 disabled:hover:bg-transparent text-slate-300 transition cursor-pointer disabled:cursor-not-allowed"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <span className="text-xs font-bold font-mono text-white tracking-wider">
                {navYear}
              </span>
              <button
                type="button"
                onClick={handleNextYear}
                disabled={navYear >= maxYear}
                aria-label="Next year"
                className="p-1 rounded-lg hover:bg-slate-800 disabled:opacity-30 disabled:hover:bg-transparent text-slate-300 transition cursor-pointer disabled:cursor-not-allowed"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>

            {/* 12-Month Selection Grid */}
            <div className="grid grid-cols-3 gap-1.5">
              {SHORT_MONTH_NAMES.map((shortName, idx) => {
                const monthNumStr = (idx + 1).toString().padStart(2, '0');
                const isSelected = value === `${navYear}-${monthNumStr}`;
                return (
                  <button
                    key={shortName}
                    type="button"
                    onClick={() => handleSelectMonth(idx)}
                    className={`py-2 text-xs font-bold rounded-xl transition cursor-pointer ${
                      isSelected
                        ? 'bg-emerald-600 text-white shadow-sm shadow-emerald-950'
                        : 'bg-slate-950 text-slate-300 hover:bg-slate-800 hover:text-white border border-slate-800/60'
                    }`}
                  >
                    {shortName}
                  </button>
                );
              })}
            </div>

            {/* Popover Footer Action */}
            {value ? (
              <div className="pt-2 border-t border-slate-800 flex justify-end">
                <button
                  type="button"
                  onClick={() => {
                    onChange('');
                    setIsOpen(false);
                  }}
                  className="text-[11px] font-semibold text-rose-400 hover:text-rose-300 transition cursor-pointer"
                >
                  Clear date
                </button>
              </div>
            ) : null}
          </div>
        )}
      </div>
    </div>
  );
};
