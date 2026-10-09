"""Read public frontend scripts to identify guest auth routes; never call auth APIs or print cookies/tokens."""
import re
import urllib.request
import urllib.parse
from html.parser import HTMLParser

HOST = 'www.mytvsuper.com'
LIMIT = 8 * 1024 * 1024

class Scripts(HTMLParser):
    def __init__(self):
        super().__init__()
        self.paths = []
    def handle_starttag(self, tag, attrs):
        if tag == 'script':
            src = dict(attrs).get('src', '')
            url = urllib.parse.urlparse(urllib.parse.urljoin('https://' + HOST, src))
            if url.hostname == HOST and url.path.startswith('/_next/static/') and url.path.endswith('.js'):
                self.paths.append(url.path)

def fetch(path):
    url = 'https://' + HOST + path
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=15) as response:
        end = urllib.parse.urlparse(response.url)
        if end.scheme != 'https' or end.hostname != HOST:
            raise ValueError('unexpected redirect')
        data = response.read(LIMIT + 1)
        if len(data) > LIMIT:
            raise ValueError('size limit')
        return data.decode('utf-8', errors='replace')

def safe_context(text):
    def literal(match):
        content = match.group()[1:-1]
        if content.startswith(('/api/', '/v1/', '/v2/')) and len(content) < 150:
            return repr(content.split('?')[0])
        if content in {'guest','self','web','cid','auth','post','get','GET','POST','free','web_auth','guest_mode','guestMode','error_code','user','login','device_id','platform','HK','type','username','password','guest_login','guestLogin','loginGuest','isGuestMode'}:
            return repr(content)
        if text[match.end():].lstrip().startswith(':') and re.fullmatch('[A-Za-z_]{1,40}', content):
            return repr(content)
        return '"<literal>"'
    text = re.sub(r'"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27|`[^`]*`', literal, text)
    text = re.sub('[A-Za-z0-9_+/=-]{60,}', '<opaque>', text)
    return text

try:
    parser = Scripts()
    parser.feed(fetch('/tc/live/81/'))
    print('FRONTEND public script count:', len(parser.paths))
    paths = list(dict.fromkeys(parser.paths))
    for path in list(paths):
        if 'webpack-' in path:
            runtime = fetch(path)
            for chunk, digest in re.findall(r'(\d{1,5}):"([a-f0-9]{16})"', runtime):
                if chunk in {'5973','3865','2639','7214'}:
                    paths.append('/_next/static/chunks/' + chunk + '-' + digest + '.js')
    for path in list(dict.fromkeys(paths))[:50]:
        try:
            js = fetch(path)
            routes = sorted(set(re.findall(r'/api/[A-Za-z0-9_/-]+', js)))
            if routes:
                print('FRONTEND routes:', ', '.join(routes))
            if 'webpack-' in path:
                print('FRONTEND chunk-map:', safe_context(js[js.find('.u='):js.find('.u=')+30000]))
            matches = list(re.finditer(r'getTrackingId:function|getGuestToken:function|var y=function\(e\)\{var t=e.device_pairing_action', js, re.I))
            if not matches:
                continue
            print('FRONTEND script:', path)
            for match in matches[:25]:
                print('FRONTEND context:', safe_context(js[max(0, match.start()-900):match.end()+5500])[:7000])
        except Exception as error:
            print('FRONTEND script inspection failed:', type(error).__name__)
except Exception as error:
    print('FRONTEND inspection unavailable:', type(error).__name__)
