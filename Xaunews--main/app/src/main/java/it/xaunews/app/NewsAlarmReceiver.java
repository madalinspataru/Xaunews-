package it.xaunews.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class NewsAlarmReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "xau_news_alerts";
    private static final String ALERT = "it.xaunews.ALERT";
    private static final String TEST = "it.xaunews.TEST";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(
            "MainActivity", Context.MODE_PRIVATE);
    }

    public static void channel(Context c) {
        NotificationManager n =
            c.getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel(
            CHANNEL, "Avvisi news",
            NotificationManager.IMPORTANCE_HIGH));
    }

    public static boolean notifications(Context c) {
        channel(c);
        if (Build.VERSION.SDK_INT >= 33
            && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        NotificationManager n =
            c.getSystemService(NotificationManager.class);
        NotificationChannel ch = n.getNotificationChannel(CHANNEL);
        return n.areNotificationsEnabled()
            && ch != null
            && ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public static boolean exact(Context c) {
        return Build.VERSION.SDK_INT < 31
            || c.getSystemService(AlarmManager.class)
                .canScheduleExactAlarms();
    }

    private static PendingIntent pending(Context c, boolean test) {
        Intent i = new Intent(c, NewsAlarmReceiver.class);
        i.setAction(test ? TEST : ALERT);
        return PendingIntent.getBroadcast(
            c, test ? 101 : 100, i,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancel(Context c) {
        AlarmManager a = c.getSystemService(AlarmManager.class);
        a.cancel(pending(c, false));
        a.cancel(pending(c, true));
        prefs(c).edit().remove("next_group").apply();
    }

    private static TreeMap<Long, LinkedHashSet<String>> groups(Context c) {
        TreeMap<Long, LinkedHashSet<String>> result = new TreeMap<>();
        Set<String> delivered = prefs(c).getStringSet(
            "delivered_groups", Collections.emptySet());

        for (String source : new String[]{"BLS", "BEA", "FED", "ISM"}) {
            try {
                JSONArray array = new JSONArray(
                    prefs(c).getString("events_" + source, "[]"));

                for (int j = 0; j < array.length(); j++) {
                    JSONObject event = array.getJSONObject(j);
                    long time = event.getLong("time");

                    if (time <= System.currentTimeMillis()
                        || delivered.contains(Long.toString(time))) {
                        continue;
                    }

                    LinkedHashSet<String> names = result.get(time);
                    if (names == null) {
                        names = new LinkedHashSet<>();
                        result.put(time, names);
                    }
                    names.add(event.getString("name"));
                }
            } catch (JSONException ignored) {
            }
        }
        return result;
    }

    public static String schedule(Context c) {
        AlarmManager a = c.getSystemService(AlarmManager.class);
        PendingIntent p = pending(c, false);
        a.cancel(p);
        prefs(c).edit().remove("next_group").apply();

        if (!prefs(c).getBoolean("alerts", false))
            return "Avvisi automatici disattivati";
        if (!notifications(c))
            return "Abilita le notifiche Android";
        if (!exact(c))
            return "Abilita Sveglie e promemoria";

        TreeMap<Long, LinkedHashSet<String>> all = groups(c);
        if (all.isEmpty())
            return "Nessun evento futuro disponibile per gli avvisi";

        long event = all.firstKey();
        long trigger = Math.max(
            event - 300000, System.currentTimeMillis() + 2000);

        prefs(c).edit().putLong("next_group", event).apply();

        try {
            a.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, trigger, p);

            String when = Instant.ofEpochMilli(trigger)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));

            return "Avvisi automatici attivi\n"
                + "Prossimo avviso: " + when
                + "\nGruppi di eventi in attesa: " + all.size();
        } catch (SecurityException e) {
            prefs(c).edit().remove("next_group").apply();
            return "Permesso avvisi precisi non disponibile";
        }
    }

    public static String test(Context c) {
        if (!notifications(c) || !exact(c))
            return "Abilita prima gli avvisi e concedi i permessi";

        try {
            c.getSystemService(AlarmManager.class)
                .setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 60000,
                    pending(c, true));
            return "Prova tra 1 minuto. Torna alla Home.";
        } catch (SecurityException e) {
            return "Permesso avvisi precisi non disponibile";
        }
    }

    private static void notify(
        Context c, String title, String body, boolean test
    ) {
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent tap = PendingIntent.getActivity(
            c, 102, open,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build();

        c.getSystemService(NotificationManager.class)
            .notify(test ? 2 : 1, n);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || AlarmManager
                .ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
                .equals(action)) {
            schedule(c);
            return;
        }

        if (TEST.equals(action)) {
            if (notifications(c)) {
                try {
                    notify(c, "XAU NEWS - prova avviso",
                        "La notifica di prova e arrivata.", true);
                } catch (SecurityException ignored) {
                }
            }
            return;
        }

        if (!ALERT.equals(action)
            || !prefs(c).getBoolean("alerts", false)) {
            return;
        }

        long time = prefs(c).getLong("next_group", 0);
        TreeMap<Long, LinkedHashSet<String>> all = groups(c);
        LinkedHashSet<String> names = all.get(time);

        if (time > System.currentTimeMillis()
            && names != null && notifications(c)) {
            StringBuilder title = new StringBuilder();
            for (String name : names) {
                if (title.length() > 0) title.append(" + ");
                title.append(name);
            }

            String when = Instant.ofEpochMilli(time)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"));

            long minutes = Math.max(1,
                (time - System.currentTimeMillis() + 59999) / 60000);

            try {
                notify(c, "XAU NEWS - " + title,
                    "Eventi alle " + when
                        + ". Mancano circa " + minutes + " minuti.",
                    false);

                Set<String> done = new HashSet<>(
                    prefs(c).getStringSet(
                        "delivered_groups", Collections.emptySet()));
                done.add(Long.toString(time));

                long now = System.currentTimeMillis();
                Iterator<String> iterator = done.iterator();
                while (iterator.hasNext()) {
                    try {
                        if (Long.parseLong(iterator.next()) < now)
                            iterator.remove();
                    } catch (NumberFormatException e) {
                        iterator.remove();
                    }
                }

                prefs(c).edit()
                    .putStringSet("delivered_groups", done).apply();
            } catch (SecurityException ignored) {
            }
        }

        schedule(c);
    }
}
// FINE FILE
