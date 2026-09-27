package com.martecyber.ares.assets;

import java.util.Set;

/** Valid values for {@code Asset.hostSubtype} (only meaningful when type == "host"). */
public final class HostSubtype {

    public static final String PC        = "pc";
    public static final String SERVER    = "server";
    public static final String VM        = "vm";
    public static final String CONTAINER = "container";
    public static final String ROUTER    = "router";
    public static final String FIREWALL  = "firewall";
    public static final String SWITCH    = "switch";
    public static final String WIFI_AP   = "wifi_ap";
    public static final String OTHER     = "other";
    public static final String UNKNOWN   = "unknown";

    public static final Set<String> ALL = Set.of(
        PC, SERVER, VM, CONTAINER, ROUTER, FIREWALL, SWITCH, WIFI_AP, OTHER, UNKNOWN
    );

    private HostSubtype() {}
}
