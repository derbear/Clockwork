package edu.berkeley.eecs.shared;

/**
 * Created by derek on 8/7/15.
 */
public class ProtocolConstants {
    /*
    Format of a ping packet:
        (int)  packet number
        (long) request send time
        (long) request receive time
        (long) response send time
        REMOVED - REDUNDANT (long) response receive time
     */

    public static final int PING_SIZE = (Integer.SIZE) + (Long.SIZE) * 3;
    public static final String PING_PATH = "/ping";
    public static final String PONG_PATH = "/pong";
}
