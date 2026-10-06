package it.xaunews.app;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.io.IOException;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

public final class BlsRelease {
    public final String period, source;
    public final long published;
    public final double[] values;
    private BlsRelease(String period, String source, long published, double[] values) {
        this.period=period; this.source=source; this.published=published; this.values=values;
    }
    public static BlsRelease download(int type) throws IOException {
        String url= "https://www.bls.gov/news.release/"+(type==0?"cpi":"empsit")+".nr0.htm";
        Document doc=Jsoup.connect(url).timeout(15000).maxBodySize(2000000)
            .userAgent("XAU-News/0.7 (official release reader)").get();
        return parse(doc,type,url);
    }
    private static String match(String regex,String text) throws IOException {
        Matcher m=Pattern.compile(regex,Pattern.CASE_INSENSITIVE).matcher(text);
        if(!m.find()) throw new IOException("Formato BLS non riconosciuto: nessun dato importato");
        return m.group(1);
    }
    private static double numeric(String s) throws IOException {
        try { double n=Double.parseDouble(s.replace(",",""));
            if(Double.isNaN(n)||Double.isInfinite(n)) throw new NumberFormatException(); return n;
        } catch(NumberFormatException e) { throw new IOException("Valore BLS non valido"); }
    }
    public static BlsRelease parse(Document doc,int type,String url) throws IOException {
        String body=doc.select(".normalnews pre").text().replaceAll("\\s+"," ").trim();
        if(body.isEmpty()) throw new IOException("Comunicato BLS non trovato");
        String date=match("8:30 a\\.m\\. \\(ET\\) [A-Za-z]+, ([A-Za-z]+ \\d{1,2}, \\d{4})",body);
        long published;
        try { published=LocalDate.parse(date,DateTimeFormatter.ofPattern("MMMM d, uuuu",Locale.US))
                .atTime(8,30).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
        } catch(DateTimeException e) { throw new IOException("Data BLS non riconosciuta"); }
        String heading=type==0?"CONSUMER PRICE INDEX":"THE EMPLOYMENT SITUATION";
        String period=match(heading+"\\s*[-–—]+\\s*([A-Za-z]+ \\d{4})",body);
        double[] values=new double[4];
        if(type==0) {
            boolean all=false,core=false;
            Element table=doc.getElementById("cpi_pressa");
            if(table==null) throw new IOException("Tabella CPI non riconosciuta");
            for(Element row:table.select("tbody tr")) {
                List<Element> cells=new ArrayList<>();
                for(Element cell:row.children()) if(cell.tagName().equals("th")||cell.tagName().equals("td")) cells.add(cell);
                if(cells.size()<3) continue;
                String label=cells.get(0).text().trim();
                if(!label.equalsIgnoreCase("All items")&&!label.equalsIgnoreCase("All items less food and energy")) continue;
                double monthly=numeric(cells.get(cells.size()-2).text());
                double yearly=numeric(cells.get(cells.size()-1).text());
                if(label.equalsIgnoreCase("All items")) {values[0]=monthly;values[2]=yearly;all=true;}
                else {values[1]=monthly;values[3]=yearly;core=true;}
            }
            if(!all||!core) throw new IOException("Misure CPI incomplete");
        } else {
            int position=body.toUpperCase(Locale.ROOT).indexOf(heading);
            String summary=body.substring(position+heading.length());
            int end=summary.indexOf("This news release");
            if(end<0) throw new IOException("Riepilogo NFP non riconosciuto");
            summary=summary.substring(0,end);
            String jobs=match("([+-]?\\d{1,3}(?:,\\d{3})+)",summary);
            values[0]=numeric(jobs)/1000.0;
            if(!jobs.startsWith("-")&&Pattern.compile("(?:declined|decreased|fell|lost)\\s+(?:by\\s+)?"+Pattern.quote(jobs),Pattern.CASE_INSENSITIVE).matcher(summary).find()) values[0]=-Math.abs(values[0]);
            values[1]=numeric(match("unemployment rate.{0,130}?([0-9]+\\.[0-9]+) percent",summary));
            String wages=match("(average hourly earnings for all employees on private nonfarm payrolls.{0,700}?)\\(See tables",body);
            String monthPart=wages.split("(?i)Over the (?:past|last) 12 months")[0];
            values[2]=numeric(match("([+-]?[0-9]+\\.[0-9]+) percent",monthPart));
            if(Pattern.compile("(?:declined|decreased|fell|down)",Pattern.CASE_INSENSITIVE).matcher(monthPart).find()) values[2]=-Math.abs(values[2]);
            values[3]=numeric(match("Over the (?:past|last) 12 months.{0,150}?([+-]?[0-9]+\\.[0-9]+) percent",wages));
            String yearPart=wages.substring(wages.toLowerCase(Locale.ROOT).indexOf("over the"));
            if(Pattern.compile("(?:declined|decreased|fell)",Pattern.CASE_INSENSITIVE).matcher(yearPart.split("percent")[0]).find()) values[3]=-Math.abs(values[3]);
        }
        if(values[1]<0 && type==1 || type==1 && values[1]>100) throw new IOException("Disoccupazione non valida");
        return new BlsRelease(period,url,published,values);
    }
}
