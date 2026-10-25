package com.majnioui.m3ulite;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The channels. They come from the owner's M3U playlists and Xtream Codes accounts, added and removed
 * in the app (there are none at first: the app is only a player); the channels of all of them are put
 * together. Each
 * playlist is fetched again every time the app starts, so editing a playlist changes the channels. The
 * last copy of each that could be fetched is saved for when it can't be reached.
 *
 * On top of the playlists sit the owner's own edits, made in the app: channels removed, changed or
 * added. They are kept apart from the playlists (edits.json) so a new copy of one doesn't undo them.
 */
final class ChannelList {
    private static final String TAG = "Channels";
    private static final String EDITS = "edits.json";
    private static final Pattern ATTR = Pattern.compile("([a-z-]+)=\"([^\"]*)\"");
    /** "(1080p)", "[Geo-blocked]", "[Not 24/7]": playlist notes, not part of the name. */
    private static final Pattern NOTES = Pattern.compile("\\s*(\\(\\d{3,4}[pi]\\)|\\[[^\\]]*\\])");

    static final class Channel {
        /** Stable name of the channel for the edits and the last channel watched. */
        String key;
        final String id;
        String name, group, logo, url, userAgent, referrer;
        /** Added in the app rather than in a playlist. */
        boolean own;
        /** The link of the playlist it comes from; null for the owner's own channels. */
        String source;

        Channel(String id, String name, String group, String logo, String url, String userAgent, String referrer) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.logo = logo;
            this.url = url;
            this.userAgent = userAgent;
            this.referrer = referrer;
        }
    }

    /** An M3U playlist (url), or an Xtream Codes account (url is the server, with user and pass). */
    static final class Playlist {
        String name, url, user, pass;

        Playlist(String name, String url) {
            this.name = name;
            this.url = url;
        }

        boolean xtream() {
            return user != null && !user.isEmpty();
        }

        /** What tells it from the others: its link, or the account on its server. */
        String id() {
            return xtream() ? "xtream:" + user + "@" + url : url;
        }
    }

    private ChannelList() {
    }

    // ---- the playlists --------------------------------------------------------------------------

    static List<Playlist> playlists(Context ctx) {
        List<Playlist> out = new ArrayList<>();
        String json = prefs(ctx).getString("playlists", null);
        if (json == null) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Playlist p = new Playlist(o.optString("name"), o.optString("url"));
                p.user = o.optString("user", null);
                p.pass = o.optString("pass", null);
                out.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "playlists: " + e);
        }
        return out;
    }

    private static void savePlaylists(Context ctx, List<Playlist> list) {
        JSONArray a = new JSONArray();
        try {
            for (Playlist p : list) {
                JSONObject o = new JSONObject().put("name", p.name).put("url", p.url);
                if (p.xtream()) o.put("user", p.user).put("pass", p.pass);
                a.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "save playlists: " + e);
        }
        prefs(ctx).edit().putString("playlists", a.toString()).apply();
    }

    /**
     * Fetches the playlist p and adds it, or puts it in place of the playlist whose id is old (a changed
     * name, link or account); returns the number of its channels, or -1 when it can't be fetched or has
     * none (nothing changes then). Blocking: run off the main thread.
     */
    static int addPlaylist(Context ctx, Playlist p, String old) {
        int n = fetchPlaylist(ctx, p);
        if (n <= 0) return -1;
        List<Playlist> list = playlists(ctx);
        int at = list.size();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(old)) at = i;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id().equals(p.id()) || list.get(i).id().equals(old)) list.remove(i);
        }
        list.add(Math.min(at, list.size()), p);
        savePlaylists(ctx, list);
        if (old != null && !old.equals(p.id())) saved(ctx, old).delete();
        return n;
    }

    /** Takes the playlist with this id and its channels out of the list. */
    static void removePlaylist(Context ctx, String id) {
        List<Playlist> list = playlists(ctx);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id().equals(id)) list.remove(i);
        savePlaylists(ctx, list);
        saved(ctx, id).delete();
    }

    /**
     * Fetches every playlist again; returns the new list, or null when none could be fetched (the saved
     * copies are kept then). Blocking: run off the main thread.
     */
    static List<Channel> refresh(Context ctx) {
        boolean any = false;
        for (Playlist p : playlists(ctx)) any |= fetchPlaylist(ctx, p) > 0;
        return any ? load(ctx) : null;
    }

    /**
     * Fetches one playlist and saves it as M3U if it has channels; returns how many, or -1. An Xtream
     * Codes account is turned into M3U here, so the rest of the app only knows M3U.
     */
    static int fetchPlaylist(Context ctx, Playlist p) {
        try {
            byte[] data = p.xtream() ? xtream(p).getBytes(StandardCharsets.UTF_8) : fetch(p.url);
            int n = parse(new String(data, StandardCharsets.UTF_8)).size();
            if (n == 0) {
                Log.w(TAG, "playlist " + p.name + ": no channels");
                return -1;
            }
            try (FileOutputStream out = new FileOutputStream(saved(ctx, p.id()))) {
                out.write(data);
            }
            return n;
        } catch (Exception e) {
            Log.w(TAG, "playlist " + p.name + ": " + e);
            return -1;
        }
    }

    /** "http://host:port", whatever the owner typed around it. */
    static String server(String typed) {
        String s = typed.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        int api = s.indexOf("/player_api.php");
        if (api < 0) api = s.indexOf("/get.php");
        if (api >= 0) s = s.substring(0, api);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The live channels of an Xtream Codes account, as M3U, in the order of its categories. */
    private static String xtream(Playlist p) throws Exception {
        String api = p.url + "/player_api.php?username=" + Uri.encode(p.user) + "&password=" + Uri.encode(p.pass);
        JSONObject info = new JSONObject(new String(fetch(api), StandardCharsets.UTF_8));
        JSONObject user = info.optJSONObject("user_info");
        if (user == null || user.optInt("auth", 0) != 1) throw new Exception("login refused");
        // HLS when the account has it: it starts faster and copes with a slow network.
        JSONArray formats = user.optJSONArray("allowed_output_formats");
        String ext = "ts";
        for (int i = 0; formats != null && i < formats.length(); i++) if ("m3u8".equals(formats.optString(i))) ext = "m3u8";

        Map<String, String> categories = new LinkedHashMap<>();
        JSONArray cats = new JSONArray(new String(fetch(api + "&action=get_live_categories"), StandardCharsets.UTF_8));
        for (int i = 0; i < cats.length(); i++) {
            JSONObject c = cats.getJSONObject(i);
            categories.put(c.optString("category_id"), c.optString("category_name"));
        }
        List<String> order = new ArrayList<>(categories.keySet());
        JSONArray streams = new JSONArray(new String(fetch(api + "&action=get_live_streams"), StandardCharsets.UTF_8));
        List<JSONObject> list = new ArrayList<>(streams.length());
        for (int i = 0; i < streams.length(); i++) list.add(streams.getJSONObject(i));
        list.sort((a, b) -> Integer.compare(rank(order, a.optString("category_id")), rank(order, b.optString("category_id"))));

        StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        for (JSONObject st : list) {
            String id = st.optString("stream_id");
            if (id.isEmpty()) continue;
            String group = categories.get(st.optString("category_id"));
            m3u.append("#EXTINF:-1 tvg-id=\"xc-").append(id)
                    .append("\" tvg-logo=\"").append(clean(st.optString("stream_icon")))
                    .append("\" group-title=\"").append(clean(group == null ? "Other" : group))
                    .append("\",").append(st.optString("name").replace('\n', ' ')).append('\n')
                    .append(p.url).append("/live/").append(Uri.encode(p.user)).append('/')
                    .append(Uri.encode(p.pass)).append('/').append(id).append('.').append(ext).append('\n');
        }
        return m3u.toString();
    }

    private static int rank(List<String> order, String category) {
        int i = order.indexOf(category);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static String clean(String attr) {
        return attr == null ? "" : attr.replace("\"", "'").replace('\n', ' ');
    }

    /** The saved copies of the playlists, with the edits. Fast; main thread is fine. */
    static List<Channel> load(Context ctx) {
        List<Channel> base = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Playlist p : playlists(ctx)) {
            List<Channel> list = null;
            File f = saved(ctx, p.id());
            try {
                if (f.isFile()) list = parse(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "saved " + p.name + ": " + e);
            }
            if (list == null) continue;
            for (Channel c : list) {
                // The same channel in two playlists: both stay, with keys of their own.
                String key = c.key;
                for (int n = 2; !keys.add(key); n++) key = c.key + "#" + n;
                c.key = key;
                c.source = p.id();
                base.add(c);
            }
        }
        return applyEdits(ctx, base);
    }

    private static File saved(Context ctx, String id) {
        return new File(ctx.getFilesDir(), "playlist-" + Integer.toHexString(id.hashCode()) + ".m3u");
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("channels", Context.MODE_PRIVATE);
    }

    // ---- the owner's edits ----------------------------------------------------------------------

    /** Removes a channel from the list; a playlist channel stays removed when the playlist comes back. */
    static void remove(Context ctx, Channel c) {
        JSONObject e = edits(ctx);
        try {
            if (c.own) dropOwn(e, c.key);
            else {
                e.getJSONObject("changed").remove(c.key);
                e.getJSONArray("removed").put(c.key);
            }
        } catch (Exception x) {
            Log.w(TAG, "remove: " + x);
        }
        saveEdits(ctx, e);
    }

    /** Saves a changed or a new channel (c.key null). */
    static void save(Context ctx, Channel c) {
        JSONObject e = edits(ctx);
        try {
            JSONObject o = new JSONObject().put("name", c.name).put("group", c.group).put("url", c.url)
                    .put("logo", c.logo);
            if (c.key == null) {
                c.key = "own-" + System.currentTimeMillis();
                c.own = true;
            }
            if (c.own) {
                dropOwn(e, c.key);
                e.getJSONArray("added").put(o.put("key", c.key));
            } else {
                e.getJSONObject("changed").put(c.key, o);
            }
        } catch (Exception x) {
            Log.w(TAG, "save: " + x);
        }
        saveEdits(ctx, e);
    }

    /** Forgets every edit: the list is the playlist again. */
    static void resetEdits(Context ctx) {
        new File(ctx.getFilesDir(), EDITS).delete();
    }

    static boolean hasEdits(Context ctx) {
        return new File(ctx.getFilesDir(), EDITS).isFile();
    }

    private static List<Channel> applyEdits(Context ctx, List<Channel> base) {
        JSONObject e = edits(ctx);
        JSONArray removedArr = e.optJSONArray("removed");
        JSONObject changed = e.optJSONObject("changed");
        JSONArray added = e.optJSONArray("added");
        Set<String> removed = new HashSet<>();
        for (int i = 0; removedArr != null && i < removedArr.length(); i++) removed.add(removedArr.optString(i));
        List<Channel> out = new ArrayList<>();
        for (Channel c : base) {
            if (removed.contains(c.key)) continue;
            JSONObject o = changed == null ? null : changed.optJSONObject(c.key);
            if (o != null) {
                c.name = o.optString("name", c.name);
                c.group = o.optString("group", c.group);
                c.logo = o.optString("logo", c.logo);
                String url = o.optString("url", c.url);
                if (!url.equals(c.url)) {
                    c.url = url;
                    c.userAgent = null;
                    c.referrer = null;
                }
            }
            out.add(c);
        }
        for (int i = 0; added != null && i < added.length(); i++) {
            JSONObject o = added.optJSONObject(i);
            if (o == null) continue;
            Channel c = new Channel("", o.optString("name"), o.optString("group"), o.optString("logo"),
                    o.optString("url"), null, null);
            c.key = o.optString("key");
            c.own = true;
            out.add(c);
        }
        return byGroup(out);
    }

    private static JSONObject edits(Context ctx) {
        JSONObject e = null;
        File f = new File(ctx.getFilesDir(), EDITS);
        try {
            if (f.isFile()) e = new JSONObject(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
        } catch (Exception x) {
            Log.w(TAG, "edits: " + x);
        }
        if (e == null) e = new JSONObject();
        try {
            if (e.optJSONArray("removed") == null) e.put("removed", new JSONArray());
            if (e.optJSONObject("changed") == null) e.put("changed", new JSONObject());
            if (e.optJSONArray("added") == null) e.put("added", new JSONArray());
        } catch (Exception ignored) {
        }
        return e;
    }

    private static void saveEdits(Context ctx, JSONObject e) {
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), EDITS))) {
            out.write(e.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception x) {
            Log.w(TAG, "save edits: " + x);
        }
    }

    private static void dropOwn(JSONObject e, String key) throws Exception {
        JSONArray added = e.getJSONArray("added");
        for (int i = added.length() - 1; i >= 0; i--) {
            JSONObject o = added.optJSONObject(i);
            if (o != null && key.equals(o.optString("key"))) added.remove(i);
        }
    }

    /** Channels of a group together, groups in the order they first appear. */
    private static List<Channel> byGroup(List<Channel> list) {
        Map<String, List<Channel>> groups = new LinkedHashMap<>();
        for (Channel c : list) {
            List<Channel> g = groups.get(c.group);
            if (g == null) groups.put(c.group, g = new ArrayList<>());
            g.add(c);
        }
        List<Channel> out = new ArrayList<>(list.size());
        for (List<Channel> g : groups.values()) out.addAll(g);
        return out;
    }

    // ---- M3U ------------------------------------------------------------------------------------

    /** M3U with #EXTINF attributes (tvg-id, tvg-logo, group-title) and optional #EXTVLCOPT headers. */
    static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (!l.startsWith("#EXTINF")) continue;
            int comma = l.lastIndexOf("\",");
            comma = comma >= 0 ? comma + 1 : l.indexOf(',');
            if (comma < 0) continue;
            String name = NOTES.matcher(l.substring(comma + 1)).replaceAll("").trim();
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher m = ATTR.matcher(l.substring(0, comma));
            while (m.find()) attrs.put(m.group(1), m.group(2));
            String ua = null, ref = null, url = "";
            for (int j = i + 1; j < lines.length && j < i + 8; j++) {
                String x = lines[j].trim();
                if (x.startsWith("#EXTVLCOPT:http-user-agent=")) ua = x.substring(27);
                else if (x.startsWith("#EXTVLCOPT:http-referrer=")) ref = x.substring(25);
                else if (x.startsWith("#EXTINF")) break;
                else if (!x.isEmpty() && !x.startsWith("#")) {
                    url = x;
                    break;
                }
            }
            if (url.isEmpty()) continue;
            // "Business;Documentary": the first category is the group.
            String group = value(attrs, "group-title");
            int semi = group.indexOf(';');
            if (semi >= 0) group = group.substring(0, semi).trim();
            if (group.isEmpty()) group = "Other";
            Channel c = new Channel(value(attrs, "tvg-id"), name, group, value(attrs, "tvg-logo"), url, ua, ref);
            String key = c.id.isEmpty() ? name : c.id;
            for (int n = 2; !keys.add(key); n++) key = (c.id.isEmpty() ? name : c.id) + "#" + n;
            c.key = key;
            out.add(c);
        }
        return out;
    }

    private static String value(Map<String, String> attrs, String key) {
        String v = attrs.get(key);
        return v == null ? "" : v.trim();
    }

    static byte[] fetch(String url) throws Exception {
        return fetch(url, Integer.MAX_VALUE);
    }

    /** The first max bytes (or all) of url. */
    static byte[] fetch(String url, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; out.size() < max && (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = src.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
