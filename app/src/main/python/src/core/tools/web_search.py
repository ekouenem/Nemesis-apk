"""Web search using independent public endpoints, with honest failures.

No challenge is solved or bypassed. Queries are sent to each selected provider.
"""
import html
import json
import re
import urllib.request
import urllib.parse
import unicodedata
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Optional, List

@dataclass
class WebSearchInput:
    query: str
    max_results: int = 5

@dataclass
class WebSearchResult:
    success: bool
    results: List[dict] = None
    error: Optional[str] = None
    def __post_init__(self):
        if self.results is None:
            self.results = []

DESCRIPTION_FULL = """Search the web; returns result titles, URLs and snippets.
The query is sent to external search providers. Results can be incomplete."""

_HEADERS = {'User-Agent': 'NEMESIS-Android/0.1 (+https://github.com/ekouenem/Nemesis-apk)',
            'Accept': 'application/rss+xml,application/xml,text/html;q=0.9,*/*;q=0.5'}
_LIMIT = 1024 * 1024

def _get(url):
    req = urllib.request.Request(url, headers=_HEADERS)
    with urllib.request.urlopen(req, timeout=12) as resp:
        status = getattr(resp, 'status', 200)
        raw = resp.read(_LIMIT + 1)
        if len(raw) > _LIMIT: raise RuntimeError('Réponse trop volumineuse')
        if status == 202 or b'anomaly-modal' in raw.lower():
            raise RuntimeError('Vérification anti-robot demandée')
        return raw

def _clean(value):
    return html.unescape(re.sub(r'<[^>]*>', '', value or '')).strip()

def _bing_rss_search(query, maximum):
    url = 'https://www.bing.com/search?' + urllib.parse.urlencode({'q': query, 'format':'rss'})
    raw = _get(url)
    # ElementTree doesn't fetch external entities. Reject DTDs outright.
    if b'<!doctype' in raw.lower() or b'<!entity' in raw.lower(): raise RuntimeError('Flux RSS non valide')
    root = ET.fromstring(raw)
    if root.tag.lower() != 'rss': raise RuntimeError('Réponse de recherche inattendue')
    out = []
    words=[x for x in re.findall(r'[a-z0-9]+',unicodedata.normalize('NFKD',query.casefold()).encode('ascii','ignore').decode()) if len(x)>=4 and x not in {'avec','pour','dans','comment','quelle','quelles','quels','sont','entre','from','this','that','what','the'}]
    for item in root.findall('./channel/item'):
        link = (item.findtext('link') or '').strip()
        if urllib.parse.urlsplit(link).scheme not in ('http','https'): continue
        title = _clean(item.findtext('title'))
        haystack=unicodedata.normalize('NFKD',(title+' '+_clean(item.findtext('description'))).casefold()).encode('ascii','ignore').decode()
        if words and sum(1 for word in set(words) if word in haystack)<min(2,len(set(words))): continue
        if title: out.append({'title':title, 'url':link, 'snippet':_clean(item.findtext('description')), 'source':'Bing'})
        if len(out) >= maximum: break
    return out

def _wiki_search(query, maximum):
    url='https://fr.wikipedia.org/w/api.php?'+urllib.parse.urlencode({
        'action':'query','list':'search','srsearch':query,'srlimit':maximum,'format':'json','utf8':'1'})
    payload=json.loads(_get(url).decode('utf-8'))
    out=[]
    for item in payload.get('query',{}).get('search',[]):
        title=str(item.get('title') or '').strip()
        if title:
            out.append({'title':title,'url':'https://fr.wikipedia.org/wiki/'+urllib.parse.quote(title.replace(' ','_')),
                        'snippet':_clean(item.get('snippet','')),'source':'Wikipédia (fr)'})
    return out[:maximum]

def _ddg_html_search(query, maximum):
    from html.parser import HTMLParser
    url = 'https://html.duckduckgo.com/html/?' + urllib.parse.urlencode({'q':query})
    raw = _get(url)
    class Parser(HTMLParser):
        def __init__(self):
            super().__init__(); self.results=[]; self.cur=None; self.field=None
        def handle_starttag(self, tag, attrs):
            attrs=dict(attrs); classes=attrs.get('class','').split()
            if tag=='a' and 'result__a' in classes:
                if self.cur and self.cur.get('title'): self.results.append(self.cur)
                href=attrs.get('href',''); href='https:'+href if href.startswith('//') else href
                parts=urllib.parse.urlsplit(href)
                if parts.hostname and parts.hostname.endswith('duckduckgo.com'):
                    href=urllib.parse.parse_qs(parts.query).get('uddg',[href])[0]
                self.cur={'title':'','url':href,'snippet':''}; self.field='title'
            elif self.cur and 'result__snippet' in classes: self.field='snippet'
        def handle_endtag(self, tag):
            if tag=='a' and self.field=='title': self.field=None
            elif tag in ('a','div','span') and self.field=='snippet': self.field=None
        def handle_data(self, data):
            if self.cur and self.field: self.cur[self.field]+=data
        def finish(self):
            if self.cur and self.cur.get('title'): self.results.append(self.cur)
            return [x for x in self.results if urllib.parse.urlsplit(x['url']).scheme in ('http','https')][:maximum]
    parser=Parser(); parser.feed(raw.decode('utf-8',errors='replace')); return parser.finish()

def web_search(input: WebSearchInput) -> WebSearchResult:
    query=input.query.strip()
    if not query: return WebSearchResult(False, error='Requête de recherche vide.')
    try: maximum=max(1,min(int(input.max_results),10))
    except (ValueError,TypeError): maximum=5
    errors=[]; results=[]; seen=set()
    # Wikipedia's own API supplies useful French-language references; Bing RSS
    # supplies general web pages. Do not treat one source as an exhaustive search.
    for name,provider,quota in (('Wikipédia',_wiki_search,min(maximum,2)),('Bing',_bing_rss_search,maximum),('DuckDuckGo',_ddg_html_search,maximum)):
        if len(results)>=maximum: break
        # DuckDuckGo is a fallback only if neither endpoint yielded a result.
        if name=='DuckDuckGo' and results: break
        try:
            found=provider(query,quota)
            if not found: errors.append(name+': aucun résultat exploitable')
            for item in found:
                if item.get('url') not in seen:
                    seen.add(item.get('url')); results.append(item)
                    if len(results)>=maximum: break
        except Exception as exc:
            errors.append(name+': '+str(exc)[:180])
    if results: return WebSearchResult(True,results)
    return WebSearchResult(False,error='Recherche indisponible. '+' ; '.join(errors))
