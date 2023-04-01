package com.github.serezhka.airplay.lib;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

/**
 * Registers airplay/airtunes service mdns
 */
public class AirPlayBonjour {
    private static String TAG = "AirPlayBonjour";

    private static final String AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local";
    private static final String AIRTUNES_SERVICE_TYPE = "_raop._tcp.local";

    private final Context context;
    private String serverName;
    private NsdManager.RegistrationListener registrationListener;

    public AirPlayBonjour(Context context, String serverName) {
        this.context = context;
        this.serverName = serverName;
        initializeRegistrationListener();
    }

    public void start(int airPlayPort) {
        String macAddress = getMacAddress();
        NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);

        // register airplay mdns service
        nsdManager.registerService(
                airPlayMDNSProps(macAddress, airPlayPort),
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener);

        // TODO: register airtunes service
    }

    public void stop() {
        NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        nsdManager.unregisterService(registrationListener);
    }

    // FIXME: deprecated function
    @SuppressWarnings("deprecation")
    private String getMacAddress() {
        WifiManager manager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = manager.getConnectionInfo();
        return info.getMacAddress();
    }

    private NsdServiceInfo airPlayMDNSProps(String deviceId, int port) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();

        serviceInfo.setServiceName(serverName);
        serviceInfo.setServiceType(AIRPLAY_SERVICE_TYPE);
        serviceInfo.setPort(port);
        serviceInfo.setAttribute("deviceid", deviceId);
        serviceInfo.setAttribute("features", "0x5A7FFFF7,0x1E"); // 0x5A7FFFF7 E4
        serviceInfo.setAttribute("srcvers", "220.68");
        serviceInfo.setAttribute("flags", "0x44");
        serviceInfo.setAttribute("vv", "2");
        serviceInfo.setAttribute("model", "AppleTV3,2C");
        serviceInfo.setAttribute("rhd", "5.6.0.0");
        serviceInfo.setAttribute("pw", "false");
        serviceInfo.setAttribute("pk", "f3769a660475d27b4f6040381d784645e13e21c53e6d2da6a8c3d757086fc336");
        serviceInfo.setAttribute("rmodel", "PC1.0");
        serviceInfo.setAttribute("rrv", "1.01");
        serviceInfo.setAttribute("rsv", "1.00");
        serviceInfo.setAttribute("pcversion", "1715");
        return serviceInfo;
    }

    private NsdServiceInfo airTunesMDNSProps() {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();

        serviceInfo.setAttribute("ch", "2");
        serviceInfo.setAttribute("cn", "1,3");
        serviceInfo.setAttribute("da", "true");
        serviceInfo.setAttribute("et", "0,3,5");
        serviceInfo.setAttribute("ek", "1");
        //serviceInfo.setAttribute("vv", "2");
        serviceInfo.setAttribute("ft", "0x5A7FFFF7,0x1E");
        serviceInfo.setAttribute("am", "AppleTV3,2C");
        serviceInfo.setAttribute("md", "0,1,2");
        //serviceInfo.setAttribute("rhd", "5.6.0.0");
        //serviceInfo.setAttribute("pw", "false");
        serviceInfo.setAttribute("sr", "44100");
        serviceInfo.setAttribute("ss", "16");
        serviceInfo.setAttribute("sv", "false");
        serviceInfo.setAttribute("sm", "false");
        serviceInfo.setAttribute("tp", "UDP");
        serviceInfo.setAttribute("txtvers", "1");
        serviceInfo.setAttribute("sf", "0x44");
        serviceInfo.setAttribute("vs", "220.68");
        serviceInfo.setAttribute("vn", "65537");
        serviceInfo.setAttribute("pk", "f3769a660475d27b4f6040381d784645e13e21c53e6d2da6a8c3d757086fc336");
        return serviceInfo;
    }

    public void initializeRegistrationListener() {
        Log.d(TAG, "Creating registration listener");
        registrationListener = new NsdManager.RegistrationListener() {

            @Override
            public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
                // Save the service name. Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                serverName = NsdServiceInfo.getServiceName();
                Log.d(TAG, String.format("Service registered as %s", serverName));
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Registration failed! Put debugging code here to determine why.
                Log.e(TAG, String.format("Service registration failed with error %d", errorCode));
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo arg0) {
                // Service has been unregistered. This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.
                Log.d(TAG, String.format("Service %s was unregistered", serverName));
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Unregistration failed. Put debugging code here to determine why.
                Log.e(TAG, String.format("Service unregistration failed with error %d", errorCode));
            }
        };
    }
}
