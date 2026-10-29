package com.ray.web3.consts;

import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Uint256;

import java.util.Arrays;

public class EventConsts {

    // 1. 定义事件（静态常量）
    public static final Event TRANSFER_EVENT = new Event("Transfer",
            Arrays.asList(
                    new TypeReference<Address>(true) {},  // from (indexed)
                    new TypeReference<Address>(true) {},  // to (indexed)
                    new TypeReference<Uint256>(false) {}  // value
            ));
}
