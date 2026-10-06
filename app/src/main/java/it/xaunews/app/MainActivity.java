package it.xaunews.app;

import android.app.Activity;
import android.os.*;
import android.graphics.Color;
import android.widget.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    private final Handler handler =
        new Handler(Looper.getMainLooper());
    private TextView selected, countdown, status;
    private LinearLayout events;
    private Button refresh;
    private long eventTime;
    private boolean destroyed;
    private final ZoneId eastern = ZoneId.of("America/New_York");

    private final String[] sources = {"BLS", "BEA", "FED", "ISM"};
    private final String[] urls = {
        "https://www.bls.gov/schedule/news_release/bls.ics",
        "https://www.bea.gov/news/schedule/ics/online-calendar-subscription.ics",
        "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm",
        "https://www.ismworld.org/supply-management-news-and-reports/reports/rob-report-calendar/"
    };

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

    private static class Event {
        String name, source;
        long time;
        Event(String n, long t, String s) {
            name = n;
            time = t;
            source = s;
        }
    }

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
        text(root, "Calendario USA - BLS / BEA / FED / ISM", 18);
        text(root, "Fuso Android: " + ZoneId.systemDefault(), 14);
        selected = text(root,
            getPreferences(0).getString("name", "Nessun evento"), 22);
        eventTime = getPreferences(0).getLong("time", 0);
        countdown = text(root, "", 22);

        refresh = new Button(this);
        refresh.setText("Aggiorna tutte le news");
        root.addView(refresh);
        status = text(root, "", 14);
        events = new LinearLayout(this);
        events.setOrientation(1);
        root.addView(events);
        refresh.setOnClickListener(v -> download());
        showSaved();

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
            "Calendario di date e orari. Nessuna classificazione "
            + "rossa automatica. Consenso, risultati, AI e notifiche "
            + "non disponibili. Countdown a schermo aperto.",
            14);
    }

    private String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
            new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "XauNews/0.4 Android");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            StringBuilder data = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    data.append(line).append('\n');
                    if (data.length() > 4000000)
                        throw new IOException("Risposta troppo grande");
                }
            }
            return data.toString();
        } finally {
            c.disconnect();
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
            || zone.equalsIgnoreCase("US/Eastern"))
            zone = "America/New_York";
        int a = zone.indexOf("America/");
        if (a >= 0) zone = zone.substring(a);
        return dt.atZone(ZoneId.of(zone)).toInstant().toEpochMilli();
    }

    private String label(String title, String source) {
        String s = title.toLowerCase(Locale.US);
        if (source.equals("BLS")) {
            if (s.contains("consumer price index")) return "CPI USA";
            if (s.contains("employment situation"))
                return "NFP / disoccupazione / salari";
            if (s.contains("producer price index")) return "PPI USA";
            if (s.contains("job openings")
                && s.contains("labor turnover")) return "JOLTS USA";
        }
        if (source.equals("BEA")) {
            if (s.contains("personal income and outlays"))
                return "PCE / Core PCE USA";
            if ((s.startsWith("gross domestic product,")
                || s.startsWith("gross domestic product ")
                || s.startsWith("gdp ("))
                && !s.contains("by state")) {
                if (s.contains("advance")) return "PIL USA - prima stima";
                if (s.contains("second")) return "PIL USA - seconda stima";
                if (s.contains("third")) return "PIL USA - terza stima";
                return "PIL USA";
            }
        }
        return null;
    }

    private List<Event> parseIcs(String data, String source) {
        if (!data.contains("BEGIN:VCALENDAR")
            || !data.contains("END:VCALENDAR"))
            throw new IllegalArgumentException("Calendario non valido");
        String unfolded = data.replace("\r\n", "\n")
            .replaceAll("\n[ \t]", "");
        List<Event> list = new ArrayList<>();
        String name = null, property = null, value = null;
        boolean inside = false;
        for (String line : unfolded.split("\n")) {
            if (line.equals("BEGIN:VEVENT")) {
                inside = true;
                name = null;
                property = null;
                value = null;
            } else if (line.equals("END:VEVENT")) {
                String n = name == null ? null : label(name, source);
                if (n != null && property != null && value != null)
                    list.add(new Event(n, timestamp(property, value), source));
                inside = false;
            } else if (inside) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon);
                String v = line.substring(colon + 1);
                if (key.equals("SUMMARY") || key.startsWith("SUMMARY;"))
                    name = v.replace("\\,", ",");
                if (key.equals("DTSTART") || key.startsWith("DTSTART;")) {
                    property = key;
                    value = v;
                }
            }
        }
        if (list.isEmpty())
            throw new IllegalArgumentException("Nessun evento riconosciuto");
        return list;
    }

    private int month(String name) {
        String[] names = {"january", "february", "march", "april",
            "may", "june", "july", "august", "september",
            "october", "november", "december"};
        String s = name.toLowerCase(Locale.US);
        for (int i = 0; i < names.length; i++)
            if (s.equals(names[i])) return i + 1;
        throw new IllegalArgumentException("Mese non riconosciuto: " + name);
    }

    private long easternTime(int y, int m, int d, int h, int minute) {
        return LocalDateTime.of(y, m, d, h, minute)
            .atZone(eastern).toInstant().toEpochMilli();
    }

    private List<Event> parseFed(String html) {
        Document doc = Jsoup.parse(html);
        List<Event> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element heading : doc.select("h3, h4, h5")) {
            Matcher year = Pattern.compile(
                "^(\\d{4}) FOMC Meetings$").matcher(heading.text().trim());
            if (!year.matches()) continue;
            int y = Integer.parseInt(year.group(1));
            Element container = heading.parent();
            while (container != null
                && container.select(".fomc-meeting").isEmpty())
                container = container.parent();
            if (container == null) continue;

            for (Element meeting : container.select(".fomc-meeting")) {
                String m = meeting.select(".fomc-meeting__month").text();
                String days = meeting.select(".fomc-meeting__date").text();
                if (m.isEmpty() || days.isEmpty()) continue;
                String endMonth = m.contains("/")
                    ? m.substring(m.lastIndexOf('/') + 1) : m;
                Matcher numbers = Pattern.compile("\\d+").matcher(days);
                int day = 0;
                while (numbers.find())
                    day = Integer.parseInt(numbers.group());
                if (day == 0 || day > 31) continue;
                int mo = month(endMonth.trim());
                String key = y + "-" + mo + "-" + day;
                if (!seen.add(key)) continue;
                list.add(new Event(
                    "FOMC - decisione (orario standard, da confermare)",
                    easternTime(y, mo, day, 14, 0), "FED"));
                list.add(new Event(
                    "Fed - conferenza (orario standard, da confermare)",
                    easternTime(y, mo, day, 14, 30), "FED"));
            }
        }
        if (list.isEmpty())
            throw new IllegalArgumentException("Pagina Fed non riconosciuta");
        return list;
    }

    private List<Event> parseIsm(String html) {
        Document doc = Jsoup.parse(html);
        List<Event> list = new ArrayList<>();
        Pattern rowPattern = Pattern.compile(
            "(?i)^([a-z]+)\\s+(\\d{4})$");
        for (Element row : doc.select("table tr")) {
            List<Element> cells = row.select("td");
            if (cells.size() < 3) continue;
            Matcher match = rowPattern.matcher(cells.get(0).text().trim());
            if (!match.matches()) continue;
            int m = month(match.group(1));
            int y = Integer.parseInt(match.group(2));
            for (int i = 1; i <= 2; i++) {
                Matcher day = Pattern.compile("^\\s*(\\d{1,2})\\b")
                    .matcher(cells.get(i).text());
                if (!day.find())
                    throw new IllegalArgumentException("Data ISM non leggibile");
                int d = Integer.parseInt(day.group(1));
                list.add(new Event(
                    i == 1 ? "ISM manifatturiero USA" : "ISM servizi USA",
                    easternTime(y, m, d, 10, 0), "ISM"));
            }
        }
        if (list.isEmpty())
            throw new IllegalArgumentException("Tabella ISM non riconosciuta");
        return list;
    }

    private String encode(List<Event> list) throws Exception {
        JSONArray array = new JSONArray();
        for (Event e : list) {
            JSONObject o = new JSONObject();
            o.put("name", e.name);
            o.put("time", e.time);
            o.put("source", e.source);
            array.put(o);
        }
        return array.toString();
    }

    private List<Event> saved(String source) {
        List<Event> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(getPreferences(0)
                .getString("events_" + source, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                list.add(new Event(o.getString("name"),
                    o.getLong("time"), o.getString("source")));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void showSaved() {
        List<Event> all = new ArrayList<>();
        StringBuilder message = new StringBuilder();
        for (String source : sources) {
            all.addAll(saved(source));
            message.append(source).append(": ")
                .append(getPreferences(0).getString(
                    "updated_" + source, "non scaricato"))
                .append('\n');
        }
        status.setText("Ultime copie salvate:\n" + message);
        show(all);
    }

    private void show(List<Event> list) {
        events.removeAllViews();
        list.sort((a, b) -> Long.compare(a.time, b.time));
        Set<String> seen = new HashSet<>();
        DateTimeFormatter format =
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
                .withZone(ZoneId.systemDefault());
        int count = 0;
        for (Event e : list) {
            if (e.time <= System.currentTimeMillis()) continue;
            if (!seen.add(e.name + e.time)) continue;
            Button b = new Button(this);
            b.setText(e.name + "\n"
                + format.format(Instant.ofEpochMilli(e.time))
                + " - " + e.source);
            b.setOnClickListener(v -> select(e.name, e.time));
            events.addView(b);
            count++;
        }
        if (count == 0)
            text(events, "Nessun evento futuro disponibile nelle copie "
                + "caricate. Premi Aggiorna tutte le news.", 15);
    }

    private void download() {
        refresh.setEnabled(false);
        status.setText("Aggiornamento delle quattro fonti...");
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            for (int i = 0; i < sources.length; i++) {
                String source = sources[i];
                try {
                    String data = fetch(urls[i]);
                    List<Event> list = i < 2 ? parseIcs(data, source)
                        : i == 2 ? parseFed(data) : parseIsm(data);
                    String encoded = encode(list);
                    String updated = ZonedDateTime.now().format(
                        DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm z"));
                    getPreferences(0).edit()
                        .putString("events_" + source, encoded)
                        .putString("updated_" + source, updated).apply();
                    int future = 0;
                    for (Event e : list)
                        if (e.time > System.currentTimeMillis()) future++;
                    report.append(source).append(": aggiornato, ")
                        .append(future).append(" eventi futuri.\n");
                } catch (Exception e) {
                    report.append(source).append(": errore - ")
                        .append(e.getMessage())
                        .append(". Copia precedente conservata.\n");
                }
            }
            String result = report.toString();
            runOnUiThread(() -> {
                if (destroyed) return;
                showSaved();
                status.setText(result);
                refresh.setEnabled(true);
            });
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
