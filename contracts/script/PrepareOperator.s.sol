// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console2} from "forge-std/Script.sol";
import {MockKRW} from "../src/MockKRW.sol";
import {ExchangeVault} from "../src/ExchangeVault.sol";

/// @notice 백엔드 운영자 지갑에 테스트 mKRW를 지급하고 Vault 사용 권한을 설정한다.
/// @dev MOCK_KRW_ADDRESS, EXCHANGE_VAULT_ADDRESS와 운영자 PRIVATE_KEY를 환경 변수로 받는다.
contract PrepareOperator is Script {
    function run() external {
        address mockKrwAddress = vm.envAddress("MOCK_KRW_ADDRESS");
        address vaultAddress = vm.envAddress("EXCHANGE_VAULT_ADDRESS");
        uint256 privateKey = vm.envUint("OPERATOR_PRIVATE_KEY");

        MockKRW krw = MockKRW(mockKrwAddress);

        vm.startBroadcast(privateKey);
        krw.faucet();
        krw.approve(vaultAddress, type(uint256).max);
        vm.stopBroadcast();

        address operator = vm.addr(privateKey);
        console2.log("Operator       :", operator);
        console2.log("mKRW balance   :", krw.balanceOf(operator));
        console2.log("Vault allowance:", krw.allowance(operator, vaultAddress));
        console2.log("Vault          :", address(ExchangeVault(vaultAddress)));
    }
}
