package com.pricetrack.exchange.blockchain.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

class PriceReportEip712Test {
    private static final BigInteger SIGNER_KEY = new BigInteger("a11ce", 16);
    private static final String VERIFYING_CONTRACT = "0x1111111111111111111111111111111111111111";
    private static final String EXPECTED_DIGEST =
            "0x17997e214c5c57f7030b1c2f588f97a3e4353a383135e96714018aafad246b3e";
    private static final String EXPECTED_SIGNER = "0xe05fcc23807536bee418f142d19fa0d21bb0cff7";
    private static final String EXPECTED_SIGNATURE =
            "b14c5d2dfd6a49f5c5f6b16542f9ce5365eb897f7b641672ddc42a2a7f6e1afd"
            + "03c89b25a1439c1fcb9d7630575ba995e6b6f90d7a4d4ee6c17f3889909acceb1c";

    @Test
    void sharedVectorSignsAndRecoversTheConfiguredSigner() throws Exception {
        PriceReport report = sharedReport();
        byte[] digest = PriceReportEip712.digest(report, BigInteger.valueOf(31_337), VERIFYING_CONTRACT);
        ECKeyPair keyPair = ECKeyPair.create(SIGNER_KEY);
        Sign.SignatureData signature = Sign.signMessage(digest, keyPair, false);
        BigInteger recovered = Sign.signedMessageHashToKey(digest, signature);

        assertThat(Numeric.toHexString(digest)).isEqualTo(EXPECTED_DIGEST);
        assertThat("0x" + Keys.getAddress(keyPair)).isEqualTo(EXPECTED_SIGNER);
        assertThat("0x" + Keys.getAddress(recovered)).isEqualTo(EXPECTED_SIGNER);
        assertThat(signatureHex(signature)).isEqualTo(EXPECTED_SIGNATURE);
    }

    @Test
    void domainSeparatesChainAndVerifyingContract() {
        PriceReport report = sharedReport();
        String local = PriceReportEip712.digestHex(report, BigInteger.valueOf(31_337), VERIFYING_CONTRACT);

        assertThat(PriceReportEip712.digestHex(report, BigInteger.ONE, VERIFYING_CONTRACT))
                .isNotEqualTo(local);
        assertThat(PriceReportEip712.digestHex(report, BigInteger.valueOf(31_337),
                "0x3333333333333333333333333333333333333333")).isNotEqualTo(local);
    }

    @Test
    void rejectsInvalidFixedWidthFields() {
        PriceReport invalidQuote = new PriceReport("0x01", sharedReport().symbolHash(),
                sharedReport().priceE8(), sharedReport().observedAt(), sharedReport().validUntil(),
                PriceReport.Side.BUY, sharedReport().inputAmount(), sharedReport().minimumOutput(),
                sharedReport().executor());

        assertThatThrownBy(() -> PriceReportEip712.digest(invalidQuote,
                BigInteger.valueOf(31_337), VERIFYING_CONTRACT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quoteId");
    }

    private static PriceReport sharedReport() {
        return new PriceReport(
                PriceReportEip712.hashText("quote-20260926-0001"),
                PriceReportEip712.hashText("mSEC"),
                BigInteger.valueOf(75_300).multiply(BigInteger.TEN.pow(8)),
                BigInteger.valueOf(1_790_393_400L),
                BigInteger.valueOf(1_790_393_430L),
                PriceReport.Side.BUY,
                BigInteger.valueOf(100_000).multiply(BigInteger.TEN.pow(18)),
                new BigInteger("1320000000000000000"),
                "0x2222222222222222222222222222222222222222");
    }

    private static String signatureHex(Sign.SignatureData signature) {
        return Numeric.toHexStringNoPrefix(signature.getR())
                + Numeric.toHexStringNoPrefix(signature.getS())
                + Numeric.toHexStringNoPrefix(signature.getV());
    }
}
