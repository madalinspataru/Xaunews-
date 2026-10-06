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
        root.addView(new XauPriceView(this));

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
        root.addView(test);
        test.setOnClickListener(view ->
            alarmStatus.setText(NewsAlarmReceiver.test(this)));

        Button disable = new Button(this);
        disable.setText("Disattiva tutti gli avvisi");
        root.addView(disable);
        disable.setOnClickListener(view -> {
            getPreferences(0).edit()
                .putBoolean("alerts", false)
                .apply();
            NewsAlarmReceiver.cancel(this);
            alarmStatus.setText("Avvisi automatici disattivati");
        });

        refresh = new Button(this);
        refresh.setText("Aggiorna calendario ora");
        root.addView(refresh);
        refresh.setOnClickListener(view -> update());

        status = text(root, "", 14);
        events = new LinearLayout(this);
        events.setOrientation(LinearLayout.VERTICAL);
        root.addView(events);

        showSaved();

        text(root, "Countdown manuale", 20);
        text(root,
            "Questa sezione crea solo un countdown. "
            + "Gli avvisi automatici riguardano il calendario.",
            14);

        EditText name = input(root, "Nome evento");
        EditText date = input(root, "AAAA-MM-GG HH:MM");

        Button save = new Button(this);
        save.setText("Salva countdown manuale");
        root.addView(save);

        save.setOnClickListener(view -> {
            try {
                String label = name.getText().toString().trim();
                if (label.isEmpty())
                    throw new IllegalArgumentException();

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

        Button analysis = new Button(this);
        analysis.setText("Analizza CPI / NFP e misura il movimento");
        root.addView(analysis);
        analysis.setOnClickListener(view -> {
            Intent intent = new Intent(this, NewsAnalysisActivity.class);
            intent.putExtra("event_name", selected.getText().toString());
            startActivity(intent);
        });
        text(root, "Analisi disponibile dopo la pubblicazione", 20);
        text(root,
            "Calendario aggiornato all'apertura e periodicamente "
            + "in background. Prezzo esterno aggiornato mentre "
            + "l'app e visibile, con almeno 30 secondi tra richieste. "
            + "Avvisi 5 minuti prima delle news caricate. "
            + "ISM puo usare la copia di riserva; "
            + "orari standard Fed da confermare. "
            + "Modello AI non ancora addestrato.",
            14);

        NewsSyncWorker.install(getApplicationContext());
    }

    private List<Event> savedEvents() {
        List<Event> list = new ArrayList<>();

        for (String source : new String[]{"BLS", "BEA", "FED", "ISM"}) {
            try {
                JSONArray array = new JSONArray(
                    getPreferences(0).getString(
                        "events_" + source, "[]"));

                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    list.add(new Event(
                        item.getString("name"),
                        item.getLong("time"),
                        item.getString("source")));
                }
            } catch (JSONException ignored) {
            }
        }
        return list;
    }

    private void showSaved() {
        List<Event> list = savedEvents();
        list.sort((a, b) -> Long.compare(a.time, b.time));
        events.removeAllViews();

        DateTimeFormatter format =
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
                .withZone(ZoneId.systemDefault());

        Set<String> seen = new HashSet<>();
        int count = 0;

        for (Event event : list) {
            if (event.time <= System.currentTimeMillis())
                continue;
            if (!seen.add(event.name + event.time))
                continue;

            Button button = new Button(this);
            button.setText(event.name + "\n"
                + format.format(Instant.ofEpochMilli(event.time))
                + " - " + event.source);
            button.setOnClickListener(view ->
                select(event.name, event.time));
            events.addView(button);
            count++;
        }

        if (count == 0) {
            text(events,
                "Nessuna news futura disponibile nella copia salvata.",
                15);
        }

        StringBuilder report = new StringBuilder();
        report.append("Ultime copie disponibili:\n");
        for (String source : new String[]{"BLS", "BEA", "FED", "ISM"}) {
            report.append(source).append(": ")
                .append(getPreferences(0).getString(
                    "updated_" + source, "non scaricato"))
                .append('\n');
        }

        String lastReport =
            getPreferences(0).getString("sync_report", "");
        if (!lastReport.isEmpty()) {
            report.append("\nUltimo tentativo:\n")
                .append(lastReport);
        }

        status.setText(report.toString());
    }

    private void update() {
        if (updating || destroyed) return;
        updating = true;
        refresh.setEnabled(false);
        status.setText("Aggiornamento automatico delle news...");

        final android.content.Context context =
            getApplicationContext();

        new Thread(() -> {
            try {
                NewsSyncWorker.refresh(context);
            } finally {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    updating = false;
                    refresh.setEnabled(true);
                    showSaved();
                    alarmStatus.setText(
                        NewsAlarmReceiver.schedule(this));
                });
            }
        }).start();
    }

    private void enableAlerts() {
        getPreferences(0).edit()
            .putBoolean("alerts", true)
            .apply();

        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                71);
            return;
        }

        if (!NewsAlarmReceiver.notifications(this)) {
            Intent intent = new Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(
                Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
            return;
        }

        if (Build.VERSION.SDK_INT >= 31
            && !NewsAlarmReceiver.exact(this)) {
            Intent intent = new Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException error) {
                alarmStatus.setText(
                    "Apri le impostazioni Android: Sveglie e promemoria");
            }
            return;
        }

        alarmStatus.setText(NewsAlarmReceiver.schedule(this));
    }

    @Override
    public void onRequestPermissionsResult(
        int request, String[] permissions, int[] results
    ) {
        super.onRequestPermissionsResult(
            request, permissions, results);

        if (request == 71) {
            if (results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
                enableAlerts();
            } else {
                alarmStatus.setText(
                    "Notifiche non autorizzate. Premi Abilita per riprovare.");
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        handler.post(ticker);
        showSaved();
        alarmStatus.setText(NewsAlarmReceiver.schedule(this));
        update();
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }
}
// FINE FILE
