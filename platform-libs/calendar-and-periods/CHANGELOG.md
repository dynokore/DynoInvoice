# Changelog

All notable changes to `calendar-and-periods` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-10-15
### Added
- Period generation from a registration date, a filing-frequency history and a calendar pack: every period record
  carries explicit start and end dates, never derived from a frequency constant (INV-REQ-83, INV-REQ-707).
- First, final (F8) and transitional periods generated explicitly and flagged irregular, unconfirmed until
  `confirmPeriods`; a mid-period frequency change yields a transitional period of explicit length (INV-REQ-84,
  INV-REQ-688). How the pack cuts it is pack data (`TransitionStyle`).
- Due-date derivation with the non-business-day rule as a parameter (`NO_MOVE`, `NEXT_WORKING_DAY`); weekend days
  and the public holiday list come from the calendar pack, which may pin the rule (INV-REQ-87).
- Period readiness gating as a pure function of a period's completeness controls.
