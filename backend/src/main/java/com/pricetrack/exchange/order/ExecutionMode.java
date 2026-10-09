package com.pricetrack.exchange.order;

/** Order is authoritative; legacy rows are deliberately not inferred from txHash. */
public enum ExecutionMode { UNKNOWN, ONCHAIN, DB_ONLY }
