// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {MockKRW} from "../src/MockKRW.sol";
import {SamsungPriceTrackingToken} from "../src/SamsungPriceTrackingToken.sol";
import {PriceOracle} from "../src/PriceOracle.sol";
import {ExchangeVault} from "../src/ExchangeVault.sol";

/// @notice 기획서 §0.5 / §1 검증 시나리오를 그대로 테스트한다.
contract ExchangeTest is Test {
    MockKRW internal krw;
    SamsungPriceTrackingToken internal token;
    PriceOracle internal oracle;
    ExchangeVault internal vault;

    address internal user = address(0xBEEF);

    uint256 internal constant PRICE_75K = 75_000 * 1e8;
    uint256 internal constant PRICE_80K = 80_000 * 1e8;

    function setUp() public {
        krw = new MockKRW();
        token = new SamsungPriceTrackingToken();
        oracle = new PriceOracle(PRICE_75K);
        vault = new ExchangeVault(address(krw), address(token), address(oracle));
        token.setMinter(address(vault));

        // 테스트 사용자에게 10,000,000 mKRW 지급
        deal(address(krw), user, 10_000_000 ether);
        // 매도 시 Vault 가 지급할 mKRW 유동성 확보
        deal(address(krw), address(vault), 10_000_000 ether);
    }

    /// faucet 동작
    function test_Faucet() public {
        vm.prank(user);
        krw.faucet();
        assertEq(krw.balanceOf(user), 10_000_000 ether + krw.faucetAmount());
    }

    /// 초기 가격 / 가격 업데이트
    function test_OraclePrice() public {
        assertEq(oracle.priceE8(), PRICE_75K);
        oracle.updatePrice(PRICE_80K);
        assertEq(oracle.priceE8(), PRICE_80K);
    }

    /// approve 후 buy → mSEC 증가, fee=0 가정 단순 수량 검증
    function test_BuyMintsTokens() public {
        uint256 feeBps = vault.feeBps();
        uint256 krwIn = 750_000 ether;

        vm.startPrank(user);
        krw.approve(address(vault), krwIn);
        uint256 tokenOut = vault.buy(krwIn);
        vm.stopPrank();

        uint256 net = krwIn - (krwIn * feeBps) / 10_000;
        uint256 expected = (net * 1e8) / PRICE_75K; // ~10 mSEC
        assertEq(tokenOut, expected);
        assertEq(token.balanceOf(user), expected);
        assertApproxEqAbs(tokenOut, 10 ether, 0.01 ether);
    }

    /// 전체 시나리오: 매수 → 가격 상승 → 매도 → mKRW 증가 (기획서 §0.5)
    function test_FullBuyPriceUpSellFlow() public {
        vm.startPrank(user);
        krw.approve(address(vault), 750_000 ether);
        uint256 tokenOut = vault.buy(750_000 ether); // ~10 mSEC @75k
        vm.stopPrank();

        uint256 krwAfterBuy = krw.balanceOf(user);

        // 오라클 가격 75k → 80k 상승
        oracle.updatePrice(PRICE_80K);

        vm.prank(user);
        uint256 krwOut = vault.sell(tokenOut); // ~800,000 mKRW @80k

        assertEq(token.balanceOf(user), 0);
        assertEq(krw.balanceOf(user), krwAfterBuy + krwOut);
        // 가격 상승분만큼 매도 수령액이 매수 지출액보다 크다
        assertGt(krwOut, 750_000 ether);
    }

    /// 가격 변경 후 매수 수량이 달라지는가
    function test_PriceChangeAffectsBuyAmount() public {
        (uint256 outAt75k,) = vault.quoteBuy(750_000 ether);
        oracle.updatePrice(PRICE_80K);
        (uint256 outAt80k,) = vault.quoteBuy(750_000 ether);
        assertGt(outAt75k, outAt80k); // 가격이 오르면 같은 금액으로 더 적게 산다
    }

    /// Vault 유동성 부족 시 sell 실패
    function test_SellRevertsOnInsufficientLiquidity() public {
        // Vault 유동성을 0 으로 만들기 위해 새 Vault 구성
        ExchangeVault dry = new ExchangeVault(address(krw), address(token), address(oracle));
        token.setMinter(address(dry));

        vm.startPrank(user);
        krw.approve(address(dry), 750_000 ether);
        uint256 tokenOut = dry.buy(750_000 ether); // Vault 에 750k 만 적립됨
        vm.stopPrank();

        // 가격을 크게 올려 sell 수령액이 Vault 잔고를 초과하게 만든다
        oracle.updatePrice(1_000_000 * 1e8);

        vm.prank(user);
        vm.expectRevert(ExchangeVault.InsufficientLiquidity.selector);
        dry.sell(tokenOut);
    }
}
