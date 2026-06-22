// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

/// @title MockKRW
/// @notice 모의 원화 결제 토큰 (mKRW). 학습/모의 거래 전용이며 실제 화폐가 아니다.
/// @dev 기획서 §9.2 — faucet 으로 테스트용 잔고를 지급받는다.
contract MockKRW is ERC20, Ownable {
    /// @notice faucet 1회 지급량 (18 decimals 기준)
    uint256 public faucetAmount = 1_000_000 ether;

    event FaucetClaimed(address indexed account, uint256 amount);

    constructor() ERC20("Mock Korean Won", "mKRW") Ownable(msg.sender) {}

    /// @notice 호출자에게 모의 원화를 지급한다 (MVP 단계에서는 무제한 허용).
    function faucet() external {
        _mint(msg.sender, faucetAmount);
        emit FaucetClaimed(msg.sender, faucetAmount);
    }

    /// @notice faucet 지급량 조정 (관리자).
    function setFaucetAmount(uint256 amount) external onlyOwner {
        faucetAmount = amount;
    }
}
