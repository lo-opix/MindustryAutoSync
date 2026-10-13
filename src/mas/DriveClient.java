package mas;

import arc.Core;
import arc.util.io.Streams;
import arc.util.serialization.Base64Coder;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the Google Apps Script web app (see drive-script/Code.gs) that stores the save in a Google Drive folder.
 */
public class DriveClient {
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 60_000;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern FOLDER_LINK = Pattern.compile("(?:/folders/|[?&]id=)([\\w-]+)");

    /**
     * Thrown when the script answers, but with an error (wrong password, invalid folder...).
     * Messages starting with "@" are bundle keys.
     */
    public static class DriveException extends IOException {
        public DriveException(String message) {
            super(message);
        }
    }

    /** Remote save metadata, and its content if it was requested. */
    public static class RemoteSave {
        public final boolean exists;
        public final long lastSynced;
        public final byte[] data;

        RemoteSave(boolean exists, long lastSynced, byte[] data) {
            this.exists = exists;
            this.lastSynced = lastSynced;
            this.data = data;
        }
    }

    public String getScriptUrl() {
        return Core.settings.getString("mas-script-url", "").trim();
    }

    /** Accepts a folder ID or any Google Drive folder link. */
    public String getFolderId() {
        String raw = Core.settings.getString("mas-folder", "").trim();
        Matcher matcher = FOLDER_LINK.matcher(raw);
        return matcher.find() ? matcher.group(1) : raw;
    }

    public boolean isConfigured() {
        return !getScriptUrl().isEmpty() && !getFolderId().isEmpty();
    }

    /** Fetches the remote save metadata, and its content when {@code withData} is true. */
    public RemoteSave fetch(boolean withData) throws IOException {
        String query = withData ? "" : "&action=info";
        JsonValue json = request("GET", buildUrl(query), null);
        byte[] data = null;
        if (withData && json.getBoolean("exists", false)) {
            data = Base64Coder.decode(json.getString("data"));
        }
        return new RemoteSave(json.getBoolean("exists", false), json.getLong("lastSynced", 0), data);
    }

    /** Replaces the remote save with {@code zip}. */
    public void upload(byte[] zip, long lastSynced) throws IOException {
        byte[] body = new String(Base64Coder.encode(zip)).getBytes(StandardCharsets.US_ASCII);
        request("POST", buildUrl("&lastSynced=" + lastSynced), body);
    }

    private String buildUrl(String extraQuery) throws IOException {
        String url = getScriptUrl();
        String secret = Core.settings.getString("mas-secret", "");
        return url + (url.contains("?") ? "&" : "?")
                + "folder=" + URLEncoder.encode(getFolderId(), "UTF-8")
                + (secret.isEmpty() ? "" : "&secret=" + URLEncoder.encode(secret, "UTF-8"))
                + extraQuery;
    }

    private JsonValue request(String method, String url, byte[] body) throws IOException {
        // Apps Script answers with a redirect to googleusercontent.com, which must be fetched with GET.
        // Redirects are followed by hand because HttpURLConnection doesn't do it for POST requests.
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestMethod(method);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/plain");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }

            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                url = connection.getHeaderField("Location");
                connection.disconnect();
                if (url == null) throw new IOException("Redirect without location");
                method = "GET";
                body = null;
                continue;
            }

            String response;
            try (InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                response = in == null ? "" : new String(Streams.copyBytes(in), StandardCharsets.UTF_8);
            } finally {
                connection.disconnect();
            }
            if (code >= 400) throw new DriveException("HTTP " + code);

            JsonValue json;
            try {
                json = new JsonReader().parse(response);
            } catch (RuntimeException e) {
                // Usually a Google login page: the web app is not shared with "Anyone"
                throw new DriveException("@mas-script-not-public");
            }
            if (json.has("error")) throw new DriveException(json.getString("error"));
            return json;
        }
        throw new IOException("Too many redirects");
    }
}
