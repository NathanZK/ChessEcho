#!/usr/bin/python3
"""Deterministic bounded agent fixture for runtime tests."""

import sys


if sys.argv[1] != "--request-binding":
    raise SystemExit(2)
sys.stdout.write(sys.argv[2])
