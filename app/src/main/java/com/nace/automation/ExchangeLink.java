package com.nace.automation;

import android.net.Uri;

import java.net.URI;
import java.util.Locale;

final class ExchangeLink {
    static final String PREFERENCE = "exchange_link";
    static final String DEFAULT_URL = "https://h5.naexchangeu.com/#/home";

    final String url;
    final String origin;
    private final String entryPoint;

    private ExchangeLink(String url, String origin, String entryPoint) {
        this.url = url;
        this.origin = origin;
        this.entryPoint = entryPoint;
    }

    static ExchangeLink parse(String input) {
        URI uri = URI.create(input.trim());
        String host = uri.getHost();
        int port = uri.getPort();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                || port == 0 || port > 65535) throw new IllegalArgumentException();
        String origin = "https://" + host.toLowerCase(Locale.ROOT)
                + (port < 0 || port == 443 ? "" : ":" + port);
        String path = uri.getRawPath();
        String entryPoint = origin + (path.isEmpty() ? "/" : path);
        String url = entryPoint + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        return new ExchangeLink(url, origin, entryPoint);
    }

    String signInRoute() {
        return entryPoint + "#/login";
    }

    String tradeRoute(String instrumentId) {
        return entryPoint + "#/forward?id=" + instrumentId;
    }

    boolean matchesOrigin(Uri uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null) return false;
        int port = uri.getPort();
        String currentOrigin = "https://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (port < 0 || port == 443 ? "" : ":" + port);
        return origin.equals(currentOrigin);
    }
}
