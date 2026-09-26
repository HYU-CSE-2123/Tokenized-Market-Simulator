package com.pricetrack.exchange.blockchain.oracle;

import com.pricetrack.exchange.blockchain.config.PriceReportProperties;
import com.pricetrack.exchange.blockchain.support.BlockchainConfigurationException;

import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/** 전용 개인키로 EIP-712 digest를 서명하며 개인키 자체는 외부로 노출하지 않는다. */
@Component
public class PriceReportSigner {
    private final PriceReportProperties properties;

    public PriceReportSigner(PriceReportProperties properties) {
        this.properties = properties;
    }

    public String signerAddress() {
        return credentials().getAddress();
    }

    public String sign(byte[] digest) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("EIP-712 digest는 32바이트여야 합니다.");
        }
        Sign.SignatureData signature = Sign.signMessage(digest, credentials().getEcKeyPair(), false);
        return Numeric.toHexString(signatureBytes(signature));
    }

    private Credentials credentials() {
        String privateKey = properties.signerPrivateKey();
        if (privateKey == null || privateKey.isBlank()) {
            throw new BlockchainConfigurationException("PRICE_SIGNER_PRIVATE_KEY 환경 변수가 필요합니다.");
        }
        try {
            return Credentials.create(privateKey.trim());
        } catch (RuntimeException exception) {
            throw new BlockchainConfigurationException("PRICE_SIGNER_PRIVATE_KEY 형식이 올바르지 않습니다.", exception);
        }
    }

    private byte[] signatureBytes(Sign.SignatureData signature) {
        byte[] bytes = new byte[65];
        System.arraycopy(signature.getR(), 0, bytes, 0, 32);
        System.arraycopy(signature.getS(), 0, bytes, 32, 32);
        System.arraycopy(signature.getV(), 0, bytes, 64, 1);
        return bytes;
    }
}
