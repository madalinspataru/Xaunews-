package it.xaunews.app;

import android.app.Activity;
import android.os.*;
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
    private TextView selected, countdown, status;
    private LinearLayout events;
    private Button refresh;
    private long eventTime;
    private boolean destroyed;

    private final Runnable ticker = new Runnable() {
        public void run() {
            long s = (eventTime - System.currentTimeMillis()) / 1000;
            countdown.setText(eventTime == 0
                ? "Seleziona un evento"
                : s <= 0 ? "Orario evento raggiunto"
                : String.format(Locale.ITALY,
                    "Mancano %d giorni - %02d:%02d:%02d",
                    s / 86400, (s / 3600) % 24,
                    (s / 60) % 60, s % 60));
            handler.postDelayed(this, 1000);
        }
    };

    private TextView text(LinearLayout root, String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, 16, 0, 16);
        root.addView(t);
        return t;
    }

    private EditText input(LinearLayout root, String hint) {
        EditText e = new EditText(this);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.LTGRAY);
        e.setHint(hint);
        root.addView(e);
        return e;
    }

    private void select(String name, long time) {
        eventTime = time;
        selected.setText(name);
        getPreferences(0).edit().putString("name", name)
            .putLong("time", time).apply();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(32, 48, 32, 48);
        root.setBackgroundColor(Color.rgb(14, 22, 35));
        scroll.addView(root);
        setContentView(scroll);

        text(root, "XAU NEWS", 30);
        text(root, "CPI e NFP - calendario ufficiale BLS", 18);
        text(root, "Fuso: " + ZoneId.systemDefault(), 14);
        selected = text(root,
            getPreferences(0).getString("name", "Nessun evento"), 22);
        eventTime = getPreferences(0).getLong("time", 0);
        countdown = text(root, "", 22);

        refresh = new Button(this);
        refresh.setText("Aggiorna news");
        root.addView(refresh);
        status = text(root, "Premi Aggiorna news.", 14);
        events = new LinearLayout(this);
        events.setOrientation(1);
        root.addView(events);
        refresh.setOnClickListener(v -> download());

        String cache = getPreferences(0).getString("calendar", "");
        if (!cache.isEmpty()) {
            try {
                show(parse(cache));
                status.setText("Copia salvata: "
                    + getPreferences(0).getString("updated", "")
                    + ". Aggiorna per verificarla.");
            } catch (Exception e) {
                status.setText("Copia non leggibile. Aggiorna.");
            }
        }

        text(root, "Inserimento manuale", 20);
        EditText name = input(root, "Nome evento");
        EditText date = input(root, "AAAA-MM-GG HH:MM");
        Button save = new Button(this);
        save.setText("Salva evento manuale");
        root.addView(save);
        save.setOnClickListener(v -> {
            try {
                String label = name.getText().toString().trim();
                if (label.isEmpty()) throw new IllegalArgumentException();
                LocalDateTime dt = LocalDateTime.parse(
                    date.getText().toString().trim(),
                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                        .withResolverStyle(ResolverStyle.STRICT));
                select(label, dt.atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli());
            } catch (Exception e) {
                Toast.makeText(this,
                    "Inserisci nome e data: AAAA-MM-GG HH:MM",
                    Toast.LENGTH_LONG).show();
            }
        });
        text(root, "Segnale XAU/USD: NON DISPONIBILE", 20);
        text(root,
            "Solo date e orari. Consenso, dati pubblicati, AI e "
            + "notifiche non disponibili. Countdown a schermo aperto.",
            14);
    }

    private static class Event {
        String name;
        long time;
        Event(String n, long t) {
            name = n;
            time = t;
        }
    }

    private long timestamp(String property, String value) {
        if (value.length() < 15)
            throw new IllegalArgumentException("Orario assente");

        LocalDateTime dt = LocalDateTime.parse(
            value.substring(0, 15),
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
                .withResolverStyle(ResolverStyle.STRICT));

        if (value.endsWith("Z"))
            return dt.toInstant(ZoneOffset.UTC).toEpochMilli();

        int p = property.indexOf("TZID=");
        if (p < 0)
            throw new IllegalArgumentException("Fuso assente");

        String zone = property.substring(p + 5)
            .split(";")[0].replace("\"", "").trim();

        if (zone.equalsIgnoreCase("US-Eastern")
            || zone.equalsIgnoreCase("US/Eastern")) {
            zone = "America/New_York";
        }

        int a = zone.indexOf("America/");
        if (a >= 0) zone = zone.substring(a);

        return dt.atZone(ZoneId.of(zone))
            .toInstant().toEpochMilli();
    }

    private List<Event> parse(String data) {
        if (!data.contains("BEGIN:VCALENDAR")
            || !data.contains("END:VCALENDAR"))
            throw new IllegalArgumentException("Calendario non valido");

        String unfolded = data.replace("\r\n", "\n")
            .replaceAll("\n[ \t]", "");
        List<Event> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String name = null;
        long time = 0;
        boolean inside = false;

        for (String line : unfolded.split("\n")) {
            if (line.equals("BEGIN:VEVENT")) {
                inside = true;
                name = null;
                time = 0;
            } else if (line.equals("END:VEVENT")) {
                if (name != null && time > System.currentTimeMillis()) {
                    String lower = name.toLowerCase(Locale.US);
                    String label = lower.contains("consumer price index")
                        ? "CPI USA"
                        : lower.contains("employment situation")
                        ? "NFP USA" : null;

                    if (label != null && seen.add(label + time))
                        list.add(new Event(label, time));
                }
                inside = false;
            } else if (inside) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon);
                String value = line.substring(colon + 1);

                if (key.equals("SUMMARY") || key.startsWith("SUMMARY;"))
                    name = value;

                if (key.equals("DTSTART") || key.startsWith("DTSTART;"))
                    time = timestamp(key, value);
            }
        }

        list.sort((a, b) -> Long.compare(a.time, b.time));
        return list;
    }

    private void show(List<Event> list) {
        events.removeAllViews();
        if (list.isEmpty()) {
            text(events, "Nessun CPI/NFP futuro trovato nel calendario. "
                + "Verifica gli orari sul sito BLS.", 15);
        }

        DateTimeFormatter format =
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
                .withZone(ZoneId.systemDefault());

        for (Event event : list) {
            Button button = new Button(this);
            button.setText(event.name + " - "
                + format.format(Instant.ofEpochMilli(event.time)));
            button.setOnClickListener(v -> select(event.name, event.time));
            events.addView(button);
        }
    }

    private void download() {
        refresh.setEnabled(false);
        status.setText("Download calendario BLS...");

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                    "https://www.bls.gov/schedule/news_release/bls.ics"
                ).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "XauNews/0.3");
                connection.setRequestProperty("Accept", "text/calendar");

                int code = connection.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);

                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(),
                        "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append('\n');
                        if (content.length() > 2000000)
                            throw new IOException("Risposta troppo grande");
                    }
                }

                String data = content.toString();
                List<Event> list = parse(data);
                String updated = ZonedDateTime.now().format(
                    DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm z"));

                runOnUiThread(() -> {
                    if (destroyed) return;
                    getPreferences(0).edit().putString("calendar", data)
                        .putString("updated", updated).apply();
                    show(list);
                    status.setText("Scaricato: " + updated
                        + ". Tocca un evento.");
                    refresh.setEnabled(true);
                });
            } catch (Exception e) {
                String detail = e.getMessage();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    status.setText("Download non riuscito: " + detail
                        + ". L'eventuale copia salvata resta disponibile.");
                    refresh.setEnabled(true);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    @Override
    public void onStart() {
        super.onStart();
        handler.post(ticker);
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
