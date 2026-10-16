#!/bin/bash
cd /tmp
rm -rf /tmp/live /tmp/vodhls; mkdir -p /tmp/live /tmp/vodhls
ffmpeg -loglevel error -f lavfi -i "testsrc=size=320x240:rate=25:duration=20" -f lavfi -i "sine=frequency=440:duration=20" -c:v libx264 -preset ultrafast -g 25 -c:a aac -f hls -hls_time 2 -hls_playlist_type vod /tmp/vodhls/vod.m3u8 < /dev/null
setsid nohup ffmpeg -loglevel error -re -f lavfi -i "testsrc=size=320x240:rate=25" -f lavfi -i "sine=frequency=440" -c:v libx264 -preset ultrafast -g 25 -c:a aac -f hls -hls_time 2 -hls_list_size 6 -hls_flags delete_segments+omit_endlist /tmp/live/live.m3u8 > /tmp/live_ff.log 2>&1 < /dev/null &
echo $! > /tmp/live_ff.pid
setsid nohup python3 -m http.server 8089 --bind 127.0.0.1 --directory /tmp > /tmp/http.log 2>&1 < /dev/null &
echo $! > /tmp/http.pid
