package android.net;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public final class Uri {
    public static final Uri EMPTY = Uri.parse("");

    private final String value;

    private Uri(String value) {
        this.value = value == null ? "" : value;
    }

    public static Uri parse(String value) {
        return new Uri(value);
    }

    public static String encode(String value) {
        return encode(value, null);
    }

    public static String encode(String value, String allow) {
        if (value == null) {
            return null;
        }
        try {
            String encoded = URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20");
            if (allow != null) {
                for (int index = 0; index < allow.length(); index++) {
                    String character = String.valueOf(allow.charAt(index));
                    encoded = encoded.replace(
                        encode(character, null),
                        character
                    );
                }
            }
            return encoded;
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public String getScheme() {
        return parsed().getScheme();
    }

    public String getHost() {
        return parsed().getHost();
    }

    public int getPort() {
        return parsed().getPort();
    }

    public String getPath() {
        return parsed().getPath();
    }

    public String getEncodedPath() {
        return parsed().getRawPath();
    }

    public String getQuery() {
        return parsed().getQuery();
    }

    public String getEncodedQuery() {
        return parsed().getRawQuery();
    }

    public String getFragment() {
        return parsed().getFragment();
    }

    public String getLastPathSegment() {
        String path = getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        int separator = path.lastIndexOf('/');
        return path.substring(separator + 1);
    }

    public Builder buildUpon() {
        return new Builder(value);
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Uri && value.equals(((Uri) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    private URI parsed() {
        try {
            return new URI(value);
        } catch (URISyntaxException error) {
            return URI.create(value.replace(" ", "%20"));
        }
    }

    public static final class Builder {
        private String scheme;
        private String authority;
        private final List<String> paths = new ArrayList<>();
        private final List<String> query = new ArrayList<>();
        private String fragment;

        public Builder() {
        }

        Builder(String value) {
            URI uri = Uri.parse(value).parsed();
            scheme = uri.getScheme();
            authority = uri.getRawAuthority();
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty()) {
                for (String component : path.split("/")) {
                    if (!component.isEmpty()) {
                        paths.add(component);
                    }
                }
            }
            String rawQuery = uri.getRawQuery();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                for (String component : rawQuery.split("&")) {
                    query.add(component);
                }
            }
            fragment = uri.getRawFragment();
        }

        public Builder scheme(String value) {
            scheme = value;
            return this;
        }

        public Builder authority(String value) {
            authority = value;
            return this;
        }

        public Builder appendPath(String value) {
            paths.add(Uri.encode(value));
            return this;
        }

        public Builder appendEncodedPath(String value) {
            if (value != null) {
                for (String component : value.split("/")) {
                    if (!component.isEmpty()) {
                        paths.add(component);
                    }
                }
            }
            return this;
        }

        public Builder appendQueryParameter(String key, String value) {
            query.add(Uri.encode(key) + "=" + Uri.encode(value));
            return this;
        }

        public Builder fragment(String value) {
            fragment = Uri.encode(value);
            return this;
        }

        public Uri build() {
            StringBuilder result = new StringBuilder();
            if (scheme != null) {
                result.append(scheme).append(':');
            }
            if (authority != null) {
                result.append("//").append(authority);
            }
            for (String path : paths) {
                result.append('/').append(path);
            }
            if (!query.isEmpty()) {
                result.append('?');
                for (int index = 0; index < query.size(); index++) {
                    if (index > 0) {
                        result.append('&');
                    }
                    result.append(query.get(index));
                }
            }
            if (fragment != null) {
                result.append('#').append(fragment);
            }
            return Uri.parse(result.toString());
        }

        @Override
        public String toString() {
            return build().toString();
        }
    }
}
