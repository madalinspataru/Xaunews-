package it.xaunews.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.widget.*;
import java.time.*;
import java.time.format.*;
import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private TextView countdown;
    private TextView saved;
    private long eventTime;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (eventTime > 0) {
                long seconds =
                    (eventTime - System.currentTimeMillis()) / 1000;

                countdown.setText(seconds <= 0
                    ? "Orario evento raggiunto"
                    : String.format(
                        Locale.ITALY,
                        "Mancano %d giorni • %02d:%02d:%02d",
                        seconds / 86400,
                        (seconds / 3600) % 24,
                        (seconds / 60) % 60,
                        seconds % 60));
            } else {
                countdown.setText("Inserisci il prossimo evento");
            }
            handler.postDelayed(this, 1000);
        }
    };

    private TextView text(
        LinearLayout layout, String value, int size
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setPadding(0, 16, 0, 16);
        layout.addView(view);
        return view;
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
        text(root, "Eventi inseriti manualmente", 18);
        text(root,
            "Calendario live e modello AI non ancora collegati.",
            16);

        saved = text(root, "", 22);
        countdown = text(root, "", 22);

        eventTime = getPreferences(0).getLong("time", 0);
        saved.setText(getPreferences(0).getString(
            "name", "Nessun evento"));

        EditText name = new EditText(this);
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(Color.LTGRAY);
        name.setHint("Nome evento, es. CPI USA");
        root.addView(name);

        EditText date = new EditText(this);
        date.setTextColor(Color.WHITE);
        date.setHintTextColor(Color.LTGRAY);
        date.setHint("Data e ora: AAAA-MM-GG HH:MM");
        root.addView(date);

        text(root,
            "Fuso del tablet: " + ZoneId.systemDefault()
            + ". Inserisci l'orario in questo fuso.",
            14);

        Button save = new Button(this);
