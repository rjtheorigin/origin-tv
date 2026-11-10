package io.github.rjtheorigin.liveindiantv;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/**
 * Lets the launcher's phone page use the stream server while Live Indian TV is closed. Binding starts the server, and
 * the last client leaving stops it. Only an app signed with the same key can bind.
 */
public class ServerService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        Server.setHeld(true);
        new Thread(() -> {
            try {
                Server.ensure(this);
            } catch (IOException e) {
                Log.w("Channels", "server: " + e.getMessage());
            }
        }, "start-server").start();
        return new Binder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Server.setHeld(false);
        Server.stopIfIdle();
        return false;
    }
}
