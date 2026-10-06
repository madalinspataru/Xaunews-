package it.xaunews.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import java.time.*;
import java.time.format.DateTimeFormatter;

public class NewsAlarmReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "xau_news_alerts";
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }
    public static void channel(Context c) {
        NotificationManager n = c.getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel(CHANNEL,
            "Avvisi news", NotificationManager.IMPORTANCE_HIGH));
    }
    public static boolean notifications(Context c) {
        channel(c);
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(
            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager n = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = n.getNotificationChannel(CHANNEL);
        return n.areNotificationsEnabled() && ch != null
            && ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    public static boolean exact(Context c) {
        return Build.VERSION.SDK_INT < 31
            || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    private static PendingIntent pending(Context c, boolean test) {
        Intent i = new Intent(c, NewsAlarmReceiver.class);
        i.setAction(test ? "it.xaunews.TEST" : "it.xaunews.ALERT");
        return PendingIntent.getBroadcast(c, test ? 101 : 100, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public static void cancel(Context c) {
        AlarmManager a = c.getSystemService(AlarmManager.class);
        a.cancel(pending(c, false)); a.cancel(pending(c, true));
    }
    public static String schedule(Context c) {
        AlarmManager a = c.getSystemService(AlarmManager.class);
        PendingIntent p = pending(c, false); a.cancel(p);
        if (!prefs(c).getBoolean("alerts", false)) return "Avvisi disattivati";
        if (!notifications(c)) return "Abilita le notifiche Android";
        if (!exact(c)) return "Abilita Sveglie e promemoria nelle impostazioni";
        long event = prefs(c).getLong("time", 0);
        if (event <= System.currentTimeMillis()) return "Seleziona un evento futuro";
        if (prefs(c).getLong("delivered", 0) == event) return "Avviso gia inviato per questo evento";
        long trigger = Math.max(event - 300000, System.currentTimeMillis() + 2000);
        try {
            a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, p);
            String when = Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
            return "Avviso programmato: " + when + "\nSolo per l'evento selezionato";
        } catch (SecurityException e) { return "Permesso avvisi precisi non disponibile"; }
    }
    public static String test(Context c) {
        if (!notifications(c) || !exact(c)) return "Premi prima Abilita avvisi e concedi i permessi";
        try {
            c.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60000, pending(c, true));
            return "Prova programmata tra 1 minuto. Torna alla schermata Home.";
        } catch (SecurityException e) { return "Permesso avvisi precisi non disponibile"; }
    }
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) {
            schedule(c); return;
        }
        boolean test = "it.xaunews.TEST".equals(action);
        if (!test && !"it.xaunews.ALERT".equals(action)) return;
        if (!notifications(c)) return;
        long event = prefs(c).getLong("time", 0);
        if (!test && (!prefs(c).getBoolean("alerts", false)
            || event <= System.currentTimeMillis()
            || prefs(c).getLong("delivered", 0) == event)) return;
        String title = test ? "XAU NEWS - prova avviso"
            : prefs(c).getString("name", "News USA");
        String body = test ? "La notifica di prova e arrivata."
            : "Evento alle " + Instant.ofEpochMilli(event)
                .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                + ". Mancano circa " + Math.max(1, (event - System.currentTimeMillis() + 59999) / 60000)
                + " minuti.";
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 102, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(tap).setAutoCancel(true).build();
        try {
            c.getSystemService(NotificationManager.class).notify(test ? 2 : 1, notification);
            if (!test) prefs(c).edit().putLong("delivered", event).apply();
        } catch (SecurityException ignored) { }
    }
}
