package it.xaunews.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

public class NewsSyncWorker extends Worker {
    private static final ZoneId EASTERN =
        ZoneId.of("America/New_York");

    private static final String[] SOURCES = {
        "BLS", "BEA", "FED", "ISM"
    };

    private static final String[] URLS = {
        "https://www.bls.gov/schedule/news_release/bls.ics",
        "https://www.bea.gov/news/schedule/ics/online-calendar-subscription.ics",
        "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm",
        "https://www.ismworld.org/supply-management-news-and-reports/reports/rob-report-calendar/"
    };

    public NewsSyncWorker(Context context, WorkerParameters parameters) {
        super(context, parameters);
    }

    public static void install(Context context) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        PeriodicWorkRequest request =
            new PeriodicWorkRequest.Builder(
                NewsSyncWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "xau_calendar_update",
            ExistingPeriodicWorkPolicy.KEEP,
            request);
    }

    @Override
    public Result doWork() {
        return refresh(getApplicationContext()) > 0
            ? Result.success() : Result.retry();
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(url).openConnection();

        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty(
                "User-Agent", "XauNews/0.5 Android");

            int code = connection.getResponseCode();
            if (code != 200)
                throw new IOException("HTTP " + code);

            StringBuilder result = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line).append('\n');
                    if (result.length() > 4000000)
                        throw new IOException("Risposta troppo grande");
                }
            }
            return result.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static void add(
        JSONArray array, String name, long time, String source
    ) throws JSONException {
        JSONObject event = new JSONObject();
        event.put("name", name);
        event.put("time", time);
        event.put("source", source);
        array.put(event);
    }

    private static long timestamp(String property, String value) {
        if (value.length() < 15)
            throw new IllegalArgumentException("Orario assente");

        LocalDateTime time = LocalDateTime.parse(
            value.substring(0, 15),
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
                .withResolverStyle(ResolverStyle.STRICT));

        if (value.endsWith("Z"))
            return time.toInstant(ZoneOffset.UTC).toEpochMilli();

        int position = property.indexOf("TZID=");
        if (position < 0)
            throw new IllegalArgumentException("Fuso assente");

        String zone = property.substring(position + 5)
            .split(";")[0].replace("\"", "").trim();

        if (zone.equalsIgnoreCase("US-Eastern")
            || zone.equalsIgnoreCase("US/Eastern"))
            zone = "America/New_York";

        int america = zone.indexOf("America/");
        if (america >= 0) zone = zone.substring(america);

        return time.atZone(ZoneId.of(zone))
            .toInstant().toEpochMilli();
    }

    private static String label(String title, String source) {
        String text = title.toLowerCase(Locale.US);

        if (source.equals("BLS")) {
            if (text.contains("consumer price index"))
                return "CPI USA";
            if (text.contains("employment situation"))
                return "NFP / disoccupazione / salari";
            if (text.contains("producer price index"))
                return "PPI USA";
            if (text.contains("job openings")
                && text.contains("labor turnover"))
                return "JOLTS USA";
        }

        if (source.equals("BEA")) {
            if (text.contains("personal income and outlays"))
                return "PCE / Core PCE USA";

            if ((text.startsWith("gross domestic product,")
                || text.startsWith("gross domestic product ")
                || text.startsWith("gdp ("))
                && !text.contains("by state")) {
                if (text.contains("advance"))
                    return "PIL USA - prima stima";
                if (text.contains("second"))
                    return "PIL USA - seconda stima";
                if (text.contains("third"))
                    return "PIL USA - terza stima";
                return "PIL USA";
            }
        }
        return null;
    }

    private static JSONArray ics(
        String data, String source
    ) throws Exception {
        if (!data.contains("BEGIN:VCALENDAR")
            || !data.contains("END:VCALENDAR"))
            throw new IOException("Calendario non valido");

        JSONArray array = new JSONArray();
        String name = null, property = null, value = null;
        boolean inside = false;

        String unfolded = data.replace("\r\n", "\n")
            .replaceAll("\n[ \t]", "");

        for (String line : unfolded.split("\n")) {
            if (line.equals("BEGIN:VEVENT")) {
                inside = true;
                name = null;
                property = null;
                value = null;
            } else if (line.equals("END:VEVENT")) {
                String title = name == null ? null : label(name, source);
                if (title != null && property != null && value != null)
                    add(array, title, timestamp(property, value), source);
                inside = false;
            } else if (inside) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon);
                String content = line.substring(colon + 1);

                if (key.equals("SUMMARY") || key.startsWith("SUMMARY;"))
                    name = content.replace("\\,", ",");

                if (key.equals("DTSTART") || key.startsWith("DTSTART;")) {
                    property = key;
                    value = content;
                }
            }
        }

        if (array.length() == 0)
            throw new IOException("Nessun evento riconosciuto");
        return array;
    }

    private static int month(String value) {
        String[] names = {
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"
        };
        String name = value.trim().toLowerCase(Locale.US)
            .replace(".", "");
        if (name.length() >= 3) name = name.substring(0, 3);
        for (int i = 0; i < names.length; i++)
            if (name.equals(names[i])) return i + 1;
        throw new IllegalArgumentException("Mese sconosciuto");
    }

    private static long eastern(
        int year, int month, int day, int hour, int minute
    ) {
        return LocalDateTime.of(year, month, day, hour, minute)
            .atZone(EASTERN).toInstant().toEpochMilli();
    }

    private static JSONArray fed(String html) throws Exception {
        Document document = Jsoup.parse(html);
        JSONArray array = new JSONArray();
        Set<String> seen = new HashSet<>();
        int currentYear = LocalDate.now(EASTERN).getYear();

        for (Element heading : document.select("h3, h4, h5")) {
            Matcher yearMatch = Pattern.compile(
                "^(\\d{4}) FOMC Meetings$")
                .matcher(heading.text().trim());
            if (!yearMatch.matches()) continue;

            int year = Integer.parseInt(yearMatch.group(1));
            if (year < currentYear) continue;

            Element container = heading.parent();
            while (container != null
                && container.select(".fomc-meeting").isEmpty())
                container = container.parent();

            if (container == null
                || container == document.body()
                || container == document) continue;

            for (Element meeting : container.select(".fomc-meeting")) {
                String monthText =
                    meeting.select(".fomc-meeting__month").text();
                String days =
                    meeting.select(".fomc-meeting__date").text();
                if (monthText.isEmpty() || days.isEmpty()) continue;

                if (monthText.contains("/"))
                    monthText = monthText.substring(
                        monthText.lastIndexOf('/') + 1);

                Matcher range = Pattern.compile(
                    "^\\s*(\\d{1,2})(?:\\s*[-\\u2013]\\s*(\\d{1,2}))?\\s*\\*?\\s*$")
                    .matcher(days);
                if (!range.matches()) continue;

                int day = Integer.parseInt(
                    range.group(2) == null
                        ? range.group(1) : range.group(2));
                int month = month(monthText);
                if (!seen.add(year + "-" + month + "-" + day)) continue;

                add(array,
                    "FOMC - decisione (orario standard, da confermare)",
                    eastern(year, month, day, 14, 0), "FED");
                add(array,
                    "Fed - conferenza (orario standard, da confermare)",
                    eastern(year, month, day, 14, 30), "FED");
            }
        }

        if (array.length() == 0)
            throw new IOException("Pagina Fed non riconosciuta");
        return array;
    }

    private static JSONArray ism(String html) throws Exception {
        Document document = Jsoup.parse(html);
        document.select("script, style, sup").remove();
        JSONArray array = new JSONArray();
        Set<String> seen = new HashSet<>();
        Pattern heading = Pattern.compile(
            "(?i)^([a-z]+)\\s+(20\\d{2})$");

        for (Element row : document.select("table tr")) {
            List<Element> cells = row.children();
            if (cells.size() != 3) continue;

            Matcher match = heading.matcher(
                cells.get(0).text().replace('\u00a0', ' ').trim());
            if (!match.matches()) continue;

            int month = month(match.group(1));
            int year = Integer.parseInt(match.group(2));
            if (!seen.add(year + "-" + month)) continue;

            for (int i = 1; i <= 2; i++) {
                String dayText = cells.get(i).text().trim();
                if (!dayText.matches("\\d{1,2}"))
                    throw new IOException("Data ISM non riconosciuta");

                int day = Integer.parseInt(dayText);
                add(array,
                    i == 1 ? "ISM manifatturiero USA" : "ISM servizi USA",
                    eastern(year, month, day, 10, 0), "ISM");
            }
        }

        if (array.length() == 0)
            throw new IOException("Pagina ISM non riconosciuta");
        return array;
    }

    private static JSONArray ismBackup() throws Exception {
        JSONArray array = new JSONArray();
        add(array,
            "ISM manifatturiero USA - copia ufficiale 06/10/2026",
            eastern(2026, 11, 2, 10, 0), "ISM");
        add(array,
            "ISM servizi USA - copia ufficiale 06/10/2026",
            eastern(2026, 11, 4, 10, 0), "ISM");
        add(array,
            "ISM manifatturiero USA - copia ufficiale 06/10/2026",
            eastern(2026, 12, 1, 10, 0), "ISM");
        add(array,
            "ISM servizi USA - copia ufficiale 06/10/2026",
            eastern(2026, 12, 3, 10, 0), "ISM");
        return array;
    }

    public static synchronized int refresh(Context context) {
        SharedPreferences preferences = NewsAlarmReceiver.prefs(context);
        StringBuilder report = new StringBuilder();
        int successful = 0;

        for (int i = 0; i < SOURCES.length; i++) {
            String source = SOURCES[i];
            try {
                String data = fetch(URLS[i]);
                JSONArray events = i < 2 ? ics(data, source)
                    : i == 2 ? fed(data) : ism(data);

                String updated = ZonedDateTime.now().format(
                    DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm z"));

                preferences.edit()
                    .putString("events_" + source, events.toString())
                    .putString("updated_" + source, updated)
                    .apply();

                int future = 0;
                for (int j = 0; j < events.length(); j++)
                    if (events.getJSONObject(j).getLong("time")
                        > System.currentTimeMillis()) future++;

                report.append(source).append(": aggiornato, ")
                    .append(future).append(" eventi futuri.\n");
                successful++;
            } catch (Exception error) {
                if (source.equals("ISM")
                    && preferences.getString("events_ISM", "[]")
                        .equals("[]")) {
                    try {
                        preferences.edit()
                            .putString("events_ISM", ismBackup().toString())
                            .putString("updated_ISM",
                                "Copia ufficiale 06/10/2026; solo novembre/dicembre 2026")
                            .apply();
                    } catch (Exception ignored) {
                    }
                }

                report.append(source)
                    .append(": online non disponibile. ")
                    .append(preferences.getString(
                        "updated_" + source, "Nessuna copia salvata"))
                    .append(".\n");
            }

            NewsAlarmReceiver.schedule(context);
        }

        preferences.edit()
            .putString("sync_report", report.toString())
            .putLong("sync_attempt", System.currentTimeMillis())
            .apply();

        return successful;
    }
}
// FINE FILE
