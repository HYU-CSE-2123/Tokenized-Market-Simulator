// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";

import {PriceReportTypes} from "./PriceReportTypes.sol";

/// @title PriceOracle
/// @notice 삼성전자 기준 가격(모의 원화 환산)을 온체인에 저장한다.
/// @dev 기획서 §9.4 — 가격은 1e8 정밀도(priceE8)로 저장. 백엔드/관리자가 updatePrice 로 갱신.
contract PriceOracle is Ownable, EIP712 {
    /// @notice 가격 정밀도 (1 mKRW = 1e8).
    uint256 public constant PRICE_DECIMALS = 8;
    uint256 public constant REPORT_TTL = 30 seconds;
    uint256 public constant MAX_FUTURE_SKEW = 2 seconds;

    bytes32 public immutable supportedSymbolHash;
    address public priceSigner;
    address public authorizedConsumer;
    mapping(bytes32 quoteId => bool used) public usedQuoteIds;

    /// @notice 현재 기준 가격 (1e8 단위). 예: 75,000원 = 75000 * 1e8.
    uint256 public priceE8;

    /// @notice 마지막 갱신 시각(블록 timestamp).
    uint256 public updatedAt;

    /// @notice 외부 시장에서 마지막 승인 가격이 실제로 관측된 시각.
    uint256 public priceObservedAt;

    event PriceUpdated(uint256 priceE8, uint256 updatedAt);
    event PriceSignerUpdated(address indexed previousSigner, address indexed newSigner);
    event AuthorizedConsumerUpdated(address indexed previousConsumer, address indexed newConsumer);
    event PriceReportConsumed(
        bytes32 indexed quoteId,
        uint256 priceE8,
        uint256 observedAt,
        uint256 validUntil,
        address indexed signer
    );

    error InvalidPrice();
    error InvalidAddress();
    error InvalidSymbol();
    error InvalidQuoteId();
    error InvalidValidityWindow();
    error ObservationFromFuture();
    error ReportExpired();
    error QuoteAlreadyUsed();
    error UnauthorizedConsumer();
    error InvalidPriceSigner();

    constructor(uint256 initialPriceE8, address initialPriceSigner, bytes32 symbolHash)
        Ownable(msg.sender)
        EIP712("TokenizedMarketPriceOracle", "1")
    {
        if (initialPriceSigner == address(0)) revert InvalidAddress();
        if (symbolHash == bytes32(0)) revert InvalidSymbol();
        priceSigner = initialPriceSigner;
        supportedSymbolHash = symbolHash;
        _setPrice(initialPriceE8, block.timestamp);
    }

    /// @notice 기준 가격 갱신 (관리자/백엔드).
    function updatePrice(uint256 newPriceE8) external onlyOwner {
        _setPrice(newPriceE8, block.timestamp);
    }

    /// @notice Phase 5.2 이후 Vault가 거래와 같은 트랜잭션에서 서명 가격을 승인한다.
    function consumePriceReport(PriceReportTypes.PriceReport calldata report, bytes calldata signature)
        external
        returns (uint256 approvedPriceE8)
    {
        if (msg.sender != authorizedConsumer) revert UnauthorizedConsumer();
        if (report.quoteId == bytes32(0)) revert InvalidQuoteId();
        if (report.symbolHash != supportedSymbolHash) revert InvalidSymbol();
        if (report.priceE8 == 0) revert InvalidPrice();
        if (report.observedAt > type(uint256).max - REPORT_TTL
                || report.validUntil != report.observedAt + REPORT_TTL) {
            revert InvalidValidityWindow();
        }
        if (report.observedAt > block.timestamp + MAX_FUTURE_SKEW) revert ObservationFromFuture();
        if (block.timestamp > report.validUntil) revert ReportExpired();
        if (usedQuoteIds[report.quoteId]) revert QuoteAlreadyUsed();

        bytes32 digest = _hashTypedDataV4(PriceReportTypes.hash(report));
        address recoveredSigner = ECDSA.recover(digest, signature);
        if (recoveredSigner != priceSigner) revert InvalidPriceSigner();

        usedQuoteIds[report.quoteId] = true;
        _setPrice(report.priceE8, report.observedAt);
        emit PriceReportConsumed(
            report.quoteId,
            report.priceE8,
            report.observedAt,
            report.validUntil,
            recoveredSigner
        );
        return report.priceE8;
    }

    function setPriceSigner(address newSigner) external onlyOwner {
        if (newSigner == address(0)) revert InvalidAddress();
        emit PriceSignerUpdated(priceSigner, newSigner);
        priceSigner = newSigner;
    }

    function setAuthorizedConsumer(address newConsumer) external onlyOwner {
        if (newConsumer == address(0)) revert InvalidAddress();
        emit AuthorizedConsumerUpdated(authorizedConsumer, newConsumer);
        authorizedConsumer = newConsumer;
    }

    /// @notice 현재 가격과 갱신 시각을 반환.
    function getPrice() external view returns (uint256 price, uint256 timestamp) {
        return (priceE8, updatedAt);
    }

    /// @notice 클라이언트와 테스트가 서명 전에 동일한 EIP-712 digest를 확인할 수 있다.
    function priceReportDigest(PriceReportTypes.PriceReport calldata report)
        external
        view
        returns (bytes32)
    {
        return _hashTypedDataV4(PriceReportTypes.hash(report));
    }

    function _setPrice(uint256 newPriceE8, uint256 observedAt) internal {
        if (newPriceE8 == 0) revert InvalidPrice();
        priceE8 = newPriceE8;
        priceObservedAt = observedAt;
        updatedAt = block.timestamp;
        emit PriceUpdated(newPriceE8, block.timestamp);
    }
}
