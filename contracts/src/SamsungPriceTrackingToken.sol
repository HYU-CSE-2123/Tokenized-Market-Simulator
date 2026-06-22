// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

/// @title SamsungPriceTrackingToken (mSEC)
/// @notice 삼성전자 기준 가격을 추종하는 모의 토큰. 실제 주식/배당권/의결권/상환권을 나타내지 않는다.
/// @dev 기획서 §9.3 — ExchangeVault(minter)만 mint/burn 을 수행한다.
contract SamsungPriceTrackingToken is ERC20, Ownable {
    /// @notice mint/burn 권한을 가진 주소 (ExchangeVault)
    address public minter;

    event MinterUpdated(address indexed previousMinter, address indexed newMinter);

    error NotMinter();

    modifier onlyMinter() {
        if (msg.sender != minter) revert NotMinter();
        _;
    }

    constructor()
        ERC20("Samsung Electronics Price-Tracking Token", "mSEC")
        Ownable(msg.sender)
    {}

    /// @notice minter(ExchangeVault) 설정.
    function setMinter(address newMinter) external onlyOwner {
        emit MinterUpdated(minter, newMinter);
        minter = newMinter;
    }

    /// @notice 매수 정산 시 Vault 가 사용자에게 토큰을 발행한다.
    function mint(address to, uint256 amount) external onlyMinter {
        _mint(to, amount);
    }

    /// @notice 매도 정산 시 Vault 가 사용자 토큰을 소각한다.
    function burn(address from, uint256 amount) external onlyMinter {
        _burn(from, amount);
    }
}
