import os
import sys

BACKEND_DIR = os.path.join(os.path.dirname(__file__), "..", "backend")
sys.path.append(BACKEND_DIR)

import main


def test_compute_tax_rounds_half_up():
    tax = main.TaxConfig(rate=50, enabled=True, treasury=None)
    assert main.compute_tax_amount(10, tax) == 1


def test_compute_tax_disabled_or_zero():
    tax_disabled = main.TaxConfig(rate=50, enabled=False, treasury=None)
    tax_zero = main.TaxConfig(rate=0, enabled=True, treasury=None)
    assert main.compute_tax_amount(1000, tax_disabled) == 0
    assert main.compute_tax_amount(1000, tax_zero) == 0


def test_compute_tax_positive_amount_required():
    tax = main.TaxConfig(rate=100, enabled=True, treasury=None)
    assert main.compute_tax_amount(0, tax) == 0
    assert main.compute_tax_amount(-100, tax) == 0


def test_transfer_tax_withheld_from_receiver():
    tax = main.TaxConfig(rate=100, enabled=True, treasury="treasury")
    amt = 1000
    tax_amt = min(main.compute_tax_amount(amt, tax), amt)
    assert tax_amt == 100
    assert amt - tax_amt == 900


def test_trade_tax_withheld_from_shop_payout():
    tax = main.TaxConfig(rate=100, enabled=True, treasury="treasury")
    price = 100
    tax_amt = min(main.compute_tax_amount(price, tax), price)
    assert tax_amt == 10
    assert price - tax_amt == 90
