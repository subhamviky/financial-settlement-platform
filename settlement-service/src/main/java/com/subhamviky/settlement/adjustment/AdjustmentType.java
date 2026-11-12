package com.subhamviky.settlement.adjustment;

/**
 * WRITE_OFF: the payee received less than settled; the difference moves from the payee
 * to the write-off expense account. REFUND: a duplicate payment is returned from the
 * payee to the payer.
 */
public enum AdjustmentType { WRITE_OFF, REFUND }
