import socket,json,subprocess,time,sys,os,tempfile
def run(url,secs=10,extra=[],props=None):
    sock=tempfile.mktemp(suffix=".sock")
    p=subprocess.Popen(["mpv","--no-config","--idle=yes","--vo=null","--ao=null","--no-terminal","--input-ipc-server="+sock]+extra,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    for _ in range(50):
        if os.path.exists(sock): break
        time.sleep(0.1)
    s=socket.socket(socket.AF_UNIX); s.connect(sock); s.settimeout(0.5); f=s.makefile("rwb")
    def cmd(*a):
        s.sendall((json.dumps({"command":list(a)})+"\n").encode())
        end=time.time()+2
        while time.time()<end:
            try: line=f.readline()
            except Exception: continue
            if not line: continue
            j=json.loads(line)
            if "error" in j: return j
        return None
    def get(n):
        r=cmd("get_property",n); 
        return r.get("data") if r and r.get("error")=="success" else ("<"+(r.get("error") if r else "?")+">")
    cmd("loadfile",url)
    props=props or ["duration","seekable","partially-seekable","demuxer-via-network","file-format","time-pos","demuxer-cache-time","demuxer-cache-duration","paused-for-cache","eof-reached","idle-active","core-idle"]
    rows=[]
    t0=time.time()
    while time.time()-t0<secs:
        time.sleep(secs/4.0)
        rows.append({n:get(n) for n in props})
        cs=get("demuxer-cache-state"); 
        rows[-1]["ranges"]=cs.get("seekable-ranges") if isinstance(cs,dict) else cs
    p.terminate(); 
    return rows
if __name__=="__main__":
    for name,url,extra in [("LIVE HLS (no ENDLIST)","http://127.0.0.1:8089/live/live.m3u8",[]),("VOD HLS (ENDLIST)","http://127.0.0.1:8089/vodhls/vod.m3u8",[]),("HTTP mp4, server w/o Range","http://127.0.0.1:8089/vodhls/t_vod.mp4",[]),("local file","/tmp/t_vod.mp4",[])]:
        print("==",name)
        for r in run(url,8,extra): print("  ",json.dumps(r))
