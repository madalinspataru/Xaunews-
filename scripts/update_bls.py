"""Download official BLS releases and publish a small validated JSON cache."""
import datetime as dt
import html
import json
import os
from pathlib import Path
import re
import urllib.request
from zoneinfo import ZoneInfo

def find(pattern, text):
    m = re.search(pattern, text, re.I)
    if not m:
        raise ValueError('Formato BLS non riconosciuto')
    return m.group(1)

def clean(text):
    return re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]+>', '', text))).strip()

def parse(raw, kind, url):
    body = clean(find(r'<pre[^>]*>(.*?)</pre>', raw.replace('\n', ' ').replace('\r', ' ')))
    date = find(r'8:30 a\.m\. \(ET\) [A-Za-z]+, ([A-Za-z]+ \d{1,2}, \d{4})', body)
    published = dt.datetime.strptime(date, '%B %d, %Y').replace(hour=8, minute=30, tzinfo=ZoneInfo('America/New_York'))
    if published > dt.datetime.now(dt.timezone.utc):
        raise ValueError('Comunicato non ancora pubblicato')
    heading = 'CONSUMER PRICE INDEX' if kind == 'CPI' else 'THE EMPLOYMENT SITUATION'
    period = find(heading + r'\s*[-–—]+\s*([A-Za-z]+ \d{4})', body)
    if kind == 'CPI':
        table = find(r'(<table[^>]*id="cpi_pressa".*?</table>)', raw.replace('\n', ' ').replace('\r', ' '))
        measures = {}
        for row in re.findall(r'<tr[^>]*>(.*?)</tr>', table, re.I):
            cells = [clean(s) for s in re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', row, re.I)]
            if cells and cells[0] in ['All items', 'All items less food and energy']:
                measures[cells[0]] = [float(v) for v in cells[-2:]]
        all_items = measures['All items']; core = measures['All items less food and energy']
        values = [all_items[0], core[0], all_items[1], core[1]]
    else:
        summary = body.split(heading, 1)[1].split('This news release', 1)[0]
        jobs = find(r'([+-]?\d{1,3}(?:,\d{3})+)', summary)
        nfp = float(jobs.replace(',', '')) / 1000
        if re.search(r'(?:declined|decreased|fell|lost)\s+(?:by\s+)?' + re.escape(jobs), summary, re.I):
            nfp = -abs(nfp)
        unemployment = float(find(r'unemployment rate.{0,130}?([0-9]+\.[0-9]+) percent', summary))
        wages = find(r'(average hourly earnings for all employees on private nonfarm payrolls.{0,700}?)\(See tables', body)
        month_part = re.split(r'Over the (?:past|last) 12 months', wages, flags=re.I)[0]
        monthly = float(find(r'([+-]?[0-9]+\.[0-9]+) percent', month_part))
        if re.search(r'(?:declined|decreased|fell|down)', month_part, re.I):
            monthly = -abs(monthly)
        annual = float(find(r'Over the (?:past|last) 12 months.{0,150}?([+-]?[0-9]+\.[0-9]+) percent', wages))
        year_part = wages.lower().split('over the', 1)[1].split('percent', 1)[0]
        if re.search(r'(?:declined|decreased|fell)', year_part):
            annual = -abs(annual)
        if not 0 <= unemployment <= 100:
            raise ValueError('Disoccupazione non valida')
        values = [nfp, unemployment, monthly, annual]
    return {'period': period, 'source': url, 'published': int(published.timestamp()*1000), 'values': values}

def update():
    output = Path('data/bls_latest.json')
    previous = json.loads(output.read_text()) if output.exists() else {}
    updated = dict(previous); failures = []
    for kind, name in [('CPI', 'cpi'), ('NFP', 'empsit')]:
        url = f'https://www.bls.gov/news.release/{name}.nr0.htm'
        try:
            request = urllib.request.Request(url, headers={
                'User-Agent': 'XAU-News/0.7.1 (+https://github.com/madalinspataru/Xaunews-)',
                'Accept': 'text/html',
            })
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read(2000001)
            if len(raw) > 2000000:
                raise ValueError('Risposta troppo grande')
            release = parse(raw.decode('utf-8'), kind, url)
            if kind in previous and release['published'] < previous[kind]['published']:
                raise ValueError('Comunicato meno recente della copia salvata')
            updated[kind] = release
            print(kind, 'OK', release['period'])
        except Exception as error:
            failures.append(kind)
            print(kind, 'ERRORE:', str(error), '(copia precedente conservata)')
    if updated:
        output.parent.mkdir(exist_ok=True)
        output.write_text(json.dumps(updated, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    # Permit committing one successful source, then fail the workflow visibly.
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as f:
            f.write('failed='+str(bool(failures)).lower()+'\n')
    elif failures:
        raise SystemExit(1)

if __name__ == '__main__':
    update()
