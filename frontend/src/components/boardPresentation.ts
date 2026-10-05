import type { CSSProperties } from 'react';

// Shared by the puzzle board and the blindfold board so both render identically.
export const BOARD_CONTAINER_CLASS = 'flex flex-col space-y-2.5 w-full max-w-[640px] 2xl:max-w-[760px] mx-auto';
export const BOARD_FRAME_CLASS = 'rounded-2xl overflow-hidden shadow-2xl border border-slate-800 bg-slate-900 p-2';
export const BOARD_DARK_SQUARE_STYLE: CSSProperties = { backgroundColor: '#769656' };
export const BOARD_LIGHT_SQUARE_STYLE: CSSProperties = { backgroundColor: '#eeeed2' };
