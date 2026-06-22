// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console2} from "forge-std/Script.sol";
import {MockKRW} from "../src/MockKRW.sol";
import {SamsungPriceTrackingToken} from "../src/SamsungPriceTrackingToken.sol";
import {PriceOracle} from "../src/PriceOracle.sol";
import {ExchangeVault} from "../src/ExchangeVault.sol";

/// @notice 4개 컨트랙트 배포 + ExchangeVault 를 mSEC minter 로 등록한다. (기획서 §0.5, §16 Phase 1)
/// @dev 로컬: `forge script script/Deploy.s.sol --rpc-url local --broadcast --private-key <KEY>`
contract Deploy is Script {
    // 초기 가격 75,000원 (1e8 정밀도)
    uint256 internal constant INITIAL_PRICE_E8 = 75_000 * 1e8;

    function run() external {
        vm.startBroadcast();

        MockKRW krw = new MockKRW();
        SamsungPriceTrackingToken token = new SamsungPriceTrackingToken();
        PriceOracle oracle = new PriceOracle(INITIAL_PRICE_E8);
        ExchangeVault vault = new ExchangeVault(address(krw), address(token), address(oracle));

        // Vault 만 mSEC mint/burn 가능하도록 minter 등록
        token.setMinter(address(vault));

        vm.stopBroadcast();

        console2.log("MockKRW                   :", address(krw));
        console2.log("SamsungPriceTrackingToken :", address(token));
        console2.log("PriceOracle               :", address(oracle));
        console2.log("ExchangeVault             :", address(vault));
    }
}
