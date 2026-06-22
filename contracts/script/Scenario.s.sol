// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console2} from "forge-std/Script.sol";
import {MockKRW} from "../src/MockKRW.sol";
import {SamsungPriceTrackingToken} from "../src/SamsungPriceTrackingToken.sol";
import {PriceOracle} from "../src/PriceOracle.sol";
import {ExchangeVault} from "../src/ExchangeVault.sol";

/**
 * Phase 0.5 최소 동작 검증 — 실제 로컬체인(Anvil)에서 전체 흐름을 실행한다.
 * 기획서 §0.5: 매수 → 가격 변경(75k→80k) → 매도 → mKRW 잔고 증가 확인.
 *
 * 실행:
 *   anvil &
 *   forge script script/Scenario.s.sol --rpc-url local --broadcast
 *
 * OWNER_KEY / USER_KEY 는 Anvil 의 잘 알려진 기본 계정(테스트 전용). env 로 override 가능.
 */
contract Scenario is Script {
    // Anvil 기본 계정 #0, #1 (공개된 테스트 키 — 실거래 사용 금지)
    uint256 internal constant DEFAULT_OWNER_KEY =
        0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80;
    uint256 internal constant DEFAULT_USER_KEY =
        0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d;

    uint256 internal constant PRICE_75K = 75_000 * 1e8;
    uint256 internal constant PRICE_80K = 80_000 * 1e8;

    function run() external {
        uint256 ownerKey = vm.envOr("OWNER_KEY", DEFAULT_OWNER_KEY);
        uint256 userKey = vm.envOr("USER_KEY", DEFAULT_USER_KEY);
        address user = vm.addr(userKey);

        // 1~6) 배포 + minter 등록 + Vault 유동성 시드 (owner)
        vm.startBroadcast(ownerKey);
        MockKRW krw = new MockKRW();
        SamsungPriceTrackingToken token = new SamsungPriceTrackingToken();
        PriceOracle oracle = new PriceOracle(PRICE_75K);
        ExchangeVault vault = new ExchangeVault(address(krw), address(token), address(oracle));
        token.setMinter(address(vault));
        krw.faucet(); // owner 1,000,000 mKRW
        krw.transfer(address(vault), 1_000_000 ether); // 매도 정산용 유동성
        vm.stopBroadcast();

        console2.log("=== Deployed ===");
        console2.log("MockKRW       :", address(krw));
        console2.log("mSEC          :", address(token));
        console2.log("PriceOracle   :", address(oracle));
        console2.log("ExchangeVault :", address(vault));
        console2.log("Initial price : 75,000 (e8)");

        // 7~9) 사용자 자금 확보 + 750,000 mKRW 매수
        vm.startBroadcast(userKey);
        krw.faucet(); // user 1,000,000 mKRW
        uint256 krwBeforeBuy = krw.balanceOf(user);
        krw.approve(address(vault), 750_000 ether);
        uint256 tokenOut = vault.buy(750_000 ether);
        vm.stopBroadcast();

        console2.log("\n=== After BUY (750,000 mKRW @75k) ===");
        console2.log("user mSEC      :", token.balanceOf(user));
        console2.log("user mKRW      :", krw.balanceOf(user));
        console2.log("tokenOut(~10e18):", tokenOut);

        // 10) mSEC 약 10개 증가 확인
        require(token.balanceOf(user) == tokenOut, "mSEC balance mismatch");
        require(tokenOut > 9.9 ether && tokenOut < 10.1 ether, "expected ~10 mSEC");

        // 11) 오라클 가격 80,000 으로 변경 (owner)
        vm.startBroadcast(ownerKey);
        oracle.updatePrice(PRICE_80K);
        vm.stopBroadcast();
        console2.log("\n=== Oracle price -> 80,000 (e8) ===");

        // 12) 사용자가 보유 mSEC 전량 매도
        vm.startBroadcast(userKey);
        uint256 krwOut = vault.sell(tokenOut);
        vm.stopBroadcast();

        uint256 krwAfter = krw.balanceOf(user);
        console2.log("\n=== After SELL (price up) ===");
        console2.log("user mSEC      :", token.balanceOf(user));
        console2.log("user mKRW      :", krwAfter);
        console2.log("krwOut(~800k)  :", krwOut);

        // 13) mKRW 잔고가 매수 직전 대비 증가(가격 상승분 반영) + mSEC 전량 소진
        require(token.balanceOf(user) == 0, "mSEC should be 0 after full sell");
        require(krwOut > 750_000 ether, "sell proceeds should exceed buy cost after price up");
        require(krwAfter > krwBeforeBuy, "final mKRW should exceed pre-buy balance");

        // 14) 성공
        console2.log("\n[OK] Phase 0.5 price-tracking buy/sell verified on-chain.");
    }
}
