package com.grapelemon.lumineeconomybridge.cash;

import com.google.gson.annotations.SerializedName;

public enum CashEventAction {
    @SerializedName("issue")
    ISSUE,
    @SerializedName("transfer")
    TRANSFER,
    @SerializedName("pickup")
    PICKUP,
    @SerializedName("store")
    STORE,
    @SerializedName("retrieve")
    RETRIEVE,
    @SerializedName("drop")
    DROP,
    @SerializedName("move")
    MOVE,
    @SerializedName("destroy")
    DESTROY;
}
