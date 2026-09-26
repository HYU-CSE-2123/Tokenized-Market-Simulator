package com.pricetrack.exchange.blockchain.oracle;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/** EIP-712 가격 보고서 digest를 Solidity abi.encode 규칙과 동일하게 계산한다. */
public final class PriceReportEip712 {
    public static final String DOMAIN_NAME = "TokenizedMarketPriceOracle";
    public static final String DOMAIN_VERSION = "1";
    public static final String TYPE = "PriceReport(bytes32 quoteId,bytes32 symbolHash,uint256 priceE8,"
            + "uint256 observedAt,uint256 validUntil,uint8 side,uint256 inputAmount,"
            + "uint256 minimumOutput,address executor)";

    private static final byte[] DOMAIN_TYPE_HASH = hash(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    private static final byte[] REPORT_TYPE_HASH = hash(TYPE);
    private static final byte[] DOMAIN_NAME_HASH = hash(DOMAIN_NAME);
    private static final byte[] DOMAIN_VERSION_HASH = hash(DOMAIN_VERSION);

    private PriceReportEip712() {}

    public static byte[] digest(PriceReport report, BigInteger chainId, String verifyingContract) {
        byte[] domainSeparator = Hash.sha3(concat(
                DOMAIN_TYPE_HASH,
                DOMAIN_NAME_HASH,
                DOMAIN_VERSION_HASH,
                uint256(chainId),
                address(verifyingContract)));
        byte[] structHash = Hash.sha3(concat(
                REPORT_TYPE_HASH,
                bytes32(report.quoteId(), "quoteId"),
                bytes32(report.symbolHash(), "symbolHash"),
                uint256(report.priceE8()),
                uint256(report.observedAt()),
                uint256(report.validUntil()),
                uint256(BigInteger.valueOf(report.side().code())),
                uint256(report.inputAmount()),
                uint256(report.minimumOutput()),
                address(report.executor())));
        return Hash.sha3(concat(new byte[] {0x19, 0x01}, domainSeparator, structHash));
    }

    public static String digestHex(PriceReport report, BigInteger chainId, String verifyingContract) {
        return Numeric.toHexString(digest(report, chainId, verifyingContract));
    }

    public static String hashText(String value) {
        return Numeric.toHexString(hash(value));
    }

    private static byte[] hash(String value) {
        return Hash.sha3(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] uint256(BigInteger value) {
        if (value == null || value.signum() < 0 || value.bitLength() > 256) {
            throw new IllegalArgumentException("uint256 범위를 벗어난 값입니다.");
        }
        return Numeric.toBytesPadded(value, 32);
    }

    private static byte[] bytes32(String value, String field) {
        byte[] decoded = Numeric.hexStringToByteArray(value);
        if (decoded.length != 32) throw new IllegalArgumentException(field + "는 bytes32여야 합니다.");
        return decoded;
    }

    private static byte[] address(String value) {
        if (value == null || !value.matches("(?i)^0x[0-9a-f]{40}$")) {
            throw new IllegalArgumentException("EVM 주소 형식이 올바르지 않습니다.");
        }
        return Numeric.toBytesPadded(Numeric.toBigInt(value), 32);
    }

    private static byte[] concat(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }
}
