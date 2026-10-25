package com.paycore.webhook;

import com.paycore.common.ApiException;
import org.springframework.http.HttpStatus;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * SSRF guard. Merchants choose the URL our server will call, so without this check a merchant
 * could point us at localhost, the database, or a cloud metadata endpoint (169.254.169.254).
 * We check at registration AND again at delivery time (DNS can change in between).
 */
public final class WebhookUrls {
    private WebhookUrls() {}

    public static void assertAllowed(String url, boolean allowPrivate) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "URL must start with http:// or https://");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "URL must contain a host");
        }
        if (allowPrivate) return;

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot resolve host: " + host);
        }
        for (InetAddress a : addresses) {
            if (isInternal(a)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Webhook URL must not point to a private or internal address");
            }
        }
    }

    static boolean isInternal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC; // IPv6 unique-local fc00::/7
    }
}
