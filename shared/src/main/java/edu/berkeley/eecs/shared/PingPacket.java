package edu.berkeley.eecs.shared;

/**
 * Created by derek on 8/7/15.
 */
public class PingPacket {
    public int packetNumber = -1;
    public long requestSend = -1;
    public long requestReceive = -1;
    public long responseSend = -1;
    public long responseReceive = -1;

    public PingPacket(int packetNumber, long requestSend, long requestReceive, long responseSend, long responseReceive) {
        this.packetNumber = packetNumber;
        this.requestSend = requestSend;
        this.requestReceive = requestReceive;
        this.responseSend = responseSend;
        this.responseReceive = responseReceive;
    }

    public long up() {
        return requestReceive - requestSend;
    }

    public long down() {
        return responseReceive - responseSend;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("packet ");
        builder.append(packetNumber).append(": (").append(requestSend).append(", ").append(requestReceive)
                .append("), ").append("(").append(responseSend).append(", ").append(responseReceive).append(")");
        return builder.toString();
    }
}
