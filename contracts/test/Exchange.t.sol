// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {MockKRW} from "../src/MockKRW.sol";
import {SamsungPriceTrackingToken} from "../src/SamsungPriceTrackingToken.sol";
import {PriceOracle} from "../src/PriceOracle.sol";
import {PriceReportTypes} from "../src/PriceReportTypes.sol";
import {ExchangeVault} from "../src/ExchangeVault.sol";

contract ExchangeTest is Test {
    uint256 internal constant SIGNER_KEY = 0xA11CE;
    uint256 internal constant WRONG_SIGNER_KEY = 0xBAD;
    uint256 internal constant PRICE_75K = 75_000 * 1e8;
    uint256 internal constant PRICE_80K = 80_000 * 1e8;
    bytes32 internal constant SYMBOL_HASH = keccak256("mSEC");

    MockKRW internal krw;
    SamsungPriceTrackingToken internal token;
    PriceOracle internal oracle;
    ExchangeVault internal vault;
    address internal user = address(0xBEEF);
    uint256 internal quoteSequence;

    event FaucetClaimed(address indexed account, uint256 amount);
    event MinterUpdated(address indexed previousMinter, address indexed newMinter);
    event FeeBpsUpdated(uint256 previousFeeBps, uint256 newFeeBps);
    event Bought(address indexed user, uint256 krwIn, uint256 tokenOut, uint256 fee, uint256 priceE8);
    event Sold(address indexed user, uint256 tokenIn, uint256 krwOut, uint256 fee, uint256 priceE8);

    function setUp() public {
        krw = new MockKRW();
        token = new SamsungPriceTrackingToken();
        oracle = new PriceOracle(PRICE_75K, vm.addr(SIGNER_KEY), SYMBOL_HASH);
        vault = new ExchangeVault(address(krw), address(token), address(oracle));
        token.setMinter(address(vault));
        oracle.setAuthorizedConsumer(address(vault));

        krw.faucet();
        krw.transfer(address(vault), 500_000 ether);
        vm.prank(user);
        krw.faucet();
        vm.prank(user);
        krw.approve(address(vault), type(uint256).max);
    }

    function testBuyUsesSignedReportPriceAndMinimumOutput() public {
        uint256 input = 750_000 ether;
        (uint256 expected,) = vault.quoteBuyAtPrice(input, PRICE_80K);
        PriceReportTypes.PriceReport memory report = _report(0, input, PRICE_80K, expected, user);
        bytes memory signature = _sign(report, SIGNER_KEY);

        vm.prank(user);
        uint256 actual = vault.buy(report, signature);

        assertEq(actual, expected);
        assertEq(token.balanceOf(user), expected);
        assertEq(oracle.priceE8(), PRICE_80K);
        assertTrue(oracle.usedQuoteIds(report.quoteId));
    }

    function testSellUsesSignedReportPrice() public {
        uint256 tokenAmount = _buyAt(750_000 ether, PRICE_75K);
        (uint256 expected,) = vault.quoteSellAtPrice(tokenAmount, PRICE_80K);
        PriceReportTypes.PriceReport memory report = _report(1, tokenAmount, PRICE_80K, expected, user);
        bytes memory signature = _sign(report, SIGNER_KEY);

        vm.prank(user);
        uint256 actual = vault.sell(report, signature);

        assertEq(actual, expected);
        assertEq(token.balanceOf(user), 0);
        assertEq(oracle.priceE8(), PRICE_80K);
    }

    function testPriceRiseRoundTripProducesProfit() public {
        uint256 beforeBalance = krw.balanceOf(user);
        uint256 tokenAmount = _buyAt(750_000 ether, PRICE_75K);
        (uint256 minimum,) = vault.quoteSellAtPrice(tokenAmount, PRICE_80K);
        PriceReportTypes.PriceReport memory report = _report(1, tokenAmount, PRICE_80K, minimum, user);
        bytes memory signature = _sign(report, SIGNER_KEY);
        vm.prank(user);
        vault.sell(report, signature);
        assertGt(krw.balanceOf(user), beforeBalance);
    }

    function testReplayIsRejected() public {
        uint256 input = 10_000 ether;
        (uint256 minimum,) = vault.quoteBuyAtPrice(input, PRICE_75K);
        PriceReportTypes.PriceReport memory report = _report(0, input, PRICE_75K, minimum, user);
        bytes memory signature = _sign(report, SIGNER_KEY);
        vm.prank(user);
        vault.buy(report, signature);
        vm.expectRevert(PriceOracle.QuoteAlreadyUsed.selector);
        vm.prank(user);
        vault.buy(report, signature);
    }

    function testWrongSideIsRejectedWithoutConsumingQuote() public {
        PriceReportTypes.PriceReport memory report = _report(1, 10_000 ether, PRICE_75K, 1, user);
        bytes memory signature = _sign(report, SIGNER_KEY);
        vm.expectRevert(ExchangeVault.InvalidReportSide.selector);
        vm.prank(user);
        vault.buy(report, signature);
        assertFalse(oracle.usedQuoteIds(report.quoteId));
    }

    function testWrongExecutorIsRejectedWithoutConsumingQuote() public {
        PriceReportTypes.PriceReport memory report = _report(0, 10_000 ether, PRICE_75K, 1, address(0xCAFE));
        bytes memory signature = _sign(report, SIGNER_KEY);
        vm.expectRevert(ExchangeVault.InvalidExecutor.selector);
        vm.prank(user);
        vault.buy(report, signature);
        assertFalse(oracle.usedQuoteIds(report.quoteId));
    }

    function testZeroInputIsRejected() public {
        PriceReportTypes.PriceReport memory report = _report(0, 0, PRICE_75K, 1, user);
        bytes memory signature = _sign(report, SIGNER_KEY);
        vm.expectRevert(ExchangeVault.ZeroAmount.selector);
        vm.prank(user);
        vault.buy(report, signature);
    }

    function testZeroMinimumOutputIsRejected() public {
        PriceReportTypes.PriceReport memory report = _report(0, 10_000 ether, PRICE_75K, 0, user);
        bytes memory signature = _sign(report, SIGNER_KEY);
        vm.expectRevert(ExchangeVault.InvalidMinimumOutput.selector);
        vm.prank(user);
        vault.buy(report, signature);
    }

    function testMinimumOutputFailureRollsBackOracleConsumption() public {
        PriceReportTypes.PriceReport memory report = _report(0, 10_000 ether, PRICE_80K, type(uint256).max, user);
        bytes memory signature = _sign(report, SIGNER_KEY);
        (uint256 actualOutput,) = vault.quoteBuyAtPrice(report.inputAmount, report.priceE8);
        vm.expectRevert(
            abi.encodeWithSelector(ExchangeVault.MinimumOutputNotMet.selector, actualOutput, report.minimumOutput)
        );
        vm.prank(user);
        vault.buy(report, signature);
        assertEq(oracle.priceE8(), PRICE_75K);
        assertFalse(oracle.usedQuoteIds(report.quoteId));
    }

    function testFeeChangeCanInvalidateSignedMinimumAndRollsBack() public {
        uint256 input = 100_000 ether;
        (uint256 minimum,) = vault.quoteBuyAtPrice(input, PRICE_80K);
        PriceReportTypes.PriceReport memory report = _report(0, input, PRICE_80K, minimum, user);
        bytes memory signature = _sign(report, SIGNER_KEY);
        vault.setFeeBps(100);
        (uint256 actualOutput,) = vault.quoteBuyAtPrice(input, PRICE_80K);
        vm.expectRevert(abi.encodeWithSelector(ExchangeVault.MinimumOutputNotMet.selector, actualOutput, minimum));
        vm.prank(user);
        vault.buy(report, signature);
        assertEq(oracle.priceE8(), PRICE_75K);
        assertFalse(oracle.usedQuoteIds(report.quoteId));
    }

    function testInsufficientLiquidityRollsBackBurnOracleAndQuote() public {
        uint256 tokenAmount = _buyAt(750_000 ether, PRICE_75K);
        uint256 vaultBalance = krw.balanceOf(address(vault));
        vm.prank(address(vault));
        krw.transfer(address(0xDEAD), vaultBalance);
        (uint256 minimum,) = vault.quoteSellAtPrice(tokenAmount, PRICE_80K);
        PriceReportTypes.PriceReport memory report = _report(1, tokenAmount, PRICE_80K, minimum, user);
        bytes memory signature = _sign(report, SIGNER_KEY);

        uint256 tokenBefore = token.balanceOf(user);
        vm.expectRevert(ExchangeVault.InsufficientLiquidity.selector);
        vm.prank(user);
        vault.sell(report, signature);
        assertEq(token.balanceOf(user), tokenBefore);
        assertEq(oracle.priceE8(), PRICE_75K);
        assertFalse(oracle.usedQuoteIds(report.quoteId));
    }

    function testInvalidSignatureIsRejected() public {
        PriceReportTypes.PriceReport memory report = _report(0, 10_000 ether, PRICE_75K, 1, user);
        bytes memory signature = _sign(report, WRONG_SIGNER_KEY);
        vm.expectRevert(PriceOracle.InvalidPriceSigner.selector);
        vm.prank(user);
        vault.buy(report, signature);
    }

    function testExpiredReportIsRejected() public {
        PriceReportTypes.PriceReport memory report = _report(0, 10_000 ether, PRICE_75K, 1, user);
        bytes memory signature = _sign(report, SIGNER_KEY);
        vm.warp(report.validUntil + 1);
        vm.expectRevert(PriceOracle.ReportExpired.selector);
        vm.prank(user);
        vault.buy(report, signature);
    }

    function testQuoteAtPriceRejectsZeroPrice() public {
        vm.expectRevert(ExchangeVault.InvalidPrice.selector);
        vault.quoteBuyAtPrice(1 ether, 0);
        vm.expectRevert(ExchangeVault.InvalidPrice.selector);
        vault.quoteSellAtPrice(1 ether, 0);
    }

    function testOnlyOwnerCanSetFeeAndCapIsEnforced() public {
        vm.expectRevert();
        vm.prank(user);
        vault.setFeeBps(20);
        vm.expectRevert(ExchangeVault.FeeTooHigh.selector);
        vault.setFeeBps(1_001);
        vault.setFeeBps(1_000);
        assertEq(vault.feeBps(), 1_000);
    }

    function testFaucetMintsConfiguredAmountAndEmitsEvent() public {
        address recipient = address(0xCAFE);
        vm.expectEmit(true, false, false, true, address(krw));
        emit FaucetClaimed(recipient, krw.faucetAmount());
        vm.prank(recipient);
        krw.faucet();
        assertEq(krw.balanceOf(recipient), krw.faucetAmount());
    }

    function testMinterConfigurationAccessControlAndEvent() public {
        SamsungPriceTrackingToken fresh = new SamsungPriceTrackingToken();
        vm.expectRevert(SamsungPriceTrackingToken.InvalidMinter.selector);
        fresh.setMinter(address(0));

        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, user));
        vm.prank(user);
        fresh.setMinter(user);

        vm.expectEmit(true, true, false, true, address(fresh));
        emit MinterUpdated(address(0), address(vault));
        fresh.setMinter(address(vault));
    }

    function testOnlyMinterCanMintAndBurn() public {
        vm.startPrank(user);
        vm.expectRevert(SamsungPriceTrackingToken.NotMinter.selector);
        token.mint(user, 1 ether);
        vm.expectRevert(SamsungPriceTrackingToken.NotMinter.selector);
        token.burn(user, 1 ether);
        vm.stopPrank();
    }

    function testVaultConstructorRejectsZeroAddresses() public {
        vm.expectRevert(ExchangeVault.InvalidAddress.selector);
        new ExchangeVault(address(0), address(token), address(oracle));
        vm.expectRevert(ExchangeVault.InvalidAddress.selector);
        new ExchangeVault(address(krw), address(0), address(oracle));
        vm.expectRevert(ExchangeVault.InvalidAddress.selector);
        new ExchangeVault(address(krw), address(token), address(0));
    }

    function testBuyEmitsEvent() public {
        uint256 input = 100_000 ether;
        (uint256 expected, uint256 fee) = vault.quoteBuyAtPrice(input, PRICE_75K);
        PriceReportTypes.PriceReport memory report = _report(0, input, PRICE_75K, expected, user);
        bytes memory signature = _sign(report, SIGNER_KEY);

        vm.expectEmit(true, false, false, true, address(vault));
        emit Bought(user, input, expected, fee, PRICE_75K);
        vm.prank(user);
        vault.buy(report, signature);
    }

    function testSellEmitsEvent() public {
        uint256 tokenAmount = _buyAt(100_000 ether, PRICE_75K);
        (uint256 expected, uint256 fee) = vault.quoteSellAtPrice(tokenAmount, PRICE_80K);
        PriceReportTypes.PriceReport memory report = _report(1, tokenAmount, PRICE_80K, expected, user);
        bytes memory signature = _sign(report, SIGNER_KEY);

        vm.expectEmit(true, false, false, true, address(vault));
        emit Sold(user, tokenAmount, expected, fee, PRICE_80K);
        vm.prank(user);
        vault.sell(report, signature);
    }

    function testSetFeeEmitsEvent() public {
        vm.expectEmit(false, false, false, true, address(vault));
        emit FeeBpsUpdated(10, 1_000);
        vault.setFeeBps(1_000);
    }

    function testFuzzQuoteBuyAtSignedPrice(uint96 rawAmount, uint64 rawPrice) public view {
        uint256 amount = bound(uint256(rawAmount), 1, 1_000_000 ether);
        uint256 price = bound(uint256(rawPrice), 1, 1_000_000 * 1e8);
        (uint256 tokenOut, uint256 fee) = vault.quoteBuyAtPrice(amount, price);
        assertEq(fee, (amount * vault.feeBps()) / 10_000);
        assertEq(tokenOut, ((amount - fee) * 1e8) / price);
    }

    function testFuzzQuoteSellAtSignedPrice(uint96 rawAmount, uint64 rawPrice, uint16 rawFeeBps) public {
        uint256 amount = bound(uint256(rawAmount), 1, 1_000 ether);
        uint256 price = bound(uint256(rawPrice), 1, 1_000_000 * 1e8);
        uint256 configuredFeeBps = bound(uint256(rawFeeBps), 0, 1_000);
        vault.setFeeBps(configuredFeeBps);
        (uint256 krwOut, uint256 fee) = vault.quoteSellAtPrice(amount, price);
        uint256 gross = (amount * price) / 1e8;
        assertEq(fee, (gross * configuredFeeBps) / 10_000);
        assertEq(krwOut, gross - fee);
    }

    function _buyAt(uint256 input, uint256 price) internal returns (uint256 tokenAmount) {
        (uint256 minimum,) = vault.quoteBuyAtPrice(input, price);
        PriceReportTypes.PriceReport memory report = _report(0, input, price, minimum, user);
        bytes memory signature = _sign(report, SIGNER_KEY);
        vm.prank(user);
        return vault.buy(report, signature);
    }

    function _report(uint8 side, uint256 input, uint256 price, uint256 minimum, address executor)
        internal
        returns (PriceReportTypes.PriceReport memory)
    {
        quoteSequence++;
        return PriceReportTypes.PriceReport({
            quoteId: keccak256(abi.encode("test-quote", quoteSequence)),
            symbolHash: SYMBOL_HASH,
            priceE8: price,
            observedAt: block.timestamp,
            validUntil: block.timestamp + 30 seconds,
            side: side,
            inputAmount: input,
            minimumOutput: minimum,
            executor: executor
        });
    }

    function _sign(PriceReportTypes.PriceReport memory report, uint256 key) internal view returns (bytes memory) {
        bytes32 digest = oracle.priceReportDigest(report);
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(key, digest);
        return abi.encodePacked(r, s, v);
    }
}
