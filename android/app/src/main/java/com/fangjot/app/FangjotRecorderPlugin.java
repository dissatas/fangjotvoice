package com.fangjot.app;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.List;

/** JS bridge for RecorderService: start/stop background recording and drain recognised phrases. */
@CapacitorPlugin(
    name = "FangjotRecorder",
    permissions = {
        @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class FangjotRecorderPlugin extends Plugin {

    @Override
    public void load() {
        RecorderService.onChange = () -> {
            try { getBridge().executeOnMainThread(() -> notifyListeners("change", new JSObject())); } catch (Exception ignored) { }
        };
    }

    @PluginMethod
    public void requestAll(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED
            && (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED)) {
            permsDone(call);
            return;
        }
        String[] aliases = Build.VERSION.SDK_INT >= 33 ? new String[] { "microphone", "notifications" } : new String[] { "microphone" };
        requestPermissionForAliases(aliases, call, "permsDone");
    }

    @PermissionCallback
    private void permsDone(PluginCall call) {
        JSObject r = new JSObject();
        r.put("microphone", getPermissionState("microphone") == PermissionState.GRANTED);
        r.put("notifications", Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(r);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("ต้องอนุญาตไมโครโฟนก่อน", "permission");
            return;
        }
        Intent i = new Intent(getContext(), RecorderService.class).setAction(RecorderService.ACTION_START);
        i.putExtra("language", call.getString("language", "th-TH"));
        try {
            ContextCompat.startForegroundService(getContext(), i);
            call.resolve();
        } catch (Exception e) {
            call.reject("เริ่มบริการบันทึกไม่สำเร็จ: " + e.getMessage(), "start_failed");
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (RecorderService.isRunning()) {
            Intent i = new Intent(getContext(), RecorderService.class).setAction(RecorderService.ACTION_STOP);
            getContext().startService(i);
        }
        call.resolve();
    }

    @PluginMethod
    public void drain(PluginCall call) {
        List<RecorderService.Phrase> list = RecorderService.drain();
        JSArray arr = new JSArray();
        for (RecorderService.Phrase p : list) {
            JSObject o = new JSObject();
            o.put("text", p.text);
            o.put("at", p.at);
            arr.put(o);
        }
        JSObject r = new JSObject();
        r.put("finals", arr);
        r.put("partial", RecorderService.getPartial());
        r.put("running", RecorderService.isRunning());
        r.put("startedAt", RecorderService.getStartedAt());
        r.put("error", RecorderService.takeError());
        call.resolve(r);
    }
}
