package com.pricetrack.exchange.wallet.faucet;

/** Safe stable codes only: never SQL, request keys or underlying exception text. */
public class FaucetFailure extends RuntimeException {
    private final int status;
    public FaucetFailure(int status,String code) { super(code); this.status=status; }
    public int status() { return status; }
}
