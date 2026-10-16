import socket,json,subprocess,time,os,tempfile,threading,http.server,sys
seen=[]
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        seen.append({k.lower():v for k,v in self.headers.items()}); self.send_response(404); self.end_headers()
    def log_message(self,*a): pass
srv=http.server.HTTPServer(("127.0.0.1",0),H); threading.Thread(target=srv.serve_forever,daemon=True).start()
port=srv.server_address[1]
sock=tempfile.mktemp(suffix=".sock")
p=subprocess.Popen(["mpv","--no-config","--idle=yes","--vo=null","--ao=null","--no-terminal","--ytdl=no","--input-ipc-server="+sock],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
for _ in range(50):
    if os.path.exists(sock): break
    time.sleep(0.1)
s=socket.socket(socket.AF_UNIX); s.connect(sock); s.settimeout(1.0); f=s.makefile("rwb")
def cmd(*a):
    s.sendall((json.dumps({"command":list(a),"request_id":7})+"\n").encode())
    end=time.time()+3
    while time.time()<end:
        try: l=f.readline()
        except Exception: continue
        if not l: continue
        j=json.loads(l)
        if j.get("request_id")==7: return j


url=f"http://127.0.0.1:{port}/x.mp4"
q=lambda v: f"%{len(v.encode())}%{v}"
tests=[("two plain items, outer quoted","http-header-fields="+q("X-A: 1,X-B: 2")),
 ("one item with comma: q(q(item))","http-header-fields="+q(q("X-Test: a,b"))),
 ("one plain item, outer quoted","http-header-fields="+q("X-A: 1")),
 ("one plain item, unquoted","http-header-fields=X-A: 1"),
 ("two plain, unquoted","http-header-fields=X-A: 1,X-B: 2"),
]
for name,opts in tests:
    seen.clear()
    r=cmd("loadfile",url,"replace",opts)
    time.sleep(1.2)
    print(f"{name:40} cmd={r.get('error') if r else None:10} seen={ {k:v for k,v in (seen[0].items() if seen else []) if k.startswith('x-')} }")
# global option via set_property
for name,val in [("global: two plain","X-G1: 1,X-G2: 2"),("global: comma value q()",q("X-C: a,b")+",X-D: d"),("global: comma value plain","X-C: a,b")]:
    seen.clear(); r=cmd("set_property","http-header-fields",val); cmd("loadfile",url,"replace"); time.sleep(1.2)
    print(f"{name:40} set={r.get('error') if r else None:10} seen={ {k:v for k,v in (seen[0].items() if seen else []) if k.startswith('x-')} }")
p.terminate()
