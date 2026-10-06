package it.xaunews.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class NewsAnalysisActivity extends Activity {
    private XauPriceView quotes;
    private TextView result, tracking, officialStatus;
    private boolean visible;
    private int releaseRequest;
    private final android.os.Handler officialHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable officialLoop = new Runnable() {
        public void run() { if (!visible) return; loadOfficial(); officialHandler.postDelayed(this, 60000); }
    };
    private boolean releaseFetching;
    private Spinner kind;
    private final List<EditText> expected = new ArrayList<>();
    private final List<EditText> actual = new ArrayList<>();
    private LinearLayout fields;
    private SharedPreferences prefs;
    private long start, lastStamp;
    private double reference;
    private JSONObject samples = new JSONObject();
    private String watchLabel = "";

    private TextView text(LinearLayout root, String value, int size) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(Color.WHITE);
        v.setPadding(0, 14, 0, 14); root.addView(v); return v;
    }
    private Button button(LinearLayout root, String value) {
        Button b = new Button(this); b.setText(value); root.addView(b); return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("news_analysis", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 32, 24, 32);
        root.setBackgroundColor(Color.rgb(14,22,35)); scroll.addView(root); setContentView(scroll);
        button(root, "Torna al calendario").setOnClickListener(v -> finish());
        text(root, "Analisi dopo la pubblicazione", 25);
        text(root, "Inserisci consenso e risultato della stessa pubblicazione. "
            + "Il confronto e descrittivo: non e un modello AI o un segnale di trading.", 15);
        kind = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, new String[]{"CPI", "NFP"});
        kind.setAdapter(adapter); root.addView(kind);
        officialStatus = text(root, "Ultima pubblicazione BLS: caricamento...", 15);
        button(root, "Aggiorna risultato ufficiale BLS").setOnClickListener(v -> loadOfficial());
        fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); root.addView(fields);
        result = text(root, "", 17);
        kind.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) { buildFields(pos); }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        String selected = getIntent().getStringExtra("event_name");
        int initial = selected != null && selected.toUpperCase(Locale.ROOT).contains("NFP") ? 1 : prefs.getInt("kind", 0);
        kind.setSelection(initial);
        button(root, "Confronta attese e risultati").setOnClickListener(v -> compare());
        quotes = new XauPriceView(this); root.addView(quotes);
        text(root, "Misura il movimento da adesso", 21);
        text(root, "Il prezzo iniziale e il primo dato recente disponibile quando premi Avvia. "
            + "Non e il prezzo prima della news. Campioni a 5, 15 e 60 minuti; "
            + "misurazione solo con questa schermata visibile. Nessun dato viene ricostruito se la schermata resta chiusa.", 14);
        tracking = text(root, "", 16);
        button(root, "Avvia nuova misurazione").setOnClickListener(v -> begin());
        button(root, "Ferma misurazione").setOnClickListener(v -> {
            start = 0; persist(); tracking.setText("Misurazione fermata.");
        });
        start = prefs.getLong("start", 0); reference = Double.longBitsToDouble(prefs.getLong("reference", 0));
        lastStamp = prefs.getLong("last_stamp", 0); watchLabel = prefs.getString("watch_label", "");
        try { samples = new JSONObject(prefs.getString("samples", "{}")); } catch (JSONException e) { samples = new JSONObject(); }
        quotes.setQuoteListener((price, stamp) -> receive(price, stamp));
        showTracking(Double.NaN, 0);
    }
    private void buildFields(int type) {
        fields.removeAllViews(); expected.clear(); actual.clear();
        String[] labels = type == 0 ? new String[]{"CPI mensile (%)", "Core CPI mensile (%)", "CPI annuo (%)", "Core CPI annuo (%)"}
            : new String[]{"NFP (migliaia: 157 = 157.000)", "Disoccupazione (%)", "Salari mensili (%)", "Salari annui (%)"};
        for (int i=0;i<labels.length;i++) {
            text(fields, labels[i], 16);
            EditText e = input("Consenso", prefs.getString(type+"_e_"+i, ""));
            EditText a = input("Risultato pubblicato", prefs.getString(type+"_a_"+i, ""));
            expected.add(e); actual.add(a); fields.addView(e); fields.addView(a);
        }
        result.setText("Usa il consenso della tua fonte, non una previsione di origine sconosciuta. Compila almeno una coppia; per un quadro completo servono tutte le quattro misure.");
        prefs.edit().putInt("kind", type).apply();
        releaseRequest++;
        officialStatus.setText("Ultima pubblicazione BLS: attesa del download. Il consenso resta manuale.");
        loadOfficial();
    }
    private EditText input(String hint, String value) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(value);
        e.setTextColor(Color.WHITE); e.setHintTextColor(Color.LTGRAY);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }
    private double number(EditText e) {
        double n = Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
        if (Double.isNaN(n) || Double.isInfinite(n)) throw new IllegalArgumentException();
        return n;
    }
    private void compare() {
        StringBuilder s = new StringBuilder(); int stronger=0, weaker=0, pairs=0;
        int type = kind.getSelectedItemPosition(); SharedPreferences.Editor save = prefs.edit();
        try {
            for (int i=0;i<expected.size();i++) {
                String e = expected.get(i).getText().toString().trim();
                String a = actual.get(i).getText().toString().trim();
                save.putString(type+"_e_"+i, e).putString(type+"_a_"+i, a);
                if (e.isEmpty() && a.isEmpty()) continue;
                if (e.isEmpty() || a.isEmpty()) throw new IllegalArgumentException();
                double delta = number(actual.get(i))-number(expected.get(i));
                if (type==1 && i==1 && (number(actual.get(i))<0 || number(actual.get(i))>100 || number(expected.get(i))<0 || number(expected.get(i))>100)) throw new IllegalArgumentException();
                s.append((type==0 ? new String[]{"CPI mensile", "Core CPI mensile", "CPI annuo", "Core CPI annuo"} : new String[]{"NFP", "Disoccupazione", "Salari mensili", "Salari annui"})[i]).append(": ")
                    .append(String.format(Locale.ITALY, type==1 && i==0 ? "%+.0f" : "%+.3f", delta))
                    .append(type==1 && i==0 ? " mila posti" : " punti percentuali").append(" rispetto alle attese\n");
                double direction = type==1 && i==1 ? -delta : delta;
                if (direction>0.000001) stronger++; else if(direction< -0.000001) weaker++;
                pairs++;
            }
            if (pairs==0) throw new IllegalArgumentException();
            save.apply();
            s.append("\n").append(stronger>0 && weaker>0 ? "Dati contrastanti." : stronger>0 ? "Dati orientati verso inflazione/lavoro piu forti delle attese." : weaker>0 ? "Dati orientati verso inflazione/lavoro piu deboli delle attese." : "Dati in linea con le attese.");
            if(pairs<4) s.append(" Quadro parziale: ").append(pairs).append(" misure su 4.");
            s.append("\nLa reazione dell'oro puo differire: revisioni, altre news e aspettative sui tassi non sono incluse.");
            result.setText(s.toString());
        } catch (RuntimeException e) { result.setText("Controlla i numeri: ogni misura deve avere sia consenso sia risultato. Usa 0,3 o 0.3; per NFP scrivi 157 per 157.000 posti."); }
    }

    private void loadOfficial() {
        if (expected.size()!=4 || releaseFetching) return;
        final int type=kind.getSelectedItemPosition();
        final int request=releaseRequest;
        releaseFetching=true;
        officialStatus.setText("Scarico l'ultima pubblicazione ufficiale BLS...");
        new Thread(() -> {
            BlsRelease release=null; String failure=null;
            try { release=BlsRelease.download(type); }
            catch(Exception e) { failure=e.getMessage(); }
            final BlsRelease data=release; final String error=failure;
            runOnUiThread(() -> {
                releaseFetching=false;
                if(isFinishing() || isDestroyed()) return;
                if(request!=releaseRequest || type!=kind.getSelectedItemPosition()) {
                    if(visible) loadOfficial(); return;
                }
                if(data==null) {
                    officialStatus.setText("Download BLS non riuscito: "+error+". Nessun nuovo dato importato; eventuali valori visibili restano quelli precedenti.");
                    return;
                }
                if(data.published>System.currentTimeMillis()) {
                    officialStatus.setText("Comunicato non ancora pubblicato. Nessun risultato importato."); return;
                }
                long previous=prefs.getLong(type+"_release",0);
                SharedPreferences.Editor editor=prefs.edit().putLong(type+"_release",data.published);
                for(int i=0;i<4;i++) {
                    String value=String.format(Locale.US, type==1 && i==0 ? "%.0f" : "%.1f",data.values[i]);
                    actual.get(i).setText(value); editor.putString(type+"_a_"+i,value);
                    if(previous!=data.published) {expected.get(i).setText("");editor.remove(type+"_e_"+i);}
                }
                editor.apply();
                String date=Instant.ofEpochMilli(data.published).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm z"));
                officialStatus.setText("Fonte BLS — periodo: "+data.period+"\nPubblicato: "+date
                    +"\nQuesti sono gli ultimi risultati gia pubblicati, non quelli della prossima news. Consenso da inserire per questa pubblicazione.");
                if(previous!=data.published) result.setText("Risultati ufficiali caricati. Inserisci le attese relative al periodo indicato sopra; le attese precedenti sono state svuotate.");
            });
        }).start();
    }
    @Override public void onStart() {
        super.onStart(); visible=true; officialHandler.post(officialLoop);
    }
    @Override public void onStop() {
        visible=false; officialHandler.removeCallbacks(officialLoop); super.onStop();
    }

    private void begin() {
        long stamp = quotes.getQuoteTimestamp(); double price = quotes.getQuotePrice();
        long now = System.currentTimeMillis();
        if (!quotes.hasFreshQuote() || stamp>now || now-stamp>60000) {
            tracking.setText("Attendi un prezzo aggiornato da meno di un minuto e riprova."); return;
        }
        start = now; reference = price; lastStamp = stamp; samples = new JSONObject();
        watchLabel = kind.getSelectedItem().toString(); persist(); showTracking(price, stamp);
    }
    private void receive(double price, long stamp) {
        if (start==0 || !quotes.hasFreshQuote() || stamp<=lastStamp || stamp>System.currentTimeMillis()) return;
        lastStamp=stamp;
        try {
            for(int m:new int[]{5,15,60}) {
                String key=String.valueOf(m); long target=start+m*60000L;
                if(samples.has(key) || stamp<target) continue;
                JSONObject sample=new JSONObject();
                if(stamp-target>90000) sample.put("missing",true);
                else sample.put("price",price).put("stamp",stamp);
                samples.put(key,sample);
            }
            persist(); showTracking(price,stamp);
        } catch(JSONException e) { tracking.setText("Impossibile salvare il campione."); }
    }
    private void persist() {
        prefs.edit().putLong("start",start).putLong("reference",Double.doubleToLongBits(reference))
            .putLong("last_stamp",lastStamp).putString("samples",samples.toString()).putString("watch_label",watchLabel).apply();
    }
    private String movement(double value) {
        return String.format(Locale.ITALY,"%+.2f USD/oncia (%+.3f%%)",value-reference,(value/reference-1)*100);
    }
    private void showTracking(double price,long stamp) {
        if(start==0) { tracking.setText("Nessuna misurazione attiva."); return; }
        String time=Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"));
        StringBuilder s=new StringBuilder(watchLabel+" — avvio "+time+"\nPrezzo iniziale: "+String.format(Locale.ITALY,"%.2f USD/oncia",reference));
        if(!Double.isNaN(price)) s.append("\nUltimo movimento: ").append(movement(price));
        for(int m:new int[]{5,15,60}) {
            JSONObject sample=samples.optJSONObject(String.valueOf(m));
            s.append("\n+").append(m).append(" min: ");
            if(sample==null) s.append("in attesa di un campione");
            else if(sample.optBoolean("missing")) s.append("non rilevato nella finestra prevista");
            else s.append(movement(sample.optDouble("price"))).append(" (campione entro 90 s dal termine)");
        }
        tracking.setText(s.toString());
    }
}
