// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";

import {PriceOracle} from "../src/PriceOracle.sol";
import {PriceReportTypes} from "../src/PriceReportTypes.sol";

contract PriceOracleSignedReportTest is Test {
    uint256 private constant SIGNER_KEY = 0xA11CE;
    uint256 private constant OTHER_SIGNER_KEY = 0xB0B;
    uint256 private constant INITIAL_PRICE = 75_000 * 1e8;
    uint256 private constant REPORT_PRICE = 75_300 * 1e8;
    bytes32 private constant SYMBOL_HASH = keccak256("mSEC");

    PriceOracle private oracle;

    function setUp() public {
        vm.warp(1_790_393_400);
        oracle = new PriceOracle(INITIAL_PRICE, vm.addr(SIGNER_KEY), SYMBOL_HASH);
        oracle.setAuthorizedConsumer(address(this));
    }

    function test_ConsumesValidReportAndRecordsObservedTime() public {
        PriceReportTypes.PriceReport memory report = _report(keccak256("valid"), block.timestamp - 1);
        bytes memory signature = _sign(oracle, report, SIGNER_KEY);

        uint256 approvedPrice = oracle.consumePriceReport(report, signature);

        assertEq(approvedPrice, REPORT_PRICE);
        assertEq(oracle.priceE8(), REPORT_PRICE);
        assertEq(oracle.priceObservedAt(), report.observedAt);
        assertEq(oracle.updatedAt(), block.timestamp);
        assertTrue(oracle.usedQuoteIds(report.quoteId));
    }

    function test_RevertsForUnauthorizedConsumer() public {
        PriceReportTypes.PriceReport memory report = _report(keccak256("consumer"), block.timestamp);
        bytes memory signature = _sign(oracle, report, SIGNER_KEY);

        vm.prank(address(0xBEEF));
        vm.expectRevert(PriceOracle.UnauthorizedConsumer.selector);
        oracle.consumePriceReport(report, signature);
    }

    function test_RevertsForWrongSignerAndWrongDomain() public {
        PriceReportTypes.PriceReport memory report = _report(keccak256("signer"), block.timestamp);
        bytes memory wrongSignerSignature = _sign(oracle, report, OTHER_SIGNER_KEY);
        vm.expectRevert(PriceOracle.InvalidPriceSigner.selector);
        oracle.consumePriceReport(report, wrongSignerSignature);

        PriceOracle otherOracle = new PriceOracle(INITIAL_PRICE, vm.addr(SIGNER_KEY), SYMBOL_HASH);
        bytes memory wrongDomainSignature = _sign(otherOracle, report, SIGNER_KEY);
        vm.expectRevert(PriceOracle.InvalidPriceSigner.selector);
        oracle.consumePriceReport(report, wrongDomainSignature);
    }

    function test_RevertsForInvalidSymbolPriceAndQuoteId() public {
        PriceReportTypes.PriceReport memory report = _report(keccak256("invalid-fields"), block.timestamp);

        report.symbolHash = keccak256("OTHER");
        bytes memory signature = _sign(oracle, report, SIGNER_KEY);
        vm.expectRevert(PriceOracle.InvalidSymbol.selector);
        oracle.consumePriceReport(report, signature);

        report = _report(keccak256("zero-price"), block.timestamp);
        report.priceE8 = 0;
        signature = _sign(oracle, report, SIGNER_KEY);
        vm.expectRevert(PriceOracle.InvalidPrice.selector);
        oracle.consumePriceReport(report, signature);

        report = _report(bytes32(0), block.timestamp);
        signature = _sign(oracle, report, SIGNER_KEY);
        vm.expectRevert(PriceOracle.InvalidQuoteId.selector);
        oracle.consumePriceReport(report, signature);
    }

    function test_RevertsForInvalidWindowFutureObservationAndExpiry() public {
        PriceReportTypes.PriceReport memory report = _report(keccak256("window"), block.timestamp);
        report.validUntil += 1;
        bytes memory signature = _sign(oracle, report, SIGNER_KEY);
        vm.expectRevert(PriceOracle.InvalidValidityWindow.selector);
        oracle.consumePriceReport(report, signature);

        report = _report(keccak256("future"), block.timestamp + 3);
        signature = _sign(oracle, report, SIGNER_KEY);
        vm.expectRevert(PriceOracle.ObservationFromFuture.selector);
        oracle.consumePriceReport(report, signature);

        report = _report(keccak256("expired"), block.timestamp - 31);
        signature = _sign(oracle, report, SIGNER_KEY);
        vm.expectRevert(PriceOracle.ReportExpired.selector);
        oracle.consumePriceReport(report, signature);
    }

    function test_AcceptsExactFutureSkewAndExpiryBoundaries() public {
        PriceReportTypes.PriceReport memory futureBoundary =
            _report(keccak256("future-boundary"), block.timestamp + 2);
        oracle.consumePriceReport(futureBoundary, _sign(oracle, futureBoundary, SIGNER_KEY));

        PriceReportTypes.PriceReport memory expiryBoundary =
            _report(keccak256("expiry-boundary"), block.timestamp - 30);
        oracle.consumePriceReport(expiryBoundary, _sign(oracle, expiryBoundary, SIGNER_KEY));

        assertTrue(oracle.usedQuoteIds(futureBoundary.quoteId));
        assertTrue(oracle.usedQuoteIds(expiryBoundary.quoteId));
    }

    function test_RevertsForMalformedSignatureLength() public {
        PriceReportTypes.PriceReport memory report = _report(keccak256("malformed"), block.timestamp);

        vm.expectRevert(abi.encodeWithSelector(ECDSA.ECDSAInvalidSignatureLength.selector, 2));
        oracle.consumePriceReport(report, hex"1234");
    }

    function test_RevertsWhenQuoteIdIsReused() public {
        PriceReportTypes.PriceReport memory report = _report(keccak256("replay"), block.timestamp);
        bytes memory signature = _sign(oracle, report, SIGNER_KEY);
        oracle.consumePriceReport(report, signature);

        vm.expectRevert(PriceOracle.QuoteAlreadyUsed.selector);
        oracle.consumePriceReport(report, signature);
    }

    function test_OwnerRotatesSignerAndSetsConsumer() public {
        address newSigner = vm.addr(OTHER_SIGNER_KEY);
        oracle.setPriceSigner(newSigner);
        oracle.setAuthorizedConsumer(address(0xCAFE));

        assertEq(oracle.priceSigner(), newSigner);
        assertEq(oracle.authorizedConsumer(), address(0xCAFE));

        vm.expectRevert(PriceOracle.InvalidAddress.selector);
        oracle.setPriceSigner(address(0));
        vm.expectRevert(PriceOracle.InvalidAddress.selector);
        oracle.setAuthorizedConsumer(address(0));
    }

    function test_NonOwnerCannotChangeSignerOrConsumer() public {
        vm.startPrank(address(0xBEEF));
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, address(0xBEEF)));
        oracle.setPriceSigner(vm.addr(OTHER_SIGNER_KEY));
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, address(0xBEEF)));
        oracle.setAuthorizedConsumer(address(0xCAFE));
        vm.stopPrank();
    }

    function test_LegacyOwnerUpdateUsesBlockTimeAsObservation() public {
        vm.warp(block.timestamp + 10);
        oracle.updatePrice(80_000 * 1e8);

        assertEq(oracle.priceObservedAt(), block.timestamp);
        assertEq(oracle.updatedAt(), block.timestamp);
    }

    function _report(bytes32 quoteId, uint256 observedAt)
        private
        view
        returns (PriceReportTypes.PriceReport memory)
    {
        return PriceReportTypes.PriceReport({
            quoteId: quoteId,
            symbolHash: SYMBOL_HASH,
            priceE8: REPORT_PRICE,
            observedAt: observedAt,
            validUntil: observedAt + oracle.REPORT_TTL(),
            side: PriceReportTypes.SIDE_BUY,
            inputAmount: 100_000 ether,
            minimumOutput: 1 ether,
            executor: address(0x2222)
        });
    }

    function _sign(
        PriceOracle targetOracle,
        PriceReportTypes.PriceReport memory report,
        uint256 signerKey
    ) private view returns (bytes memory) {
        bytes32 digest = targetOracle.priceReportDigest(report);
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(signerKey, digest);
        return abi.encodePacked(r, s, v);
    }
}
