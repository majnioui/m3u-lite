package com.majnioui.m3ulite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Adds a playlist from a computer, without opening the app on the TV:
 *
 *   adb shell am broadcast -n com.majnioui.m3ulite/.AddPlaylist --es url URL [--es name NAME]
 *
 * or an Xtream Codes account, with the server as the url:
 *
 *   adb shell am broadcast -n com.majnioui.m3ulite/.AddPlaylist --es url http://host:port \
 *       --es user USER --es pass PASS [--es name NAME]
 *
 * Only the ADB shell can send it: the receiver asks for the DUMP permission, which apps can't get.
 */
public class AddPlaylist extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String url = intent.getStringExtra("url");
        String user = intent.getStringExtra("user");
        if (url == null || (user == null && !(url.startsWith("http://") || url.startsWith("https://")))) {
            setResult(1, "an http(s) url is needed: --es url URL", null);
            return;
        }
        String name = intent.getStringExtra("name");
        if (name == null || name.trim().isEmpty()) name = "Playlist";
        ChannelList.Playlist p = new ChannelList.Playlist(name.trim(), user == null ? url : ChannelList.server(url));
        p.user = user;
        p.pass = intent.getStringExtra("pass") == null ? "" : intent.getStringExtra("pass");
        final PendingResult result = goAsync();
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            int count = ChannelList.addPlaylist(app, p, null);
            String msg = count < 0 ? "no channels could be loaded from " + p.url : p.name + ": " + count + " channels";
            Log.i("Channels", "add playlist: " + msg);
            result.setResult(count < 0 ? 1 : 0, msg, null);
            result.finish();
        }).start();
    }
}
