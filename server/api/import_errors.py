# SPDX-License-Identifier: GPL-3.0-or-later
"""Finite public errors; upstream messages and credentials never leave the worker."""
class ImportFailure(Exception):
    def __init__(self, code, unavailable=False):
        self.code=code
        self.unavailable=unavailable
        super().__init__(code)

class Cancelled(Exception):
    pass

def check_cancel(event):
    if event.is_set():raise Cancelled()
