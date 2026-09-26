// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @notice 백엔드와 컨트랙트가 공유하는 주문별 EIP-712 가격 보고서 형식이다.
library PriceReportTypes {
    bytes32 internal constant TYPE_HASH = keccak256(
        "PriceReport(bytes32 quoteId,bytes32 symbolHash,uint256 priceE8,uint256 observedAt,uint256 validUntil,uint8 side,uint256 inputAmount,uint256 minimumOutput,address executor)"
    );

    uint8 internal constant SIDE_BUY = 0;
    uint8 internal constant SIDE_SELL = 1;

    struct PriceReport {
        bytes32 quoteId;
        bytes32 symbolHash;
        uint256 priceE8;
        uint256 observedAt;
        uint256 validUntil;
        uint8 side;
        uint256 inputAmount;
        uint256 minimumOutput;
        address executor;
    }

    function hash(PriceReport memory report) internal pure returns (bytes32) {
        return keccak256(abi.encode(
            TYPE_HASH,
            report.quoteId,
            report.symbolHash,
            report.priceE8,
            report.observedAt,
            report.validUntil,
            report.side,
            report.inputAmount,
            report.minimumOutput,
            report.executor
        ));
    }
}
