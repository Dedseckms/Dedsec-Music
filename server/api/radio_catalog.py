# SPDX-License-Identifier: GPL-3.0-or-later
"""Read-only Navidrome catalogue; user_library and annotations scope every request."""
import contextlib
import datetime
import json
from pathlib import Path, PurePosixPath
import sqlite3
import time


def epoch(value):
    if not value:return 0
    try:
        parsed=datetime.datetime.fromisoformat(str(value).replace('Z','+00:00'))
        if parsed.tzinfo is None:parsed=parsed.replace(tzinfo=datetime.timezone.utc)
        return int(parsed.timestamp()*1000)
    except (ValueError,TypeError):return 0


class Catalog:
    def __init__(self,path,roots):
        self.path=Path(path);self.roots={str(key):Path(value).absolute() for key,value in roots.items()}
        if self.path.is_symlink() or not self.path.is_file():raise ValueError('Physical Nav database required')
        for root in self.roots.values():
            if not root.is_dir() or root.is_symlink() or root.resolve()!=root:raise ValueError('Physical music roots required')

    @contextlib.contextmanager
    def db(self):
        db=sqlite3.connect(self.path.resolve().as_uri()+'?mode=ro',uri=True,timeout=.25);db.row_factory=sqlite3.Row
        deadline=time.monotonic()+3
        db.execute('PRAGMA query_only=ON');db.set_progress_handler(lambda:int(time.monotonic()>deadline),10000)
        try:yield db
        finally:db.close()

    def songs(self,uid=None):
        """Global metadata only for indexing; UID selection never reads another user's annotations."""
        with self.db() as db:
            columns='''m.id,m.library_id,m.path,substr(m.title,1,320) AS title,substr(m.artist,1,320) AS artist,
                m.artist_id,substr(m.album,1,320) AS album,m.album_id,m.has_cover_art,m.duration,m.size,m.suffix,m.missing'''
            if uid is None:
                query='SELECT '+columns+',0 AS starred,0 AS play_count,0 AS rating,NULL AS play_date,NULL AS starred_at FROM media_file m WHERE m.missing=0'
                args=()
            else:
                query='SELECT '+columns+''',COALESCE(a.starred,0) AS starred,COALESCE(a.play_count,0) AS play_count,
                  COALESCE(a.rating,0) AS rating,a.play_date,a.starred_at FROM media_file m
                  LEFT JOIN annotation a ON a.item_id=m.id AND a.item_type='media_file' AND a.user_id=?
                  WHERE m.missing=0 AND EXISTS(SELECT 1 FROM user_library u WHERE u.library_id=m.library_id AND u.user_id=?)'''
                args=(uid,uid)
            rows={row['id']:dict(row) for row in db.execute(query+' ORDER BY m.id LIMIT 100001',args)}
            if len(rows)>100_000:raise ValueError('Radio catalogue exceeds configured limit')
            for row in rows.values():row['genres']=[]
            genre_query='''SELECT mt.media_file_id,substr(t.tag_value,1,120) FROM media_file_tags mt
                JOIN tag t ON t.id=mt.tag_id JOIN media_file m ON m.id=mt.media_file_id
                WHERE t.tag_name='genre' AND m.missing=0'''
            genre_args=()
            if uid is not None:
                genre_query+=' AND EXISTS(SELECT 1 FROM user_library u WHERE u.library_id=m.library_id AND u.user_id=?)';genre_args=(uid,)
            for song,genre in db.execute(genre_query,genre_args):
                if song in rows and len(rows[song]['genres'])<20:rows[song]['genres'].append(str(genre)[:120])
        return rows

    def song(self,uid,key):
        """One indexed ID for feedback; never expand the whole metadata/genre catalogue."""
        with self.db() as db:
            row=db.execute('''SELECT m.id,m.duration FROM media_file m WHERE m.id=? AND m.missing=0
                AND EXISTS(SELECT 1 FROM user_library u WHERE u.library_id=m.library_id AND u.user_id=?)''',(key,uid)).fetchone()
        return dict(row) if row else None

    def physical(self,song):
        root=self.roots.get(str(song['library_id']));value=song['path']
        if root is None or not isinstance(value,str) or '\\' in value:return None
        relative=PurePosixPath(value)
        if relative.is_absolute() or any(part in ('..','.') for part in relative.parts):return None
        path=root/value
        try:
            if path.is_symlink() or path.resolve()!=path or root not in path.parents or not path.is_file():return None
            info=path.stat()
            return path,info.st_size,info.st_mtime_ns
        except OSError:return None


def dto(song):
    """OpenSubsonic entry shape; false starred is omitted rather than a nonempty string."""
    def text(key):return str(song.get(key) or '')[:320]
    suffix=text('suffix').lower();genres=song.get('genres',[])
    result={'id':text('id'),'title':text('title'),'artist':text('artist'),'artistId':text('artist_id'),
        'album':text('album'),'albumId':text('album_id'),'duration':max(0,int(song.get('duration') or 0)),
        'size':max(0,int(song.get('size') or 0)),'suffix':suffix,
        'contentType':{'mp3':'audio/mpeg','m4a':'audio/mp4','flac':'audio/flac','ogg':'audio/ogg','opus':'audio/ogg','wav':'audio/wav'}.get(suffix,'audio/octet-stream'),
        'genre':genres[0] if genres else '','genres':[{'name':genre} for genre in genres],
        'coverArt':('mf-'+text('id')) if song.get('has_cover_art') else ('al-'+text('album_id') if song.get('album_id') else ''),
        'playCount':max(0,int(song.get('play_count') or 0)),'userRating':max(0,min(5,int(song.get('rating') or 0))),
        'sourceFilename':PurePosixPath(song.get('path') or '').name[:220]}
    if song.get('starred'):result['starred']=str(song.get('starred_at') or 'true')
    return result
