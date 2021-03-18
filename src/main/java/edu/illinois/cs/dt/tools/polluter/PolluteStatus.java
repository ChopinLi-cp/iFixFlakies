package edu.illinois.cs.dt.tools.polluter;

public enum PolluteStatus {
    NOD,
    NO_DEPS,
    UNSUPPORTED,
    MISSING_METHOD,
    NOT_FAILING,
    NO_CLEANER,
    CLEANER_FAIL,
    FIX_INVALID,
    FIX_NO_INLINE,
    FIX_INLINE,
    FIX_NO_INLINE_CANREMOVE,
    FIX_INLINE_CANREMOVE,
    FIX_NO_INLINE_SETUPTEARDOWN,
    FIX_INLINE_SETUPTEARDOWN
}
