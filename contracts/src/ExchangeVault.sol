// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

import {PriceOracle} from "./PriceOracle.sol";
import {PriceReportTypes} from "./PriceReportTypes.sol";
import {SamsungPriceTrackingToken} from "./SamsungPriceTrackingToken.sol";

/// @title ExchangeVault
/// @notice 서명된 가격 보고서를 기준으로 mKRW와 mSEC의 즉시 매수·매도를 정산한다.
contract ExchangeVault is Ownable, ReentrancyGuard {
    using SafeERC20 for IERC20;

    uint256 private constant PRICE_SCALE = 1e8;
    uint256 private constant BPS_DENOMINATOR = 10_000;

    IERC20 public immutable krw;
    SamsungPriceTrackingToken public immutable token;
    PriceOracle public immutable oracle;
    uint256 public feeBps = 10;

    event Bought(address indexed user, uint256 krwIn, uint256 tokenOut, uint256 fee, uint256 priceE8);
    event Sold(address indexed user, uint256 tokenIn, uint256 krwOut, uint256 fee, uint256 priceE8);
    event FeeBpsUpdated(uint256 previousFeeBps, uint256 newFeeBps);

    error ZeroAmount();
    error InvalidAddress();
    error InsufficientLiquidity();
    error FeeTooHigh();
    error InvalidPrice();
    error InvalidReportSide();
    error InvalidExecutor();
    error InvalidMinimumOutput();
    error MinimumOutputNotMet(uint256 actualOutput, uint256 minimumOutput);

    constructor(address krw_, address token_, address oracle_) Ownable(msg.sender) {
        if (krw_ == address(0) || token_ == address(0) || oracle_ == address(0)) revert InvalidAddress();
        krw = IERC20(krw_);
        token = SamsungPriceTrackingToken(token_);
        oracle = PriceOracle(oracle_);
    }

    /// @notice Oracle에 마지막으로 기록된 가격으로 참고용 매수 견적을 계산한다.
    function quoteBuy(uint256 krwAmount) public view returns (uint256 tokenOut, uint256 fee) {
        return quoteBuyAtPrice(krwAmount, oracle.priceE8());
    }

    /// @notice 지정 가격으로 매수 결과를 계산한다. 서명 보고서의 최소 수령량 산정에 사용한다.
    function quoteBuyAtPrice(uint256 krwAmount, uint256 priceE8) public view returns (uint256 tokenOut, uint256 fee) {
        if (priceE8 == 0) revert InvalidPrice();
        fee = (krwAmount * feeBps) / BPS_DENOMINATOR;
        tokenOut = ((krwAmount - fee) * PRICE_SCALE) / priceE8;
    }

    /// @notice Oracle에 마지막으로 기록된 가격으로 참고용 매도 견적을 계산한다.
    function quoteSell(uint256 tokenAmount) public view returns (uint256 krwOut, uint256 fee) {
        return quoteSellAtPrice(tokenAmount, oracle.priceE8());
    }

    /// @notice 지정 가격으로 매도 결과를 계산한다. 서명 보고서의 최소 수령량 산정에 사용한다.
    function quoteSellAtPrice(uint256 tokenAmount, uint256 priceE8) public view returns (uint256 krwOut, uint256 fee) {
        if (priceE8 == 0) revert InvalidPrice();
        uint256 gross = (tokenAmount * priceE8) / PRICE_SCALE;
        fee = (gross * feeBps) / BPS_DENOMINATOR;
        krwOut = gross - fee;
    }

    /// @notice 서명된 매수 보고서를 한 번만 소비하고 사용자의 mKRW를 mSEC로 교환한다.
    function buy(PriceReportTypes.PriceReport calldata report, bytes calldata signature)
        external
        nonReentrant
        returns (uint256 tokenOut)
    {
        _validateReport(report, PriceReportTypes.SIDE_BUY);
        uint256 priceE8 = oracle.consumePriceReport(report, signature);
        uint256 fee;
        (tokenOut, fee) = quoteBuyAtPrice(report.inputAmount, priceE8);
        if (tokenOut == 0) revert ZeroAmount();
        if (tokenOut < report.minimumOutput) revert MinimumOutputNotMet(tokenOut, report.minimumOutput);

        krw.safeTransferFrom(msg.sender, address(this), report.inputAmount);
        token.mint(msg.sender, tokenOut);
        emit Bought(msg.sender, report.inputAmount, tokenOut, fee, priceE8);
    }

    /// @notice 서명된 매도 보고서를 한 번만 소비하고 사용자의 mSEC를 mKRW로 교환한다.
    function sell(PriceReportTypes.PriceReport calldata report, bytes calldata signature)
        external
        nonReentrant
        returns (uint256 krwOut)
    {
        _validateReport(report, PriceReportTypes.SIDE_SELL);
        uint256 priceE8 = oracle.consumePriceReport(report, signature);
        uint256 fee;
        (krwOut, fee) = quoteSellAtPrice(report.inputAmount, priceE8);
        if (krwOut == 0) revert ZeroAmount();
        if (krwOut < report.minimumOutput) revert MinimumOutputNotMet(krwOut, report.minimumOutput);
        if (krw.balanceOf(address(this)) < krwOut) revert InsufficientLiquidity();

        token.burn(msg.sender, report.inputAmount);
        krw.safeTransfer(msg.sender, krwOut);
        emit Sold(msg.sender, report.inputAmount, krwOut, fee, priceE8);
    }

    function setFeeBps(uint256 newFeeBps) external onlyOwner {
        if (newFeeBps > 1_000) revert FeeTooHigh();
        emit FeeBpsUpdated(feeBps, newFeeBps);
        feeBps = newFeeBps;
    }

    function _validateReport(PriceReportTypes.PriceReport calldata report, uint8 expectedSide) internal view {
        if (report.side != expectedSide) revert InvalidReportSide();
        if (report.executor != msg.sender) revert InvalidExecutor();
        if (report.inputAmount == 0) revert ZeroAmount();
        if (report.minimumOutput == 0) revert InvalidMinimumOutput();
    }
}
