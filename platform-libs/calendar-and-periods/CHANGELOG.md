# Changelog

All notable changes to `calendar-and-periods` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.3.0] - 2026-10-18
### Added
- Despatch windows: `BatchWindow`, `severalDailyInterval`, `nextWindowOpening`, `despatchAt` (with `Despatch` and
  `DespatchReason`; a document whose hard stop falls before the next opening goes at once), `weeklyWindowFits`,
  `shortestGap` and `CalendarPack.windowsOf`. All inputs are arguments; the library reads no time source (INV-REQ-169,
  INV-REQ-73, INV-AC-179, INV-AC-190, INV-AC-198, INV-AC-204, INV-AC-2267; INV-T-6417).

## [1.2.0] - 2026-10-18
### Added
- Obligation clocks: `hardStop`, `capAtHardStop`, `escalationSchedule` (T1, T2 capped at the hard stop, with the facts
  `t2PulledForward`, `t1NotBeforeT2`, `hardStopPassed`), `responseDeadline` (pack period, tighten-only tenant period)
  and `noticeDue`. They return facts; the service decides (INV-REQ-572, INV-REQ-1401, INV-REQ-1403, INV-AC-1692,
  INV-AC-1404, INV-AC-2312; INV-T-6103).

## [1.1.0] - 2026-10-18
### Added
- Transaction-date period assignment: `PeriodSet.of` (overlap refused), `TransactionTimestamp` (`Explicit`,
  `ZoneLess`) and `assignPeriod`, which returns `Assigned` or a typed `NoPeriod` with a decision trace. Both zones are
  arguments; the library reads no clock (INV-REQ-76, INV-REQ-77, INV-AC-118, INV-AC-119, INV-AC-199; INV-T-6099).

## [1.0.0] - 2026-10-15
### Added
- Period generation from a registration date, a filing-frequency history and a calendar pack: every period record
  carries explicit start and end dates, never derived from a frequency constant (INV-REQ-83, INV-REQ-707).
- First, final (F8) and transitional periods generated explicitly and flagged irregular, unconfirmed until
  `confirmPeriods`; a mid-period frequency change yields a transitional period of explicit length (INV-REQ-84,
  INV-REQ-688). The period is cut the day before the
  change takes effect and the next one starts on the change date (INV-AC-154, owner answer INV-Q-5045 A).
- Due-date derivation with the non-business-day rule as a parameter (`NO_MOVE`, `NEXT_WORKING_DAY`); weekend days
  and the public holiday list come from the calendar pack, which may pin the rule (INV-REQ-87).
- Period readiness gating as a pure function of a period's completeness controls.
