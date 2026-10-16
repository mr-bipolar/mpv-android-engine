import json,sys
sys.path.insert(0,"."); from probe import run
URL="http://127.0.0.1:8089/live/live.m3u8"
variants=[("default",[]),
 ("cache-secs=3",["--cache-secs=3"]),
 ("profile=low-latency",["--profile=low-latency"]),
 ("low-latency + live_start_index=-1",["--profile=low-latency","--demuxer-lavf-o=live_start_index=-1"]),
 ("live_start_index=-1 only",["--demuxer-lavf-o=live_start_index=-1"]),
 ("cache=no",["--cache=no"]),
]
for name,extra in variants:
    rows=run(URL,10,extra,props=["time-pos","duration","demuxer-cache-time","seekable","paused-for-cache","hwdec-current"])
    lags=[ (r["duration"]-r["time-pos"]) for r in rows if isinstance(r["duration"],(int,float)) and isinstance(r["time-pos"],(int,float))]
    cache=[ (r["demuxer-cache-time"]-r["time-pos"]) for r in rows if isinstance(r["demuxer-cache-time"],(int,float)) and isinstance(r["time-pos"],(int,float))]
    print(f"{name:38} duration-timepos={['%.1f'%x for x in lags]}  cachetime-timepos={['%.1f'%x for x in cache]} seekable={[r['seekable'] for r in rows][-1]}")
