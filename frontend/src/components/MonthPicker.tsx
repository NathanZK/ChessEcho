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
  const [trackedValueBounds, setTrackedValueBounds] = useState({ value, minYear, maxYear });
  if (
    trackedValueBounds.value !== value ||
    trackedValueBounds.minYear !== minYear ||
    trackedValueBounds.maxYear !== maxYear
  ) {
    setTrackedValueBounds({ value, minYear, maxYear });
    if (value && /^\d{4}/.test(value)) {
      const yr = parseInt(value.split('-')[0], 10);
      setNavYear(Math.min(Math.max(yr, minYear), maxYear));
    }
  }

  const updatePopoverPosition = useCallback(() => {
    if (!triggerRef.current || !popoverRef.current) return;

    const triggerRect = triggerRef.current.getBoundingClientRect();
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight;
    const viewportWidth = window.innerWidth || document.documentElement.clientWidth;

    const popoverHeight = popoverRef.current.offsetHeight || 280;
    const popoverWidth = popoverRef.current.offsetWidth || 256;

    // Strict clamping within horizontal viewport bounds
    const maxLeft = Math.max(8, viewportWidth - popoverWidth - 8);
    const left = Math.max(8, Math.min(triggerRect.left, maxLeft));

    // Vertical positioning
    const spaceBelow = viewportHeight - triggerRect.bottom;
    const spaceAbove = triggerRect.top;
    let top = triggerRect.bottom + 8;
    if (spaceBelow < popoverHeight && spaceAbove > spaceBelow) {
      top = Math.max(8, triggerRect.top - popoverHeight - 8);
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
      <span className="text-[11px] font-semibold text-on-surface-variant font-mono">{label}</span>
      <div>
        <button
          ref={triggerRef}
          type="button"
          onClick={() => setIsOpen((prev) => !prev)}
          aria-expanded={isOpen}
          aria-label={label}
          aria-haspopup="dialog"
          className="w-full px-3 py-2 bg-surface-container-lowest border border-outline-variant hover:border-outline focus:border-primary rounded-xl text-xs font-medium text-on-surface flex items-center justify-between transition cursor-pointer"
        >
          <div className="flex items-center space-x-2 truncate">
            <Calendar className="w-3.5 h-3.5 text-primary shrink-0" />
            <span className={displayLabel ? 'text-on-surface font-semibold font-mono' : 'text-on-surface-variant font-normal font-sans'}>
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
              className="p-0.5 rounded text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition cursor-pointer shrink-0"
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
            className="fixed z-[9999] w-64 max-w-[calc(100vw-1rem)] bg-surface-container-low border border-outline-variant rounded-2xl p-3.5 shadow-2xl animate-in fade-in zoom-in-95 duration-150 space-y-3"
            role="dialog"
            aria-label={`${label} picker`}
          >
            {/* Popover Year Navigation Header */}
            <div className="flex items-center justify-between border-b border-outline-variant pb-2">
              <button
                type="button"
                onClick={handlePrevYear}
                disabled={navYear <= minYear}
                aria-label="Previous year"
                className="p-1 rounded-lg hover:bg-surface-container-high disabled:opacity-30 disabled:hover:bg-transparent text-on-surface-variant transition cursor-pointer disabled:cursor-not-allowed"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <span className="text-xs font-bold font-mono text-on-surface tracking-wider">
                {navYear}
              </span>
              <button
                type="button"
                onClick={handleNextYear}
                disabled={navYear >= maxYear}
                aria-label="Next year"
                className="p-1 rounded-lg hover:bg-surface-container-high disabled:opacity-30 disabled:hover:bg-transparent text-on-surface-variant transition cursor-pointer disabled:cursor-not-allowed"
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
                        ? 'bg-primary-container text-on-primary-container font-mono shadow-sm shadow-black/20'
                        : 'bg-surface-container-lowest text-on-surface-variant hover:bg-surface-container-high hover:text-on-surface border border-outline-variant/60 font-mono'
                    }`}
                  >
                    {shortName}
                  </button>
                );
              })}
            </div>

            {/* Popover Footer Action */}
            {value ? (
              <div className="pt-2 border-t border-outline-variant flex justify-end">
                <button
                  type="button"
                  onClick={() => {
                    onChange('');
                    setIsOpen(false);
                  }}
                  className="text-[11px] font-semibold font-mono text-error hover:underline transition cursor-pointer"
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
