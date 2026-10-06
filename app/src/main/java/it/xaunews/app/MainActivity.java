package it.xaunews.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

public class MainActivity extends Activity {
    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private TextView countdown, selected, status;
    private LinearLayout events;
    private Button refresh;
    private long eventTime;
    private boolean destroyed;

    private static final String CALENDAR =
        "https://www.bls.gov/schedule/news_release/bls.ics";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long seconds =
                (eventTime - System.currentTimeMillis()) / 1000;

            if (eventTime == 0) {
                countdown.setText("Seleziona o inserisci un evento");
            } else if (seconds <= 0) {
                countdown.setText("Orario evento raggiunto");
            } else {
                countdown.setText(String.format(
                    Locale.ITALY,
                    "Mancano %d giorni - %02d:%02d:%02d",
                    seconds / 86400,
                    (seconds / 3600) % 24,
                    (seconds / 60) % 60,
                    seconds % 60));
            }
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
        text(root, "Calendario CPI e NFP - fonte BLS", 18);
        text(root,
            "Orari nel fuso del tablet: "
            + ZoneId.systemDefault(), 14);

        selected = text(root,
            getPreferences(0).getString("name", "Nessun evento"),
            22);
        eventTime = getPreferences(0).getLong("time", 0);
        countdown = text(root, "", 22);

        refresh = new Button(this);
        refresh.setText("Aggiorna news");
        root.addView(refresh);

        status = text(root,
            "Premi Aggiorna news per scaricare il calendario.",
            14);

        events = new LinearLayout(this);
        events.setOrientation(LinearLayout.VERTICAL);
        root.addView(events);

        refresh.setOnClickListener(view -> download());

        String cached = getPreferences(0).getString("calendar", "");
        if (!cached.isEmpty()) {
            try {
                showEvents(parse(cached));
                status.setText("Calendario salvato - ultimo download: "
                    + getPreferences(0).getString("updated", "")
                    + ". Premi Aggiorna news per verificarlo.");
            } catch (Exception error) {
                status.setText(
                    "Copia salvata non leggibile. Aggiorna le news.");
            }
        }

        text(root, "Inserimento manuale", 20);
        EditText name = input(root, "Nome evento");
        EditText date = input(root, "AAAA-MM-GG HH:MM");

        Button save = new Button(this);
        save.setText("Salva evento manuale");
        root.addView(save);

        save.setOnClickListener(view -> {
            try {
                String label = name.getText().toString().trim();
                if (label.isEmpty()) {
                    throw new IllegalArgumentException();
                }

                LocalDateTime time = LocalDateTime.parse(
                    date.getText().toString().trim(),
                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                        .withResolverStyle(ResolverStyle.STRICT));

                select(label, time.atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli());
            } catch (Exception error) {
                Toast.makeText(this,
                    "Inserisci nome e data: AAAA-MM-GG HH:MM",
                    Toast.LENGTH_LONG).show();
            }
        });

        text(root, "Segnale XAU/USD: NON DISPONIBILE", 20);
        text(root,
            "Questo calendario mostra date e orari, "
            + "non consenso o valori pubblicati. "
            + "AI e notifiche non ancora disponibili. "
            + "Countdown attivo a schermo aperto.",
            14);
    }

    private static class Event {
        String name;
        long time;

        Event(String name, long time) {
            this.name = name;
            this.time = time;
        }
    }

    private long parseTime(String property, String value) {
        if (value.length() < 15) {
            throw new IllegalArgumentException("Orario assente");
        }

        LocalDateTime local = LocalDateTime.parse(
            value.substring(0, 15),
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
                .withResolverStyle(ResolverStyle.STRICT));

        if (value.endsWith("Z")) {
            return local.toInstant(ZoneOffset.UTC).toEpochMilli();
        }

        int index = property.indexOf("TZID=");
        if (index < 0) {
            throw new IllegalArgumentException("Fuso assente");
        }

        String zone = property.substring(index + 5)
            .split(";")[0].replace("\"", "");

        if (zone.startsWith("/")) {
            int america = zone.indexOf("America/");
            if (america >= 0) {
                zone = zone.substring(america);
            }
        }

        return local.atZone(ZoneId.of(zone))
            .toInstant().toEpochMilli();
    }

    private List<Event> parse(String calendar) {
        if (!calendar.contains("BEGIN:VCALENDAR")
            ||
