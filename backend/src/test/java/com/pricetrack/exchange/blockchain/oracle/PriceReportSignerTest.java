package com.pricetrack.exchange.blockchain.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pricetrack.exchange.blockchain.config.PriceReportProperties;
import com.pricetrack.exchange.blockchain.support.BlockchainConfigurationException;

import org.junit.jupiter.api.Test;
import org.web3j.utils.Numeric;

class PriceReportSignerTest {
    @Test
    void signsDigestWithDedicatedKeyAsSixtyFiveBytes() {
        PriceReportSigner signer = new PriceReportSigner(new PriceReportProperties(true, "a11ce"));

        String signature = signer.sign(new byte[32]);

        assertThat(Numeric.hexStringToByteArray(signature)).hasSize(65);
        assertThat(signer.signerAddress()).isEqualToIgnoringCase("0xe05fcc23807536bee418f142d19fa0d21bb0cff7");
    }

    @Test
    void rejectsMissingKeyWithoutLeakingIt() {
        PriceReportSigner signer = new PriceReportSigner(new PriceReportProperties(true, ""));

        assertThatThrownBy(signer::signerAddress)
                .isInstanceOf(BlockchainConfigurationException.class)
                .hasMessageContaining("PRICE_SIGNER_PRIVATE_KEY");
    }

    @Test
    void rejectsInvalidKeyFormat() {
        PriceReportSigner signer = new PriceReportSigner(new PriceReportProperties(true, "not-a-private-key"));
        assertThatThrownBy(signer::signerAddress)
                .isInstanceOf(BlockchainConfigurationException.class)
                .hasMessageContaining("형식이 올바르지 않습니다");
    }

    @Test
    void rejectsNonDigestInput() {
        PriceReportSigner signer = new PriceReportSigner(new PriceReportProperties(true, "a11ce"));
        assertThatThrownBy(() -> signer.sign(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트");
    }
}
