package com.dynokore.platform.outboxandqueue

/** Thrown by [KillableTransactionRunner] to stand for the process dying before commit. */
class ProcessKilled : RuntimeException("process killed before commit (fixture)")
