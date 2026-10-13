import datetime, json, urllib.request, xml.etree.ElementTree as ET
date = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y%m%d')
sources = [('TVB', f'https://content-api.mytvsuper.com/v1/epg?network_code=J&from={date}&to={date}&platform=web'), ('ViuTV', 'https://nowplayer.now.com/tvguide/epglist?channelIdList[]=099&day=1'), ('XMLTV', 'https://epg.112114.xyz/pp.xml')]
for name, url in sources:
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Cookie': 'LANG=zh'})
        with urllib.request.urlopen(req, timeout=8) as res:
            body = res.read(16*1024*1024+1)
            if len(body) > 16*1024*1024: raise ValueError('oversize')
            if name == 'XMLTV':
                root = ET.fromstring(body)
                print(json.dumps({'source': name, 'status': res.status, 'channels': len(root.findall('channel')), 'programmes': len(root.findall('programme'))}))
            else:
                data = json.loads(body)
                items = [p for group in data if isinstance(group,dict) for day in group.get('item',[]) for p in day.get('epg',[])] if name == 'TVB' else (data[0] if isinstance(data,list) and data and isinstance(data[0],list) else [])
                print(json.dumps({'source':name,'status':res.status,'programmes':len(items),'fields':sorted(items[0].keys()) if items else []}))
    except Exception as err:
        print(json.dumps({'source':name,'error':type(err).__name__,'status':getattr(err,'code',None)}))
