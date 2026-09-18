import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import RegisterPage from '../app/register/page';

/**
 * Issue #335 — the password-setting field on the registration form must offer an
 * eye-style show/hide control so users can verify what they typed.
 *
 * Written test-first: today the field is permanently masked and no toggle
 * exists, so these assertions are red until the production change lands.
 */

const routerMocks = vi.hoisted(() => ({
  push: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: routerMocks.push }),
}));

const apiMocks = vi.hoisted(() => ({
  register: vi.fn(),
}));

vi.mock('@/services/api', () => ({
  register: apiMocks.register,
}));

function getPasswordInput(): HTMLInputElement {
  return screen.getByLabelText(/password/i, { selector: 'input' }) as HTMLInputElement;
}

describe('registration password visibility toggle', () => {
  beforeEach(() => {
    routerMocks.push.mockReset();
    apiMocks.register.mockReset();
    apiMocks.register.mockResolvedValue({ status: 'authenticated' });
  });

  it('masks the password and exposes a show control by default', () => {
    render(<RegisterPage />);

    expect(getPasswordInput()).toHaveAttribute('type', 'password');

    const toggle = screen.getByRole('button', { name: /show password/i });
    expect(toggle).toHaveAttribute('type', 'button');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
  });

  it('reveals the password and updates the accessible label when activated', () => {
    render(<RegisterPage />);

    fireEvent.change(getPasswordInput(), { target: { value: 'correct-horse' } });
    fireEvent.click(screen.getByRole('button', { name: /show password/i }));

    const input = getPasswordInput();
    expect(input).toHaveAttribute('type', 'text');
    expect(input.value).toBe('correct-horse');

    const toggle = screen.getByRole('button', { name: /hide password/i });
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
  });

  it('re-masks the password on a second activation while preserving the value', () => {
    render(<RegisterPage />);

    fireEvent.change(getPasswordInput(), { target: { value: 'correct-horse' } });
    fireEvent.click(screen.getByRole('button', { name: /show password/i }));
    fireEvent.click(screen.getByRole('button', { name: /hide password/i }));

    const input = getPasswordInput();
    expect(input).toHaveAttribute('type', 'password');
    expect(input.value).toBe('correct-horse');
    expect(screen.getByRole('button', { name: /show password/i })).toHaveAttribute('aria-pressed', 'false');
  });

  it('does not submit the registration form when the toggle is activated', () => {
    render(<RegisterPage />);

    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'player@example.com' } });
    fireEvent.change(getPasswordInput(), { target: { value: 'correct-horse' } });
    fireEvent.click(screen.getByRole('button', { name: /show password/i }));

    expect(apiMocks.register).not.toHaveBeenCalled();
  });
});
