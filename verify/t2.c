#include <stdio.h>
#include <unistd.h>
#include <mpv/client.h>
static void chk(mpv_handle*h,const char*n,const char*v){int r=mpv_set_option_string(h,n,v);printf("set %-18s=%-10s -> %3d %s\n",n,v,r,mpv_error_string(r));}
int main(){
  mpv_handle*h=mpv_create(); mpv_set_option_string(h,"vo","null"); mpv_set_option_string(h,"ao","null"); mpv_set_option_string(h,"idle","yes");
  chk(h,"no-such-option","1"); chk(h,"cache","maybe"); chk(h,"sub-pos","500"); chk(h,"sub-pos","50"); chk(h,"demuxer-max-bytes","64MiB"); chk(h,"network-timeout","-5"); chk(h,"panscan","2");
  mpv_initialize(h);
  const char*cmd[]={"loadfile","/tmp/t_vod.mp4",NULL}; printf("loadfile: %d\n",mpv_command(h,cmd));
  for(int i=0;i<30;i++){ mpv_event*e=mpv_wait_event(h,0.2); if(e->event_id==MPV_EVENT_FILE_LOADED){printf("FILE_LOADED\n");break;} }
  usleep(500000);
  char*s; const char*props[]={"demuxer-cache-state","track-list/0/type","track-list/count","metadata","duration","seekable","demuxer-via-network","file-format","video-params/aspect","video-params/w","hwdec-current","time-pos",NULL};
  for(int i=0;props[i];i++){int r=mpv_get_property(h,props[i],MPV_FORMAT_STRING,&s);printf("%-26s -> %s %.160s\n",props[i],r<0?mpv_error_string(r):"OK",r<0?"":s);if(r>=0)mpv_free(s);}
  int64_t n; printf("track-list/count as INT64: %d\n", mpv_get_property(h,"track-list/count",MPV_FORMAT_INT64,&n));
  double d; printf("duration as DOUBLE: %d %f\n", mpv_get_property(h,"duration",MPV_FORMAT_DOUBLE,&d), d);
  mpv_terminate_destroy(h);
}
