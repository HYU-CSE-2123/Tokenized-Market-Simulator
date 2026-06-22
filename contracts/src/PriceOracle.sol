// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

/// @title PriceOracle
/// @notice 삼성전자 기준 가격(모의 원화 환산)을 온체인에 저장한다.
/// @dev 기획서 §9.4 — 가격은 1e8 정밀도(priceE8)로 저장. 백엔드/관리자가 updatePrice 로 갱신.
contract PriceOracle is Ownable {
    /// @notice 가격 정밀도 (1 mKRW = 1e8).
    uint256 public constant PRICE_DECIMALS = 8;

    /// @notice 현재 기준 가격 (1e8 단위). 예: 75,000원 = 75000 * 1e8.
    uint256 public priceE8;

    /// @notice 마지막 갱신 시각(블록 timestamp).
    uint256 public updatedAt;

    event PriceUpdated(uint256 priceE8, uint256 updatedAt);

    error InvalidPrice();

    constructor(uint256 initialPriceE8) Ownable(msg.sender) {
        _setPrice(initialPriceE8);
    }

    /// @notice 기준 가격 갱신 (관리자/백엔드).
    function updatePrice(uint256 newPriceE8) external onlyOwner {
        _setPrice(newPriceE8);
    }

    /// @notice 현재 가격과 갱신 시각을 반환.
    function getPrice() external view returns (uint256 price, uint256 timestamp) {
        return (priceE8, updatedAt);
    }

    function _setPrice(uint256 newPriceE8) internal {
        if (newPriceE8 == 0) revert InvalidPrice();
        priceE8 = newPriceE8;
        updatedAt = block.timestamp;
        emit PriceUpdated(newPriceE8, block.timestamp);
    }
}
