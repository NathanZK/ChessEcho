import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import LoginPage from '../app/login/page';

const routerMocks = vi.hoisted(() => ({
  push: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: routerMocks.push }),
}));

const apiMocks = vi.hoisted(() => ({
  login: vi.fn(),
}));

vi.mock('@/services/api', () => ({
  login: apiMocks.login,
}));

function getPasswordInput(): HTMLInputElement {
  return screen.getByLabelText(/password/i, { selector: 'input' }) as HTMLInputElement;
}

describe('sign-in password visibility toggle', () => {
  beforeEach(() => {
    routerMocks.push.mockReset();
    apiMocks.login.mockReset();
    apiMocks.login.mockResolvedValue({ status: 'authenticated' });
  });

  it('starts masked and exposes an accessible, non-submitting toggle', () => {
    render(<LoginPage />);

    expect(getPasswordInput()).toHaveAttribute('type', 'password');
    expect(getPasswordInput()).toHaveAttribute('autoComplete', 'current-password');
    expect(getPasswordInput()).toBeRequired();
    expect(getPasswordInput()).toHaveAttribute('minLength', '8');

    const toggle = screen.getByRole('button', { name: /show password/i });
    expect(toggle).toHaveAttribute('type', 'button');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(toggle.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('reveals and re-masks the same password value when clicked', async () => {
    const user = userEvent.setup();
    render(<LoginPage />);

    fireEvent.change(getPasswordInput(), { target: { value: 'correct-horse' } });
    await user.click(screen.getByRole('button', { name: /show password/i }));

    expect(getPasswordInput()).toHaveAttribute('type', 'text');
    expect(getPasswordInput().value).toBe('correct-horse');
    expect(screen.getByRole('button', { name: /hide password/i })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: /hide password/i }));

    expect(getPasswordInput()).toHaveAttribute('type', 'password');
    expect(getPasswordInput().value).toBe('correct-horse');
    expect(screen.getByRole('button', { name: /show password/i })).toHaveAttribute('aria-pressed', 'false');
  });

  it('toggles with Enter and Space without submitting the form', async () => {
    const user = userEvent.setup();
    render(<LoginPage />);

    fireEvent.change(getPasswordInput(), { target: { value: 'correct-horse' } });
    const toggle = screen.getByRole('button', { name: /show password/i });
    toggle.focus();

    await user.keyboard('{Enter}');
    expect(getPasswordInput()).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: /hide password/i })).toHaveAttribute('aria-pressed', 'true');

    await user.keyboard('[Space]');
    expect(getPasswordInput()).toHaveAttribute('type', 'password');
    expect(getPasswordInput().value).toBe('correct-horse');
    expect(screen.getByRole('button', { name: /show password/i })).toHaveAttribute('aria-pressed', 'false');
    expect(apiMocks.login).not.toHaveBeenCalled();
  });

  it('submits the original email and password after toggling visibility', async () => {
    const user = userEvent.setup();
    render(<LoginPage />);

    await user.type(screen.getByLabelText(/email/i), 'player@example.com');
    await user.type(getPasswordInput(), 'correct-horse');
    await user.click(screen.getByRole('button', { name: /show password/i }));
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(apiMocks.login).toHaveBeenCalledWith('player@example.com', 'correct-horse');
      expect(routerMocks.push).toHaveBeenCalledWith('/');
    });
  });
});
