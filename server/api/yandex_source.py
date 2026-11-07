# SPDX-License-Identifier: GPL-3.0-or-later
"""Bounded adapter for yandex-music3.2.2; OAuth is sent only to its API origin."""
import hashlib
import logging
import math
import os
from pathlib import Path
import re
import shutil
import time
import urllib.parse

import audio
from import_errors import ImportFailure, check_cancel

MAX_TRACKS=50_000
MAX_SECTIONS=2000
MAX_METADATA=20*2**20

def clean(value,limit=320):
    return ''.join(c if ord(c)>=32 and ord(c)!=127 else ' ' for c in str(value or ''))[:limit]

def source_id(value):
    value=str(value).split(':',1)[0]
    if not re.fullmatch(r'(?:[0-9]{1,20}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})',value):
        raise ImportFailure('SOURCE_UNAVAILABLE')
    return value

def describe(track):
    albums=getattr(track,'albums',None) or []
    track_type=getattr(track,'type',None)
    kinds=[getattr(album,field,None) for album in albums for field in ('type','meta_type')]
    spoken={'audiobook','podcast','podcast-episode','podcast_episode','noise'}
    kind='music' if track_type=='music' and not any(k in spoken or k not in (None,'','music','single','ep','compilation','album') for k in kinds) else 'excluded'
    album=albums[0] if albums else None
    return {'id':source_id(track.id),'kind':kind,'title':clean(getattr(track,'title',None)),
        'artist':clean(', '.join(clean(getattr(a,'name',''),100) for a in (getattr(track,'artists',None) or [])[:20])),
        'album':clean(getattr(album,'title','')),'albumArtist':clean(', '.join(clean(getattr(a,'name',''),100) for a in (getattr(album,'artists',None) or [])[:20])),
        'genre':clean(getattr(album,'genre',''),100),'durationMs':getattr(track,'duration_ms',None),
        'available':getattr(track,'available',False) is True}

def remote_url(url,api=False):
    parsed=urllib.parse.urlsplit(url)
    host=(parsed.hostname or '').lower()
    permitted=host=='api.music.yandex.net' if api else host.endswith('.yandex.net') or host=='yandex.net'
    if parsed.scheme!='https' or not permitted or parsed.port not in (None,443) or parsed.username or parsed.password or parsed.fragment:
        raise ImportFailure('SOURCE_UNAVAILABLE')
    return url

def check_space(path,extra=0):
    if shutil.disk_usage(path).free<audio.RESERVE+extra:raise ImportFailure('NO_SPACE')

class Provider:
    def __init__(self,token,event):
        import requests
        from yandex_music import Client
        from yandex_music.utils.request import Request
        self.event=event
        self.api_session=requests.Session();self.api_session.trust_env=False
        self.download_session=requests.Session();self.download_session.trust_env=False
        self.started=time.monotonic()
        # Library diagnostic decorators must never emit request bodies/headers.
        logger=logging.getLogger('yandex_music');logger.handlers=[logging.NullHandler()];logger.propagate=False
        provider=self
        class BoundedRequest(Request):
            def _request_wrapper(self,method,url,**kwargs):
                check_cancel(event);remote_url(url,api=True)
                kwargs=self._prepare_kwargs(kwargs)
                kwargs.pop('proxies',None)
                kwargs['timeout']=(5,20);kwargs['stream']=True;kwargs['allow_redirects']=False
                try:
                    with provider.api_session.request(method,url,**kwargs) as response:
                        if response.status_code in (401,403):raise ImportFailure('AUTH_REQUIRED')
                        if response.status_code in (429,500,502,503,504):raise ImportFailure('NETWORK_ERROR')
                        if response.status_code!=200:raise ImportFailure('SOURCE_UNAVAILABLE')
                        data=bytearray();started=time.monotonic()
                        for block in response.iter_content(65536):
                            check_cancel(event)
                            if len(data)+len(block)>MAX_METADATA:raise ImportFailure('SOURCE_LIMIT')
                            if time.monotonic()-started>45:raise ImportFailure('NETWORK_ERROR')
                            data.extend(block)
                        return bytes(data)
                except requests.RequestException:raise ImportFailure('NETWORK_ERROR') from None
        try:
            self.client=Client(token,request=BoundedRequest(timeout=20)).init()
            uid=getattr(getattr(self.client.me,'account',None),'uid',None)
            if not uid:raise ImportFailure('AUTH_REQUIRED')
            self.account_hash=hashlib.sha256(str(uid).encode()).hexdigest()
        except BaseException:
            self.close();raise

    def close(self):
        self.api_session.close();self.download_session.close()
        if hasattr(self,'client'):
            self.client.token=None
            self.client.request.headers.pop('Authorization',None)

    def inventory(self):
        check_cancel(self.event)
        self.playlists=[]
        likes=self.client.users_likes_tracks()
        if likes is None:raise ImportFailure('SOURCE_UNAVAILABLE')
        liked=[source_id(item.id) for item in likes.tracks]
        self.playlists.append({'key':'likes','name':'Мне нравится','ids':liked})
        yield from liked
        seen=set();sections=0
        for mode in ('own','saved'):
            entries=self.client.users_playlists_list() if mode=='own' else self.client.users_likes_playlists()
            if entries is None:raise ImportFailure('SOURCE_UNAVAILABLE')
            for entry in entries:
                check_cancel(self.event);playlist=entry if mode=='own' else entry.playlist
                if playlist is None:raise ImportFailure('SOURCE_UNAVAILABLE')
                uid=getattr(getattr(playlist,'owner',None),'uid',None) or getattr(playlist,'uid',None)
                key=(str(uid),str(playlist.kind))
                if key in seen:continue
                seen.add(key);sections+=1
                if sections>MAX_SECTIONS:raise ImportFailure('SOURCE_LIMIT')
                full=self.client.users_playlists(playlist.kind,user_id=uid)
                if full is None or full.tracks is None or (full.track_count is not None and len(full.tracks)!=full.track_count):raise ImportFailure('SOURCE_UNAVAILABLE')
                ids=[source_id(item.id) for item in full.tracks]
                self.playlists.append({'key':'playlist:'+key[0]+':'+key[1],'name':clean(full.title) or 'Playlist','ids':ids})
                yield from ids
        albums=self.client.users_likes_albums()
        if albums is None:raise ImportFailure('SOURCE_UNAVAILABLE')
        for entry in albums:
            check_cancel(self.event);sections+=1
            if sections>MAX_SECTIONS:raise ImportFailure('SOURCE_LIMIT')
            if entry.album is None:raise ImportFailure('SOURCE_UNAVAILABLE')
            album=self.client.albums_with_tracks(entry.album.id)
            if album is None or album.volumes is None:raise ImportFailure('SOURCE_UNAVAILABLE')
            for volume in album.volumes:
                for track in volume:yield source_id(track.id)

    def metadata(self,ids):
        check_cancel(self.event)
        records=self.client.tracks(ids)
        if records is None:raise ImportFailure('SOURCE_UNAVAILABLE')
        return {source_id(track.id):describe(track) for track in records}

    def download(self,key,path):
        import requests
        check_cancel(self.event)
        records=self.client.tracks([key])
        if not records:raise ImportFailure('SOURCE_UNAVAILABLE',unavailable=True)
        track=next((track for track in records if source_id(track.id)==key),None)
        if track is None:raise ImportFailure('SOURCE_UNAVAILABLE',unavailable=True)
        meta=describe(track)
        if meta['kind']!='music':return None,meta
        if not meta['available']:raise ImportFailure('SOURCE_UNAVAILABLE',unavailable=True)
        duration=meta['durationMs']
        if not isinstance(duration,(int,float)) or not math.isfinite(duration) or not 0<duration<=audio.MAX_SECONDS*1000:raise ImportFailure('SOURCE_LIMIT')
        # Raw MP3 has an unambiguous container and supports ID3 without remuxing.
        info=track.get_file_info(quality='nq',codecs=['mp3'],transport='raw')
        info=getattr(info,'download_info',None)
        if info is None or info.transport!='raw' or info.codec!='mp3':raise ImportFailure('SOURCE_UNAVAILABLE')
        suffix='.mp3'
        url=remote_url(info.url or next(iter(info.urls or []),''))
        count=0;started=time.monotonic();check_space(path.parent,audio.MAX_BYTES)
        try:
            with self.download_session.get(url,stream=True,timeout=(5,20),allow_redirects=False) as response:
                if response.status_code!=200:raise ImportFailure('NETWORK_ERROR')
                length=response.headers.get('Content-Length')
                if length and (not length.isdigit() or int(length)>audio.MAX_BYTES):raise ImportFailure('SOURCE_LIMIT')
                with path.open('xb') as output:
                    for block in response.iter_content(65536):
                        check_cancel(self.event);count+=len(block)
                        if count>audio.MAX_BYTES:raise ImportFailure('SOURCE_LIMIT')
                        if time.monotonic()-started>300:raise ImportFailure('NETWORK_ERROR')
                        check_space(path.parent,len(block));output.write(block)
                    output.flush();os.fsync(output.fileno())
            if not count:raise ImportFailure('SOURCE_UNAVAILABLE')
        except requests.RequestException:raise ImportFailure('NETWORK_ERROR') from None
        return suffix,meta

def tag(path,metadata,suffix):
    """Only bounded text tags; images and source URLs are deliberately not copied."""
    from mutagen.id3 import TIT2,TPE1,TALB,TPE2,TCON
    from mutagen.mp3 import MP3
    from mutagen.mp4 import MP4
    if suffix=='.mp3':
        media=MP3(path)
        if media.tags is None:media.add_tags()
        for key,frame,value in [('TIT2',TIT2,metadata['title']),('TPE1',TPE1,metadata['artist']),('TALB',TALB,metadata['album']),('TPE2',TPE2,metadata['albumArtist']),('TCON',TCON,metadata['genre'])]:
            if value:media.tags[key]=frame(encoding=3,text=[value])
        media.save()
    else:
        media=MP4(path)
        for key,value in [('\xa9nam',metadata['title']),('\xa9ART',metadata['artist']),('\xa9alb',metadata['album']),('aART',metadata['albumArtist']),('\xa9gen',metadata['genre'])]:
            if value:media[key]=[value]
        media.save()
    if path.stat().st_size>audio.MAX_BYTES:raise ImportFailure('SOURCE_LIMIT')
