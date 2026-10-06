package it.xaunews.app;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class XauPriceView extends LinearLayout {
    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private final TextView price;
    private final TextView info;

    private boolean attached;
    private boolean active;
    private boolean fetching;

    public interface QuoteListener { void onQuote(double price, long timestamp); }
    private QuoteListener listener;
    public void setQuoteListener(QuoteListener value) { listener = value; }
    public double getQuotePrice() { return lastPrice; }
    public long getQuoteTimestamp() { return updatedAt; }
    public boolean hasFreshQuote() {
        long age = System.currentTimeMillis() - updatedAt;
        return failure.isEmpty() && updatedAt > 0 && age >= 0 && age <= 120000
            && !Double.isNaN(lastPrice);
    }

    private long nextRequest;
    private long updatedAt;
    private String failure = "";
    private double lastPrice = Double.NaN;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!attached || !active) return;
            render();
            if (!fetching
                && SystemClock.elapsedRealtime() >= nextRequest) {
                fetch();
            }
            handler.postDelayed(this, 1000);
        }
    };

    public XauPriceView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(0, 16, 0, 16);

        price = new TextView(context);
        price.setTextColor(Color.rgb(235, 196, 90));
        price.setTextSize(26);
        price.setText("XAU/USD - caricamento...");
        addView(price);

        info = new TextView(context);
        info.setTextColor(Color.WHITE);
        info.setTextSize(14);
        info.setPadding(0, 8, 0, 8);
        addView(info);

        TextView source = new TextView(context);
        source.setTextColor(Color.LTGRAY);
        source.setTextSize(13);
        source.setText(
            "Fonte: gold-api.com. Prezzo spot esterno, "
            + "non quotazione Ultima Markets. "
            + "Bid/Ask e spread non disponibili.");
        addView(source);
    }

    private void render() {
        if (!Double.isNaN(lastPrice)) {
            price.setText(String.format(
                Locale.ITALY, "XAU/USD  %,.2f USD", lastPrice));
        }

        if (updatedAt > 0) {
            long age = Math.max(0,
                (System.currentTimeMillis() - updatedAt) / 1000);
            String time = Instant.ofEpochMilli(updatedAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(
                    "dd/MM HH:mm:ss z"));

            String freshness = age > 120
                ? "DATO NON RECENTE"
                : "Dato ricevuto dal fornitore";

            info.setText(freshness + "\n"
                + "Ora del dato: " + time
                + "\nEta: " + age + " secondi"
                + (failure.isEmpty() ? "" : "\n" + failure));
        } else {
            info.setText(failure.isEmpty()
                ? "Richiesta del prezzo in corso..."
                : failure);
        }
    }

    private void fetch() {
        fetching = true;
        nextRequest = SystemClock.elapsedRealtime() + 30000;

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                    "https://api.gold-api.com/price/XAU/USD"
                ).openConnection();

                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty(
                    "Accept", "application/json");

                int code = connection.getResponseCode();
                if (code != 200)
                    throw new IOException("HTTP " + code);

                StringBuilder data = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                        connection.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        data.append(line);
                        if (data.length() > 100000)
                            throw new IOException("Risposta troppo grande");
                    }
                }

                JSONObject quote = new JSONObject(data.toString());

                if (!"XAU".equals(quote.getString("symbol"))
                    || !"USD".equals(quote.getString("currency"))) {
                    throw new IOException("Strumento o valuta inattesi");
                }

                double value = quote.getDouble("price");
                long timestamp = Instant.parse(
                    quote.getString("updatedAt")).toEpochMilli();

                if (Double.isNaN(value)
                    || Double.isInfinite(value) || value <= 0) {
                    throw new IOException("Prezzo non valido");
                }
                if (timestamp <= 0
                    || timestamp > System.currentTimeMillis() + 120000) {
                    throw new IOException("Timestamp non valido");
                }

                handler.post(() -> {
                    lastPrice = value;
                    updatedAt = timestamp;
                    failure = "";
                    fetching = false;
                    nextRequest =
                        SystemClock.elapsedRealtime() + 30000;
                    render();
                    if (attached && active && listener != null)
                        listener.onQuote(value, timestamp);
                });
            } catch (Exception error) {
                String message = error.getMessage();
                handler.post(() -> {
                    fetching = false;
                    failure = "Prezzo non aggiornato: " + message;
                    nextRequest =
                        SystemClock.elapsedRealtime() + 30000;
                    render();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void restart() {
        handler.removeCallbacks(loop);
        if (attached && active) handler.post(loop);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        active = getWindowVisibility() == View.VISIBLE;
        restart();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        handler.removeCallbacks(loop);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        active = visibility == View.VISIBLE;
        if (handler != null) restart();
    }
}
// FINE FILE
