package io.github.jaeseongs95.portal.app.remote;

/** Opens local forwards through a target's connection ({@code direct-tcpip}). The desktop module (stage 5) uses it for the RFB and RDP endpoints of a GUI lease. */
public interface RemoteForwarder {

    ForwardOpenResult open(ForwardRequest request, ExecutionControl control);
}
