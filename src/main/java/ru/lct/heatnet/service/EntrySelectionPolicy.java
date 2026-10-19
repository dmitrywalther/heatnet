package ru.lct.heatnet.service;

/** Ограниченная эвристика 1-opt для близких нормальных вводов; не норматив. */
final class EntrySelectionPolicy {

    static final EntrySelectionPolicy DEFAULT = new EntrySelectionPolicy(1.0, 3);

    final double extraInternalLengthM;
    final int maxAlternativeEntries;

    EntrySelectionPolicy(double extraInternalLengthM, int maxAlternativeEntries) {
        if (!Double.isFinite(extraInternalLengthM) || extraInternalLengthM < 0
                || maxAlternativeEntries < 0) {
            throw new IllegalArgumentException("Invalid short-entry search policy");
        }
        this.extraInternalLengthM = extraInternalLengthM;
        this.maxAlternativeEntries = maxAlternativeEntries;
    }
}
