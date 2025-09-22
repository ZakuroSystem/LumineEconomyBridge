"""Shared helpers for handling decimal precision in persisted amounts."""

from __future__ import annotations

from decimal import Decimal, ROUND_HALF_UP

DECIMAL_PLACES_KEY = "decimal_places"
"""Settings table key used to persist the configured decimal mode."""

ALLOWED_DECIMAL_PLACES: tuple[int, ...] = (0, 2, 4)
"""Set of supported fractional digit counts."""

DEFAULT_DECIMAL_PLACES = 2
"""Default fractional digits used when no explicit preference is provided."""

LEGACY_DEFAULT_DECIMAL_PLACES = 4
"""Fractional digits assumed for databases created before this feature."""


def clamp_decimal_places(value: int) -> int:
    """Return ``value`` if supported, otherwise the default."""

    return value if value in ALLOWED_DECIMAL_PLACES else DEFAULT_DECIMAL_PLACES


def compute_scale(places: int) -> int:
    """Return the integer scale factor for ``places`` fractional digits."""

    return 10 ** max(0, places)


def rescale_value(value: int, old_places: int, new_places: int) -> int:
    """Convert ``value`` from ``old_places`` digits to ``new_places`` digits.

    The conversion keeps the represented monetary amount identical by scaling the
    stored integer and rounding half away from zero.
    """

    if value == 0 or old_places == new_places:
        return value
    old_scale = compute_scale(old_places)
    new_scale = compute_scale(new_places)
    dec_value = (Decimal(value) * Decimal(new_scale)) / Decimal(old_scale)
    return int(dec_value.to_integral_value(rounding=ROUND_HALF_UP))


__all__ = [
    "DECIMAL_PLACES_KEY",
    "ALLOWED_DECIMAL_PLACES",
    "DEFAULT_DECIMAL_PLACES",
    "LEGACY_DEFAULT_DECIMAL_PLACES",
    "clamp_decimal_places",
    "compute_scale",
    "rescale_value",
]
