// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

import {PriceOracle} from "./PriceOracle.sol";
import {SamsungPriceTrackingToken} from "./SamsungPriceTrackingToken.sol";

/// @title ExchangeVault
/// @notice 오라클 기준 가격으로 mKRW <-> mSEC 즉시 매수/매도를 정산하는 핵심 컨트랙트.
/// @dev 기획서 §9.5, §7. 가격은 PriceOracle.priceE8(1e8 정밀도)을 사용한다.
///      mKRW/mSEC 는 모두 18 decimals 로 가정한다.
contract ExchangeVault is Ownable, ReentrancyGuard {
    using SafeERC20 for IERC20;

    uint256 private constant PRICE_SCALE = 1e8; // PriceOracle 정밀도
    uint256 private constant BPS_DENOMINATOR = 10_000;

    IERC20 public immutable krw; // MockKRW
    SamsungPriceTrackingToken public immutable token; // mSEC
    PriceOracle public immutable oracle;

    /// @notice 수수료율 (basis points). 기획서 §12.3 예시(100/100000 = 0.1%) 기준 기본 10 bps.
    uint256 public feeBps = 10;

    event Bought(
        address indexed user,
        uint256 krwIn,
        uint256 tokenOut,
        uint256 fee,
        uint256 priceE8
    );
    event Sold(
        address indexed user,
        uint256 tokenIn,
        uint256 krwOut,
        uint256 fee,
        uint256 priceE8
    );
    event FeeBpsUpdated(uint256 previousFeeBps, uint256 newFeeBps);

    error ZeroAmount();
    error InsufficientLiquidity();
    error FeeTooHigh();

    constructor(address krw_, address token_, address oracle_) Ownable(msg.sender) {
        krw = IERC20(krw_);
        token = SamsungPriceTrackingToken(token_);
        oracle = PriceOracle(oracle_);
    }

    // ----------------------------------------------------------------------
    // 견적 (view) — 백엔드 §12.3 견적 API 와 동일한 계산식
    // ----------------------------------------------------------------------

    /// @notice krwAmount 매수 시 받을 토큰 수량과 수수료를 계산한다.
    function quoteBuy(uint256 krwAmount)
        public
        view
        returns (uint256 tokenOut, uint256 fee)
    {
        uint256 priceE8 = _price();
        fee = (krwAmount * feeBps) / BPS_DENOMINATOR;
        uint256 net = krwAmount - fee;
        tokenOut = (net * PRICE_SCALE) / priceE8;
    }

    /// @notice tokenAmount 매도 시 받을 mKRW 와 수수료를 계산한다.
    function quoteSell(uint256 tokenAmount)
        public
        view
        returns (uint256 krwOut, uint256 fee)
    {
        uint256 priceE8 = _price();
        uint256 gross = (tokenAmount * priceE8) / PRICE_SCALE;
        fee = (gross * feeBps) / BPS_DENOMINATOR;
        krwOut = gross - fee;
    }

    // ----------------------------------------------------------------------
    // 매수 / 매도
    // ----------------------------------------------------------------------

    /// @notice 사용자의 mKRW 를 받아 mSEC 를 발행한다. (사전 approve 필요)
    function buy(uint256 krwAmount) external nonReentrant returns (uint256 tokenOut) {
        if (krwAmount == 0) revert ZeroAmount();
        uint256 priceE8 = _price();
        uint256 fee;
        (tokenOut, fee) = quoteBuy(krwAmount);
        if (tokenOut == 0) revert ZeroAmount();

        // mKRW 전액(수수료 포함)을 Vault 가 수취 → Vault 유동성으로 적립
        krw.safeTransferFrom(msg.sender, address(this), krwAmount);
        token.mint(msg.sender, tokenOut);

        emit Bought(msg.sender, krwAmount, tokenOut, fee, priceE8);
    }

    /// @notice 사용자의 mSEC 를 소각하고 mKRW 를 지급한다.
    function sell(uint256 tokenAmount) external nonReentrant returns (uint256 krwOut) {
        if (tokenAmount == 0) revert ZeroAmount();
        uint256 priceE8 = _price();
        uint256 fee;
        (krwOut, fee) = quoteSell(tokenAmount);
        if (krwOut == 0) revert ZeroAmount();
        if (krw.balanceOf(address(this)) < krwOut) revert InsufficientLiquidity();

        token.burn(msg.sender, tokenAmount); // 사용자 approve 불필요 (minter 권한)
        krw.safeTransfer(msg.sender, krwOut);

        emit Sold(msg.sender, tokenAmount, krwOut, fee, priceE8);
    }

    // ----------------------------------------------------------------------
    // 관리자
    // ----------------------------------------------------------------------

    function setFeeBps(uint256 newFeeBps) external onlyOwner {
        if (newFeeBps > 1_000) revert FeeTooHigh(); // 최대 10%
        emit FeeBpsUpdated(feeBps, newFeeBps);
        feeBps = newFeeBps;
    }

    function _price() internal view returns (uint256) {
        return oracle.priceE8();
    }
}
