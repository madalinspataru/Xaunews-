package it.xaunews.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

public class MainActivity extends Activity {
    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private TextView selected, countdown, alarmStatus, status;
    private LinearLayout events;
    private Button refresh;
    private long eventTime;
    private boolean destroyed;
    private boolean updating;

    private static class Event {
        String name, source;
        long time;

        Event(String name, long time, String source) {
            this.name = name;
            this.time = time;
            this.source = source;
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long seconds =
                (eventTime - System.currentTimeMillis()) / 1000;

            countdown.setText(eventTime == 0
                ? "Tocca una news per vedere il countdown"
                : seconds <= 0
                    ? "Orario evento raggiunto"
                    : String.format(
                        Locale.ITALY,
                        "Mancano %d giorni - %02d:%02d:%02d",
                        seconds / 86400,
                        (seconds / 3600) % 24,
                        (seconds / 60) % 60,
                        seconds % 60));

            handler.postDelayed(this, 1000);
        }
    };

    private TextView text(
        LinearLayout parent, String value, int size
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setPadding(0, 16, 0, 16);
        parent.addView(view);
        return view;
    }

    private EditText input(LinearLayout parent, String hint) {
        EditText view = new EditText(this);
        view.setTextColor(Color.WHITE);
        view.setHintTextColor(Color.LTGRAY);
        view.setHint(hint);
        parent.addView(view);
        return view;
    }

    private void select(String name, long time) {
        eventTime = time;
        selected.setText(name);
        getPreferences(0).edit()
            .putString("name", name)
            .putLong("time", time)
            .apply();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 48);
        root.setBackgroundColor(Color.rgb(14, 22, 35));
        scroll.addView(root);
        setContentView(scroll);

        text(root, "XAU NEWS", 30);
        text(root, "Calendario automatico USA", 20);
        text(root,
            "Fuso Android: " + ZoneId.systemDefault(), 14);

        selected = text(root,
            getPreferences(0).getString(
                "name", "Nessun evento selezionato"), 22);
        eventTime = getPreferences(0).getLong("time", 0);
        countdown = text(root, "", 22);

        NewsAlarmReceiver.channel(this);
        alarmStatus = text(root, "", 15);

        Button enable = new Button(this);
        enable.setText("Abilita avvisi per tutte le news");
        root.addView(enable);
        enable.setOnClickListener(view -> enableAlerts());

        Button test = new Button(this);
        test.setText("Prova avviso tra 1 minuto");
        root.addView(test
