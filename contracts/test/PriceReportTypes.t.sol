// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {PriceReportTypes} from "../src/PriceReportTypes.sol";

contract PriceReportHarness is EIP712 {
    constructor() EIP712("TokenizedMarketPriceOracle", "1") {}

    function digest(PriceReportTypes.PriceReport memory report) external view returns (bytes32) {
        return _hashTypedDataV4(PriceReportTypes.hash(report));
    }
}

contract PriceReportTypesTest is Test {
    uint256 private constant SIGNER_KEY = 0xA11CE;
    address private constant VERIFYING_CONTRACT = 0x1111111111111111111111111111111111111111;
    bytes32 private constant EXPECTED_DIGEST =
        0x17997e214c5c57f7030b1c2f588f97a3e4353a383135e96714018aafad246b3e;
    address private constant EXPECTED_SIGNER = 0xe05fcC23807536bEe418f142D19fa0d21BB0cfF7;

    function testSharedVectorDigestAndSignerRecovery() public {
        vm.chainId(31_337);
        PriceReportHarness implementation = new PriceReportHarness();
        vm.etch(VERIFYING_CONTRACT, address(implementation).code);
        PriceReportHarness harness = PriceReportHarness(VERIFYING_CONTRACT);

        PriceReportTypes.PriceReport memory report = PriceReportTypes.PriceReport({
            quoteId: keccak256("quote-20260926-0001"),
            symbolHash: keccak256("mSEC"),
            priceE8: 75_300 * 1e8,
            observedAt: 1_790_393_400,
            validUntil: 1_790_393_430,
            side: PriceReportTypes.SIDE_BUY,
            inputAmount: 100_000 ether,
            minimumOutput: 1_320_000_000_000_000_000,
            executor: 0x2222222222222222222222222222222222222222
        });

        bytes32 digest = harness.digest(report);
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(SIGNER_KEY, digest);

        assertEq(digest, EXPECTED_DIGEST);
        assertEq(vm.addr(SIGNER_KEY), EXPECTED_SIGNER);
        assertEq(ECDSA.recover(digest, v, r, s), EXPECTED_SIGNER);
    }
}
