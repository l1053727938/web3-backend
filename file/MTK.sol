// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

contract MTK is ERC20 {
    constructor(uint256 initialSupply) ERC20("MTK Token", "MTK") {
        // 初始供应量全部铸给部署者
        _mint(msg.sender, initialSupply);
    }
}