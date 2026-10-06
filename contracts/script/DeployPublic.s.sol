// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;
import {Script} from "forge-std/Script.sol";
import {MockKRW} from "../src/MockKRW.sol";
import {SamsungPriceTrackingToken} from "../src/SamsungPriceTrackingToken.sol";
import {PriceOracle} from "../src/PriceOracle.sol";
import {ExchangeVault} from "../src/ExchangeVault.sol";

/// @notice Explicit first-time private demo initialization; never run on Sepolia.
/// @dev Dedicated keys via environment; no default Anvil accounts or private-key argv.
contract DeployPublic is Script {
    function run() external {
        require(block.chainid == 31337, "Private demo chain only");
        uint256 operatorKey = vm.envUint("OPERATOR_PRIVATE_KEY");
        uint256 signerKey = vm.envUint("PRICE_SIGNER_PRIVATE_KEY");
        require(operatorKey != signerKey, "Separate signer required");
        address operator = vm.addr(operatorKey);
        // RPC funding is allowed only on this isolated private demo chain.
        vm.rpc("anvil_setBalance", string.concat('["', vm.toString(operator), '","0x3635c9adc5dea00000"]'));
        vm.startBroadcast(operatorKey);
        MockKRW krw = new MockKRW();
        SamsungPriceTrackingToken token = new SamsungPriceTrackingToken();
        PriceOracle oracle = new PriceOracle(75_000 * 1e8, vm.addr(signerKey), keccak256("mSEC"));
        ExchangeVault vault = new ExchangeVault(address(krw), address(token), address(oracle));
        token.setMinter(address(vault));
        oracle.setAuthorizedConsumer(address(vault));
        // Small, explicit demo inventory; DB faucet does not refill this reserve.
        for (uint256 i; i < 10; i++) krw.faucet();
        krw.transfer(address(vault), 5_000_000 ether);
        krw.approve(address(vault), type(uint256).max);
        vm.stopBroadcast();
        vm.writeFile("/release/chain.properties", string.concat(
            "MOCK_KRW_ADDRESS=", vm.toString(address(krw)), "\nMSEC_ADDRESS=", vm.toString(address(token)),
            "\nPRICE_ORACLE_ADDRESS=", vm.toString(address(oracle)), "\nEXCHANGE_VAULT_ADDRESS=", vm.toString(address(vault)), "\n"));
    }
}
