package com.majnioui.m3ulite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * M3U Lite: live channels from the owner's M3U playlists and Xtream Codes accounts (see ChannelList),
 * like a TV's own channel app: it opens on the last channel, full screen. OK opens the channel list on
 * the left: Up/Down to move (past the last channel back to the first and the other way round; Up on the
 * first channel opens the search box first), Left/Right to jump between groups, OK to watch, hold OK
 * to make a channel a favourite (favourites come first), Menu to edit or remove it, Back to close.
 * Without the list, Up/Down and CH+/CH- switch channels and Back leaves.
 *
 * Left twice, in the list or while watching, opens Settings in its place: the language (English or
 * Arabic), the playlists (add, change, reload, remove) and the owner's own channels. The lists are
 * ListViews, so only the rows on screen exist, however many channels there are.
 *
 * In Arabic the whole app is right to left: the panels open on the right, and Left and Right swap
 * (Right twice for Settings, Right / Left for the previous / next group).
 *
 * One player plays every channel: switching only changes what it plays, and playback starts after a
 * second of video rather than ExoPlayer's default of two and a half.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Channels";
    private static final long BAR_MS = 4000;
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** The rows of Settings: a title, a line under it, an icon. */
    private enum Action {
        LANGUAGE("Language", "اللغة", "English", "العربية", R.drawable.ic_language),
        ADD_PLAYLIST("Add a playlist", "إضافة قائمة تشغيل", "An M3U link or an Xtream Codes account",
                "رابط M3U أو حساب Xtream Codes", R.drawable.ic_add),
        RELOAD("Reload the playlists", "إعادة تحميل القوائم", "Fetch every playlist again",
                "جلب كل القوائم من جديد", R.drawable.ic_reload),
        ADD("Add a channel", "إضافة قناة", "A stream link of your own", "رابط بث خاص بك", R.drawable.ic_channel),
        RESET("Undo my changes", "التراجع عن تغييراتي", "The channels as the playlists have them",
                "القنوات كما هي في القوائم", R.drawable.ic_undo);

        final String en, ar, subEn, subAr;
        final int icon;

        Action(String en, String ar, String subEn, String subAr, int icon) {
            this.en = en;
            this.ar = ar;
            this.subEn = subEn;
            this.subAr = subAr;
            this.icon = icon;
        }
    }

    /** Headers of the app's own groups; any other header is a group name from a playlist. */
    private static final String FAVORITES = "Favorites", GENERAL = "General", PLAYLISTS = "Playlists",
            MY_LIST = "My channels";
    /** Two Left presses this close together open Settings. */
    private static final long DOUBLE_MS = 450;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> logos = new LruCache<>(60);
    private Typeface font;
    private int accent;
    private SharedPreferences prefs;
    /** The app's own words in Arabic rather than English (Settings, Language). */
    private boolean arabic;
    /** What is typed in the search box. */
    private String query = "";

    /** The channels as ChannelList gives them; channels is the same with the favourites first. */
    private List<ChannelList.Channel> loaded = new ArrayList<>();
    private List<ChannelList.Channel> channels = new ArrayList<>();
    /** Keys of the favourite channels, in the order they were chosen. */
    private final List<String> favorites = new ArrayList<>();
    private int favoriteCount;
    /** Rows of the channel list: a String is a group header, an Integer the index of a channel. */
    private final List<Object> rows = new ArrayList<>();
    /** Rows of Settings: a String is a group header, then Playlists and Actions. */
    private final List<Object> manageRows = new ArrayList<>();
    private int current = -1;
    /** When Left (Right in Arabic) was last pressed, and the list row it was pressed on, for the double press. */
    private long lastLeft;
    private int leftFrom = -1;

    private FrameLayout root;
    private SurfaceView surface;
    private TextView message;
    private LinearLayout bar;
    private ImageView barLogo;
    private TextView barName, barGroup;
    private LinearLayout menu, manage;
    /** The panel that is open (menu or manage), or null while watching. */
    private LinearLayout shown;
    private ListView list, manageList;
    private EditText search;
    private final RowAdapter adapter = new RowAdapter(rows, false);
    private final RowAdapter manageAdapter = new RowAdapter(manageRows, true);
    private ExoPlayer player;
    private final DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(10000);
    /** Counts the play() calls, so a late answer for a channel no longer wanted is dropped. */
    private int playSeq;
    /** Links without .m3u8, .mpd or .ts whose kind is known: DASH, HLS or "" for a plain stream. */
    private final Map<String, String> mimes = new HashMap<>();
    private final Runnable hideBar = () -> bar.animate().alpha(0f).setDuration(400).start();

    /** The app's language, whatever the TV's: Arabic also makes every view and dialog right to left. */
    @Override
    protected void attachBaseContext(Context base) {
        boolean ar = "ar".equals(base.getSharedPreferences("channels", MODE_PRIVATE).getString("language", "en"));
        Locale locale = ar ? new Locale("ar") : Locale.ENGLISH;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(locale);
        c.setLayoutDirection(locale);
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    /** Left in English, Right in Arabic: the key towards the edge the panels open on. */
    private int outKey() {
        return arabic ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
    }

    private int inKey() {
        return arabic ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** Arrows for the hints, pointing the way the keys go on screen. */
    private String outArrow() {
        return arabic ? "▶" : "◀";
    }

    private String inArrow() {
        return arabic ? "◀" : "▶";
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);
        prefs = getSharedPreferences("channels", MODE_PRIVATE);
        arabic = "ar".equals(prefs.getString("language", "en"));
        try {
            JSONArray a = new JSONArray(prefs.getString("favorites", "[]"));
            for (int i = 0; i < a.length(); i++) favorites.add(a.getString(i));
        } catch (Exception e) {
            Log.w(TAG, "favorites: " + e);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        message = text(22, 400, 0xCCFFFFFF);
        message.setGravity(Gravity.CENTER);
        message.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        message.setPaddingRelative(dp(48), 0, dp(48), 0);
        root.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        buildBar();
        buildMenu();
        buildManage();
        setContentView(root);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setChannels(ChannelList.load(this));
        // Playlists are small: fetch them on every start so edits to them show up right away.
        reload(false);
    }

    /** Fetches every playlist again in the background. */
    private void reload(boolean tell) {
        if (tell) toast(t("Loading the playlists…", "جارٍ تحميل القوائم…"));
        io.execute(() -> {
            List<ChannelList.Channel> fresh = ChannelList.refresh(this);
            main.post(() -> {
                if (isDestroyed()) return;
                if (fresh != null) setChannels(fresh);
                else buildRows();
                if (tell) toast(fresh != null ? count(fresh.size()) : t("Couldn't load the playlists", "تعذر تحميل القوائم"));
                playIfIdle();
            });
        });
    }

    /** After channels arrive in an empty list: start the first one. */
    private void playIfIdle() {
        if (current < 0 && !channels.isEmpty() && player != null) play(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 30000, 1000, 2000).build())
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) message.setVisibility(View.GONE);
                else if (state == Player.STATE_BUFFERING) {
                    message.setText(t("Loading…", "جارٍ التحميل…"));
                    message.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                fitSurface(size);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                String name = current >= 0 && current < channels.size() ? channels.get(current).name : t("This channel", "هذه القناة");
                Log.w(TAG, name + ": " + e.getErrorCodeName());
                message.setText(name + t(" isn't available right now.\nUp / Down for another channel, OK for the list.",
                        " غير متاحة الآن.\nأعلى / أسفل لقناة أخرى، OK للقائمة."));
                message.setVisibility(View.VISIBLE);
            }
        });
        if (channels.isEmpty()) {
            message.setText(t("No channels yet.\nAdd a playlist in Settings.", "لا توجد قنوات بعد.\nأضف قائمة تشغيل من الإعدادات."));
            message.setVisibility(View.VISIBLE);
            openManage();
            return;
        }
        int last = indexOf(prefs.getString("last", ""));
        play(last >= 0 ? last : 0);
        if (last < 0) openMenu();   // first time: show what there is
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /**
     * New list (bundled, saved, refreshed or edited): rebuild the rows, keep the channel that is playing,
     * or play its neighbour if it is gone.
     */
    private void setChannels(List<ChannelList.Channel> fresh) {
        String playingKey = current >= 0 && current < channels.size() ? channels.get(current).key : null;
        String playingUrl = playingKey != null ? channels.get(current).url : null;
        int was = current;
        loaded = fresh;
        // Favourites first, in the order they were chosen; the others as the playlists have them.
        Map<String, ChannelList.Channel> byKey = new HashMap<>();
        for (ChannelList.Channel c : fresh) byKey.put(c.key, c);
        channels = new ArrayList<>(fresh.size());
        for (String k : favorites) if (byKey.containsKey(k)) channels.add(byKey.remove(k));
        favoriteCount = channels.size();
        for (ChannelList.Channel c : fresh) if (byKey.containsKey(c.key)) channels.add(c);
        buildRows();
        manageRows.clear();
        manageRows.add(GENERAL);
        manageRows.add(Action.LANGUAGE);
        manageRows.add(PLAYLISTS);
        manageRows.addAll(ChannelList.playlists(this));
        manageRows.add(Action.ADD_PLAYLIST);
        manageRows.add(Action.RELOAD);
        manageRows.add(MY_LIST);
        manageRows.add(Action.ADD);
        if (ChannelList.hasEdits(this)) manageRows.add(Action.RESET);
        current = playingKey != null ? indexOf(playingKey) : -1;
        adapter.notifyDataSetChanged();
        manageAdapter.notifyDataSetChanged();
        if (player == null || playingKey == null) return;
        if (current < 0) {
            if (channels.isEmpty()) {
                player.stop();
                message.setText(t("No channels.", "لا توجد قنوات."));
                message.setVisibility(View.VISIBLE);
            } else {
                play(Math.min(was, channels.size() - 1));
            }
        } else if (!channels.get(current).url.equals(playingUrl)) {
            play(current);   // its link changed
        }
    }

    /** The channel list's rows: the channels whose name has the search text, under their groups. */
    private void buildRows() {
        rows.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        String group = null;
        for (int i = 0; i < channels.size(); i++) {
            if (!q.isEmpty() && !channels.get(i).name.toLowerCase(Locale.ROOT).contains(q)) continue;
            String g = i < favoriteCount ? FAVORITES : channels.get(i).group;
            if (!g.equals(group)) {
                group = g;
                rows.add(group);
            }
            rows.add(i);
        }
        adapter.notifyDataSetChanged();
    }

    private int indexOf(String key) {
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).key.equals(key)) return i;
        return -1;
    }

    // ---- channel list (side menu) ---------------------------------------------------------------

    private void buildMenu() {
        menu = panel();

        // Only the channels: no title or count above them, so the list has the panel's height.
        buildSearch();
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(46));
        slp.bottomMargin = dp(10);
        menu.addView(search, slp);

        list = listView(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Object row = rows.get(position);
            if (row instanceof Integer) {
                play((Integer) row);
                closeMenu();
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Object row = rows.get(position);
            if (!(row instanceof Integer)) return false;
            toggleFavorite((Integer) row);
            return true;
        });
        list.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == outKey()) {
                if (doubleLeft(event)) {
                    if (leftFrom >= 0) list.setSelection(leftFrom);   // undo the first press's jump
                    openManage();
                } else {
                    leftFrom = list.getSelectedItemPosition();
                    jumpGroup(-1);
                }
                return true;
            }
            if (keyCode == inKey()) {
                jumpGroup(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                int pos = list.getSelectedItemPosition();
                if (pos >= 0 && rows.get(pos) instanceof Integer) channelOptions((Integer) rows.get(pos));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && atEnd(list, rows, true)) {
                search.setVisibility(View.VISIBLE);
                search.requestFocus();
                return true;
            }
            return wrap(list, rows, keyCode);
        });
        menu.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        String o = outArrow(), i = inArrow();
        menu.addView(hint(t(o + " " + i + " groups  ·  OK watch  ·  hold OK favourite\nMenu edit  ·  ▲ on the first: search  ·  " + o + o + " settings",
                o + " " + i + " المجموعات  ·  OK مشاهدة  ·  OK مطولاً للمفضلة\nMenu تعديل  ·  ▲ على الأولى: بحث  ·  " + o + o + " الإعدادات")));

        root.addView(menu, new FrameLayout.LayoutParams(dp(400), -1, Gravity.START));
    }

    /**
     * The search box above the channel list: the list keeps only the channels whose name has what is
     * typed. Down goes to the first of them, Up round to the last. It shows only while it has focus
     * (Up on the first channel) or something typed in it.
     */
    private void buildSearch() {
        search = new EditText(this);
        search.setHint(t("Search channels", "ابحث عن قناة"));
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setTypeface(font);
        search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0x80FFFFFF);
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(12));
        normal.setColor(0x1FFFFFFF);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(12));
        focused.setColor(0x33FFFFFF);
        focused.setStroke(dp(2), 0xF5FFFFFF);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, focused);
        bg.addState(new int[] {}, normal);
        search.setBackground(bg);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence t, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (e.toString().equals(query)) return;
                query = e.toString();
                buildRows();
                select(list, 0, 0);
            }
        });
        search.setOnEditorActionListener((v, action, event) -> {
            hideKeyboard();
            selectEnd(false);
            return true;
        });
        // Out of the way unless it is in use, so the channel list has the panel's whole height.
        search.setVisibility(View.GONE);
        search.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && query.isEmpty()) search.setVisibility(View.GONE);
        });
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideKeyboard();
                selectEnd(keyCode == KeyEvent.KEYCODE_DPAD_UP);
                return true;
            }
            return false;
        });
    }

    /** Focuses the channel list on its first row, or its last. */
    private void selectEnd(boolean last) {
        for (int i = last ? rows.size() - 1 : 0; i >= 0 && i < rows.size(); i += last ? -1 : 1) {
            if (rows.get(i) instanceof Integer) {
                list.requestFocus();
                select(list, i, last ? Integer.MAX_VALUE : dp(40));
                return;
            }
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /** Settings: in place of the channel list, opened with Left twice. */
    private void buildManage() {
        manage = panel();
        manage.addView(title(t("Settings", "الإعدادات")));
        TextView sub = text(14, 400, 0x8CFFFFFF);
        sub.setText(t("Language, playlists and your own channels", "اللغة وقوائم التشغيل وقنواتك"));
        sub.setPadding(0, dp(6), 0, dp(10));
        manage.addView(sub);

        manageList = listView(manageAdapter);
        manageList.setOnItemClickListener((parent, view, position, id) -> {
            Object row = manageRows.get(position);
            if (row instanceof Action) run((Action) row);
            else if (row instanceof ChannelList.Playlist) playlistOptions((ChannelList.Playlist) row);
        });
        manageList.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == inKey()) {
                if (!channels.isEmpty()) openMenu();
                return true;
            }
            if (keyCode == outKey()) return true;
            return wrap(manageList, manageRows, keyCode);
        });
        manage.addView(manageList, new LinearLayout.LayoutParams(-1, 0, 1f));
        manage.addView(hint(t("OK choose  ·  " + inArrow() + " or Back channels",
                "OK اختيار  ·  " + inArrow() + " أو رجوع القنوات")));

        root.addView(manage, new FrameLayout.LayoutParams(dp(400), -1, Gravity.START));
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPaddingRelative(dp(28), dp(36), dp(20), dp(24));
        // Darkest at the panel's own edge, in either direction.
        p.setBackground(new GradientDrawable(arabic ? GradientDrawable.Orientation.RIGHT_LEFT
                : GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF70B0D10, 0xEE0B0D10, 0xC20B0D10}));
        p.setVisibility(View.GONE);
        return p;
    }

    private ListView listView(RowAdapter a) {
        ListView l = new ListView(this);
        l.setAdapter(a);
        l.setDivider(null);
        l.setVerticalScrollBarEnabled(false);
        // Rows half scrolled out fade at the edges rather than look cut.
        l.setVerticalFadingEdgeEnabled(true);
        l.setFadingEdgeLength(dp(28));
        l.setItemsCanFocus(false);
        l.setDrawSelectorOnTop(false);
        GradientDrawable sel = new GradientDrawable();
        sel.setCornerRadius(dp(12));
        sel.setColor(0xF5FFFFFF);
        l.setSelector(sel);
        return l;
    }

    /** A panel's name: large, at its top. */
    private TextView title(String s) {
        TextView t = text(26, 600, 0xFFFFFFFF);
        t.setText(s);
        return t;
    }

    private TextView hint(String s) {
        TextView hint = text(12, 400, 0x59FFFFFF);
        hint.setText(s);
        hint.setLineSpacing(dp(4), 1f);
        hint.setPadding(0, dp(12), 0, 0);
        return hint;
    }

    /**
     * Selects row pos of l with its top about top pixels below the list's top, kept whole on screen:
     * never so low that the row is cut or faded at the bottom, and never further down than the rows
     * above it reach, so near the start of the list no header or row is cut off above. A list not laid
     * out yet (a panel just made visible) is placed again once it has its height.
     */
    private void select(ListView l, int pos, int top) {
        BaseAdapter a = (BaseAdapter) l.getAdapter();
        if (pos < 0 || pos >= a.getCount()) return;
        if (l.getHeight() == 0) {
            l.setSelection(pos);
            l.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int t, int right, int bottom, int ol, int ot, int or, int ob) {
                    if (bottom - t <= 0) return;
                    l.removeOnLayoutChangeListener(this);
                    l.post(() -> select(l, pos, top));
                }
            });
            return;
        }
        int width = View.MeasureSpec.makeMeasureSpec(l.getWidth(), View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int above = 0;
        for (int i = 0; i < pos && above < top; i++) {
            View v = a.getView(i, null, l);
            v.measure(width, height);
            above += v.getMeasuredHeight();
        }
        View row = a.getView(pos, null, l);
        row.measure(width, height);
        int lowest = l.getHeight() - row.getMeasuredHeight() - l.getVerticalFadingEdgeLength();
        l.setSelectionFromTop(pos, Math.max(0, Math.min(Math.min(top, above), lowest)));
    }

    /** True when the selected row of l is the first (up) or the last row that can be selected. */
    private static boolean atEnd(ListView l, List<Object> r, boolean up) {
        int pos = l.getSelectedItemPosition();
        for (int i = up ? pos - 1 : pos + 1; i >= 0 && i < r.size(); i += up ? -1 : 1) {
            if (!(r.get(i) instanceof String)) return false;
        }
        return true;
    }

    /** Up on the first row selects the last one, Down on the last the first. */
    private boolean wrap(ListView l, List<Object> r, int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        boolean up = keyCode == KeyEvent.KEYCODE_DPAD_UP;
        if (!atEnd(l, r, up)) return false;   // a row to move to: the list does it
        for (int i = up ? r.size() - 1 : 0; i >= 0 && i < r.size(); i += up ? -1 : 1) {
            if (!(r.get(i) instanceof String)) {
                // Going down to the top: show the group header above the first row too.
                select(l, i, up ? Integer.MAX_VALUE : dp(40));
                return true;
            }
        }
        return false;
    }

    /** True on the second of two quick presses of the key towards the panels' edge (not a held key). */
    private boolean doubleLeft(KeyEvent event) {
        if (event.getRepeatCount() > 0) {
            lastLeft = 0;
            return false;
        }
        long now = event.getEventTime();
        boolean twice = now - lastLeft < DOUBLE_MS;
        lastLeft = twice ? 0 : now;
        return twice;
    }

    private void toggleFavorite(int index) {
        ChannelList.Channel c = channels.get(index);
        boolean add = !favorites.remove(c.key);
        if (add) favorites.add(c.key);
        prefs.edit().putString("favorites", new JSONArray(favorites).toString()).apply();
        setChannels(loaded);
        select(list, Math.max(0, rows.indexOf(indexOf(c.key))), dp(140));
        toast(add ? "★  " + c.name + t(" is a favourite", " أضيفت إلى المفضلة")
                : c.name + t(" is no longer a favourite", " أزيلت من المفضلة"));
    }

    private boolean menuOpen() {
        return shown == menu;
    }

    private boolean manageOpen() {
        return shown == manage;
    }

    /**
     * Fades panel in where it stays (or shows it at once in place of the other one). The other one goes at once, even halfway through fading out, so
     * the two never show together.
     *
     * Panels don't slide: on a TV the video is a hardware layer under the window, shown through a hole
     * the window keeps where the SurfaceView is, uncovered by views. The hole is worked out at layout
     * time, so a panel that moves afterwards (a slide) leaves the part it moved onto under the video
     * until something lays the window out again: the panel looked cut at its edge.
     */
    private void show(LinearLayout panel) {
        LinearLayout other = panel == menu ? manage : menu;
        // From the other panel: swap at once, so the video never shows between the two.
        boolean swap = shown == other;
        other.animate().cancel();
        other.setVisibility(View.GONE);
        other.setAlpha(1f);
        shown = panel;
        panel.animate().cancel();
        panel.setVisibility(View.VISIBLE);
        if (swap) {
            panel.setAlpha(1f);
        } else {
            panel.setAlpha(0f);
            panel.animate().alpha(1f).setDuration(160).withEndAction(this::refreshVideoHole).start();
        }
        main.removeCallbacks(hideBar);
        bar.setAlpha(0f);
        // A message ("Loading…", "isn't available") moves to the middle of the video the panel leaves.
        message.setPaddingRelative(dp(424), 0, dp(24), 0);
        refreshVideoHole();
    }

    /** Has the window work out again what the panels cover of the video (see show). */
    private void refreshVideoHole() {
        root.requestTransparentRegion(surface);
        root.requestLayout();
    }

    /** Fades the open panel out; it counts as closed straight away, so the keys go to the video. */
    private void hidePanel() {
        LinearLayout panel = shown;
        shown = null;
        if (panel == null) return;
        message.setPaddingRelative(dp(48), 0, dp(48), 0);
        panel.animate().cancel();
        panel.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> {
                    if (shown != panel) panel.setVisibility(View.GONE);
                    refreshVideoHole();
                }).start();
    }

    private void openManage() {
        show(manage);
        manageAdapter.notifyDataSetChanged();
        manageList.requestFocus();
        // From the top, so the first header and rows show whole.
        select(manageList, Math.max(0, manageRows.indexOf(
                ChannelList.playlists(this).isEmpty() ? Action.ADD_PLAYLIST : Action.LANGUAGE)), Integer.MAX_VALUE);
    }

    private void closeManage() {
        hidePanel();
    }

    private void openMenu() {
        if (channels.isEmpty()) {
            openManage();
            return;
        }
        show(menu);
        adapter.notifyDataSetChanged();
        list.requestFocus();
        select(list, Math.max(0, rows.indexOf(current >= 0 ? current : 0)), dp(140));
    }

    private void closeMenu() {
        if (!query.isEmpty()) search.setText("");   // the next time, the whole list
        search.setVisibility(View.GONE);
        if (menuOpen()) hidePanel();
    }

    /** Selects the first channel of the next (+1) or previous (-1) group. */
    private void jumpGroup(int dir) {
        int pos = list.getSelectedItemPosition();
        if (pos < 0) pos = 0;
        int header = -1;
        if (dir > 0) {
            for (int i = pos + 1; i < rows.size(); i++) if (rows.get(i) instanceof String) { header = i; break; }
        } else {
            int own = pos;
            while (own > 0 && !(rows.get(own) instanceof String)) own--;
            for (int i = own - 1; i >= 0; i--) if (rows.get(i) instanceof String) { header = i; break; }
        }
        if (header >= 0 && header + 1 < rows.size()) select(list, header + 1, dp(60));
    }

    private final class RowAdapter extends BaseAdapter {
        private final List<Object> rows;
        /** Settings rows (section headers in the accent colour) rather than the channel list. */
        private final boolean settings;

        RowAdapter(List<Object> rows, boolean settings) {
            this.rows = rows;
            this.settings = settings;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            Object row = rows.get(position);
            return row instanceof String ? 0 : row instanceof Integer ? 1 : 2;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return !(rows.get(position) instanceof String);
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Object row = rows.get(position);
            if (row instanceof String) {
                TextView h = convert instanceof TextView ? (TextView) convert
                        : settings ? text(12, 700, accent) : text(13, 600, 0x99FFFFFF);
                h.setText(header((String) row).toUpperCase(Locale.ROOT));
                h.setLetterSpacing(settings ? 0.16f : 0.12f);
                h.setPaddingRelative(dp(12), dp(position == 0 ? 8 : settings ? 26 : 18), 0, dp(settings ? 10 : 8));
                return h;
            }
            if (!(row instanceof Integer)) {
                SettingHolder holder;
                if (convert != null && convert.getTag() instanceof SettingHolder) {
                    holder = (SettingHolder) convert.getTag();
                } else {
                    holder = new SettingHolder();
                    convert = holder.build();
                }
                if (row instanceof Action) {
                    Action a = (Action) row;
                    holder.bind(a.icon, t(a.en, a.ar), t(a.subEn, a.subAr));
                } else {
                    ChannelList.Playlist p = (ChannelList.Playlist) row;
                    int n = 0;
                    for (ChannelList.Channel c : channels) if (p.id().equals(c.source)) n++;
                    holder.bind(p.xtream() ? R.drawable.ic_server : R.drawable.ic_playlist, p.name,
                            (p.xtream() ? "Xtream Codes" : "M3U") + "  ·  " + count(n));
                }
                return convert;
            }
            RowHolder holder;
            if (convert != null && convert.getTag() instanceof RowHolder) {
                holder = (RowHolder) convert.getTag();
            } else {
                holder = new RowHolder();
                convert = holder.build();
            }
            holder.bind((Integer) row);
            return convert;
        }
    }

    /** A Settings row: an icon in a tile, a title, and a dimmer line under it. */
    private final class SettingHolder {
        ImageView icon;
        TextView title, sub;

        View build() {
            LinearLayout r = new LinearLayout(MainActivity.this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPaddingRelative(dp(12), dp(10), dp(14), dp(10));
            r.setMinimumHeight(dp(68));
            StateListDrawable tile = new StateListDrawable();
            tile.addState(new int[] {android.R.attr.state_selected}, rounded(0x14000000, 10));
            tile.addState(new int[] {}, rounded(0x1AFFFFFF, 10));
            icon = new ImageView(MainActivity.this);
            icon.setBackground(tile);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageTintList(selectable(0xFF111111, 0xFFFFFFFF));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(42), dp(42));
            ilp.setMarginEnd(dp(16));
            r.addView(icon, ilp);
            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = text(17, 500, 0);
            title.setTextColor(selectable(0xFF111111, 0xFFFFFFFF));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = text(13, 400, 0);
            sub.setTextColor(selectable(0x99111111, 0x80FFFFFF));
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(4), 0, 0);
            texts.addView(title);
            texts.addView(sub);
            r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            r.setTag(this);
            return r;
        }

        void bind(int iconRes, String t, String s) {
            icon.setImageResource(iconRes);
            title.setText(t);
            sub.setText(s);
        }
    }

    private static ColorStateList selectable(int selected, int normal) {
        return new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}}, new int[] {selected, normal});
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(color);
        return d;
    }

    /** number · logo · name, and a dot on the channel that is playing. */
    private final class RowHolder {
        TextView number, name;
        ImageView logo;
        View playing;
        String logoUrl;

        View build() {
            LinearLayout r = new LinearLayout(MainActivity.this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPaddingRelative(dp(12), dp(8), dp(14), dp(8));
            r.setMinimumHeight(dp(56));
            ColorStateList fg = new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}},
                    new int[] {0xFF111111, 0xFFFFFFFF});
            ColorStateList dim = new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}},
                    new int[] {0x99111111, 0x80FFFFFF});
            number = text(14, 500, 0);
            number.setTextColor(dim);
            number.setGravity(Gravity.END);
            r.addView(number, new LinearLayout.LayoutParams(dp(26), -2));
            FrameLayout logoBox = new FrameLayout(MainActivity.this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(0x1FFFFFFF);
            logoBox.setBackground(bg);
            logo = new ImageView(MainActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logoBox.addView(logo, new FrameLayout.LayoutParams(-1, -1));
            logoBox.setPadding(dp(5), dp(4), dp(5), dp(4));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(68), dp(40));
            llp.setMarginStart(dp(12));
            llp.setMarginEnd(dp(14));
            r.addView(logoBox, llp);
            name = text(17, 500, 0);
            name.setTextColor(fg);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            playing = new View(MainActivity.this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(accent);
            playing.setBackground(dot);
            r.addView(playing, new LinearLayout.LayoutParams(dp(9), dp(9)));
            r.setTag(this);
            return r;
        }

        void bind(int index) {
            ChannelList.Channel c = channels.get(index);
            number.setText(String.valueOf(index + 1));
            name.setText(c.name);
            playing.setVisibility(index == current ? View.VISIBLE : View.INVISIBLE);
            if (!c.logo.equals(logoUrl)) {
                logoUrl = c.logo;
                logo.setImageDrawable(null);
                final String want = c.logo;
                loadLogo(want, bmp -> {
                    if (want.equals(logoUrl)) logo.setImageBitmap(bmp);
                });
            }
        }
    }

    // ---- player ---------------------------------------------------------------------------------

    private void buildBar() {
        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(dp(20), dp(16), dp(28), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0xCC0B0D10);
        bar.setBackground(bg);
        barLogo = new ImageView(this);
        barLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bar.addView(barLogo, new LinearLayout.LayoutParams(dp(96), dp(54)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPaddingRelative(dp(18), 0, 0, 0);
        barName = text(24, 500, 0xFFFFFFFF);
        barGroup = text(15, 400, 0xA6FFFFFF);
        texts.addView(barName);
        texts.addView(barGroup);
        bar.addView(texts);
        bar.setAlpha(0f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        blp.setMarginStart(dp(48));
        blp.bottomMargin = dp(40);
        root.addView(bar, blp);
    }

    private void play(int index) {
        if (channels.isEmpty()) return;
        index = (index % channels.size() + channels.size()) % channels.size();
        current = index;
        final ChannelList.Channel c = channels.get(index);
        prefs.edit().putString("last", c.key).apply();
        adapter.notifyDataSetChanged();
        message.setText(t("Loading…", "جارٍ التحميل…"));
        message.setVisibility(View.VISIBLE);
        if (!menuOpen()) showBar(c, index);

        if (player == null) return;
        // The data sources made for the new item take the channel's own headers.
        Map<String, String> headers = new HashMap<>();
        if (c.referrer != null) headers.put("Referer", c.referrer);
        http.setUserAgent(c.userAgent != null ? c.userAgent : DEFAULT_UA).setDefaultRequestProperties(headers);
        final ExoPlayer p = player;
        final int seq = ++playSeq;
        // Links without .m3u8, .mpd or .ts: look at the first bytes to tell DASH, HLS and a plain stream
        // (MPEG-TS, as Xtream Codes servers send) apart, once per link.
        String lower = c.url.toLowerCase(Locale.ROOT);
        String known = mimes.get(c.url);
        if (lower.contains(".m3u8") || lower.contains(".mpd") || lower.endsWith(".ts") || known != null) {
            start(p, c.url, known == null || known.isEmpty() ? null : known);
        } else {
            p.stop();
            io.execute(() -> {
                String mime = MimeTypes.APPLICATION_M3U8;
                try {
                    // DASH manifests are XML; HLS playlists start with #EXTM3U.
                    String head = new String(ChannelList.fetch(c.url, 512), StandardCharsets.ISO_8859_1).trim();
                    if (head.startsWith("<")) mime = MimeTypes.APPLICATION_MPD;
                    else if (!head.startsWith("#EXTM3U")) mime = "";
                } catch (Exception ignored) {
                }
                final String m = mime;
                main.post(() -> {
                    mimes.put(c.url, m);
                    if (player == p && playSeq == seq) start(p, c.url, m.isEmpty() ? null : m);
                });
            });
        }
    }

    private static void start(ExoPlayer p, String url, String mime) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url))
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET).build());
        if (mime != null) item.setMimeType(mime);
        p.setMediaItem(item.build());
        p.prepare();
        p.setPlayWhenReady(true);
    }

    /** Letterbox the video in the screen at its own aspect ratio. */
    private void fitSurface(VideoSize size) {
        if (size.width == 0 || size.height == 0) return;
        float aspect = size.width * size.pixelWidthHeightRatio / size.height;
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        int vw = w, vh = Math.round(w / aspect);
        if (vh > h) {
            vh = h;
            vw = Math.round(h * aspect);
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    private void showBar(ChannelList.Channel c, int index) {
        barName.setText(c.name);
        barGroup.setText((index + 1) + "  ·  " + c.group);
        barLogo.setImageDrawable(null);
        loadLogo(c.logo, barLogo::setImageBitmap);
        bar.animate().cancel();
        bar.setAlpha(1f);
        main.removeCallbacks(hideBar);
        main.postDelayed(hideBar, BAR_MS);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (manageOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // Back to the channels, the way Settings was opened from them.
                if (channels.isEmpty()) hidePanel();
                else openMenu();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (menuOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeMenu();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                openMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (current < 0) return true;
                play(current - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (current < 0) return true;
                play(current + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (keyCode == outKey() && doubleLeft(event)) {
                    openManage();
                    return true;
                }
                if (current >= 0) showBar(channels.get(current), current);
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (current >= 0) showBar(channels.get(current), current);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ---- my list: editing the channels ---------------------------------------------------------

    private void run(Action a) {
        switch (a) {
            case LANGUAGE:
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(t("Language", "اللغة"))
                        .setSingleChoiceItems(new String[] {"English", "العربية"}, arabic ? 1 : 0, (d, which) -> {
                            d.dismiss();
                            if ((which == 1) == arabic) return;
                            prefs.edit().putString("language", which == 1 ? "ar" : "en").apply();
                            recreate();
                        })
                        .show();
                break;
            case ADD:
                editChannel(null);
                break;
            case ADD_PLAYLIST:
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(t("Add a playlist", "إضافة قائمة تشغيل"))
                        .setItems(new String[] {t("M3U link", "رابط M3U"), "Xtream Codes"},
                                (d, which) -> editPlaylist(null, which == 1))
                        .show();
                break;
            case RELOAD:
                reload(true);
                break;
            case RESET:
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(t("Undo my changes?", "التراجع عن تغييراتي؟"))
                        .setMessage(t("The channels you added, changed or removed go back to the playlists as they are.",
                                "تعود القنوات التي أضفتها أو عدّلتها أو حذفتها كما هي في القوائم."))
                        .setPositiveButton(t("Undo", "تراجع"), (d, w) -> {
                            ChannelList.resetEdits(this);
                            setChannels(ChannelList.load(this));
                        })
                        .setNegativeButton(t("Cancel", "إلغاء"), null)
                        .show();
                break;
        }
    }

    private void playlistOptions(ChannelList.Playlist p) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(p.name)
                .setItems(new String[] {t("Edit", "تعديل"), t("Reload", "إعادة تحميل"), t("Remove", "حذف")}, (d, which) -> {
                    if (which == 0) {
                        editPlaylist(p, p.xtream());
                    } else if (which == 1) {
                        reload(true);
                    } else {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(t("Remove ", "حذف ") + p.name + t("?", "؟"))
                                .setMessage(t("Its channels go too. You can add it again later.",
                                        "ستُحذف قنواتها أيضاً. يمكنك إضافتها من جديد لاحقاً."))
                                .setPositiveButton(t("Remove", "حذف"), (d2, w) -> {
                                    ChannelList.removePlaylist(this, p.id());
                                    setChannels(ChannelList.load(this));
                                    toast(p.name + t(" removed", " حُذفت"));
                                })
                                .setNegativeButton(t("Cancel", "إلغاء"), null)
                                .show();
                    }
                })
                .show();
    }

    /**
     * Changes p, or adds a playlist when p is null: an M3U link, or an Xtream Codes account (server,
     * username, password). It must load to be saved.
     */
    private void editPlaylist(ChannelList.Playlist p, boolean xtream) {
        EditText name = field(t("Name", "الاسم"), p == null ? "" : p.name, InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText url = field(xtream ? t("Server, like http://host:port", "الخادم، مثل http://host:port")
                : t("Link of the .m3u playlist", "رابط قائمة .m3u"), p == null ? "" : p.url, InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = field(t("Username", "اسم المستخدم"), p == null || p.user == null ? "" : p.user, 0);
        EditText pass = field(t("Password", "كلمة المرور"), p == null || p.pass == null ? "" : p.pass,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        String intro = xtream
                ? t("The live channels of your Xtream Codes account.", "القنوات المباشرة لحساب Xtream Codes الخاص بك.")
                : t("An M3U playlist.", "قائمة تشغيل M3U.");
        intro += t(" They are loaded again each time the app starts; your own changes to them stay.",
                " تُحمَّل من جديد عند كل تشغيل للتطبيق، وتبقى تغييراتك عليها.");
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(p == null ? (xtream ? "Xtream Codes" : t("Add a playlist", "إضافة قائمة تشغيل"))
                        : t("Edit ", "تعديل ") + p.name)
                .setMessage(intro)
                .setView(xtream ? form(name, url, user, pass) : form(name, url))
                .setPositiveButton(p == null ? t("Add", "إضافة") : t("Save", "حفظ"), null)
                .setNegativeButton(t("Cancel", "إلغاء"), null)
                .show();
        // Set here rather than in the builder so a mistake keeps the dialog open.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            ChannelList.Playlist np;
            if (xtream) {
                if (u.isEmpty()) {
                    url.setError(t("The server", "الخادم مطلوب"));
                    return;
                }
                if (user.getText().toString().trim().isEmpty()) {
                    user.setError(t("The username", "اسم المستخدم مطلوب"));
                    return;
                }
                np = new ChannelList.Playlist("", ChannelList.server(u));
                np.user = user.getText().toString().trim();
                np.pass = pass.getText().toString().trim();
            } else {
                if (!u.startsWith("http://") && !u.startsWith("https://")) {
                    url.setError(t("A link starting with http:// or https://", "رابط يبدأ بـ http:// أو https://"));
                    return;
                }
                np = new ChannelList.Playlist("", u);
            }
            String n = name.getText().toString().trim();
            if (n.isEmpty()) n = xtream ? Uri.parse(np.url).getHost() : Uri.parse(u).getLastPathSegment();
            np.name = n == null || n.isEmpty() ? "Playlist" : n;
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            d.setMessage(t("Loading…", "جارٍ التحميل…"));
            io.execute(() -> {
                int count = ChannelList.addPlaylist(this, np, p == null ? null : p.id());
                main.post(() -> {
                    if (isDestroyed()) return;
                    if (count < 0) {
                        d.setMessage(xtream
                                ? t("No channels could be loaded: check the server, username and password.",
                                        "تعذر تحميل القنوات: تحقق من الخادم واسم المستخدم وكلمة المرور.")
                                : t("No channels could be loaded from that link. Check it and try again.",
                                        "تعذر تحميل قنوات من هذا الرابط. تحقق منه وحاول مرة أخرى."));
                        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        return;
                    }
                    d.dismiss();
                    setChannels(ChannelList.load(this));
                    toast(np.name + ": " + count(count));
                    playIfIdle();
                });
            });
        });
    }

    private void channelOptions(int index) {
        ChannelList.Channel c = channels.get(index);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(c.name)
                .setItems(new String[] {t("Edit", "تعديل"), t("Remove", "حذف")}, (d, which) -> {
                    if (which == 0) {
                        editChannel(c);
                    } else {
                        ChannelList.remove(this, c);
                        setChannels(ChannelList.load(this));
                        toast(c.name + t(" removed", " حُذفت"));
                    }
                })
                .show();
    }

    /** Edits c, or adds a channel when c is null. */
    private void editChannel(ChannelList.Channel c) {
        EditText name = field(t("Name", "الاسم"), c == null ? "" : c.name, InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText url = field(t("Stream link (.m3u8, .mpd, .ts)", "رابط البث (.m3u8، .mpd، .ts)"), c == null ? "" : c.url,
                InputType.TYPE_TEXT_VARIATION_URI);
        EditText group = field(t("Group", "المجموعة"), c == null ? "My channels" : c.group, InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText logo = field(t("Logo link (optional)", "رابط الشعار (اختياري)"), c == null ? "" : c.logo,
                InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(c == null ? t("Add a channel", "إضافة قناة") : t("Edit ", "تعديل ") + c.name)
                .setView(form(name, url, group, logo))
                .setPositiveButton(t("Save", "حفظ"), null)
                .setNegativeButton(t("Cancel", "إلغاء"), null)
                .show();
        // Set here rather than in the builder so a missing name or link keeps the dialog open.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim(), u = url.getText().toString().trim();
            if (n.isEmpty()) {
                name.setError(t("A name", "الاسم مطلوب"));
                return;
            }
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                url.setError(t("A link starting with http:// or https://", "رابط يبدأ بـ http:// أو https://"));
                return;
            }
            String g = group.getText().toString().trim();
            ChannelList.Channel e = c != null ? c : new ChannelList.Channel("", n, g, "", u, null, null);
            e.name = n;
            e.url = u;
            e.group = g.isEmpty() ? "My channels" : g;
            e.logo = logo.getText().toString().trim();
            ChannelList.save(this, e);
            d.dismiss();
            setChannels(ChannelList.load(this));
            if (c == null) {
                play(indexOf(e.key));
                closeManage();
            }
        });
    }

    private EditText field(String hint, String value, int variation) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setText(value);
        f.setSingleLine(true);
        f.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        return f;
    }

    private View form(View... fields) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(8), dp(24), 0);
        for (View f : fields) l.addView(f, new LinearLayout.LayoutParams(-1, -2));
        return l;
    }

    /** The app's own words in the chosen language. */
    private String t(String en, String ar) {
        return arabic ? ar : en;
    }

    private String count(int n) {
        return t(n == 1 ? "1 channel" : n + " channels", n + " قناة");
    }

    /** A group header: the app's own groups in the chosen language, a playlist's groups as they are. */
    private String header(String group) {
        switch (group) {
            case FAVORITES: return t("Favorites", "المفضلة");
            case GENERAL: return t("General", "عام");
            case PLAYLISTS: return t("Playlists", "قوائم التشغيل");
            case MY_LIST: return t("My channels", "قنواتي");
            default: return group;
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ---- logos ----------------------------------------------------------------------------------

    private interface BitmapCallback {
        void onBitmap(Bitmap bmp);
    }

    /** From memory, then the cache directory, then the network. */
    private void loadLogo(String url, BitmapCallback cb) {
        if (url == null || url.isEmpty()) return;
        Bitmap hit = logos.get(url);
        if (hit != null) {
            cb.onBitmap(hit);
            return;
        }
        io.execute(() -> {
            File dir = new File(getCacheDir(), "logos");
            if (!dir.isDirectory()) dir.mkdirs();
            File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
            try {
                if (!f.isFile()) {
                    byte[] data = ChannelList.fetch(url);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data);
                    }
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= 240) o.inSampleSize *= 2;
                Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
                if (bmp == null) {
                    f.delete();
                    return;
                }
                main.post(() -> {
                    logos.put(url, bmp);
                    cb.onBitmap(bmp);
                });
            } catch (Exception e) {
                Log.w(TAG, "logo " + url + ": " + e);
            }
        });
    }

    // ---- helpers --------------------------------------------------------------------------------

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setIncludeFontPadding(false);
        // To the layout's start, right in Arabic, even for a Latin channel name.
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
