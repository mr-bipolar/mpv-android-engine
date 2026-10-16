#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <mpv/client.h>
static void so(mpv_handle*h,const char*n,const char*v){int r=mpv_set_option_string(h,n,v);printf("option %-22s=%-28s -> %d %s\n",n,v,r,mpv_error_string(r));}
static void sp(mpv_handle*h,const char*n,const char*v){int r=mpv_set_property_string(h,n,v);char*s=NULL;int g=mpv_get_property(h,n,MPV_FORMAT_STRING,&s);printf("prop   %-22s=%-12s -> set %d %-26s readback=%s\n",n,v,r,mpv_error_string(r),g>=0?s:"(none)");if(s)mpv_free(s);}
int main(){
  mpv_handle*h=mpv_create(); mpv_set_option_string(h,"vo","null"); mpv_set_option_string(h,"ao","null"); mpv_set_option_string(h,"idle","yes");
  so(h,"profile","low-latency"); so(h,"demuxer-lavf-o-add","live_start_index=-1");
  mpv_initialize(h);
  char*s; mpv_get_property(h,"demuxer-lavf-o",MPV_FORMAT_STRING,&s); printf("demuxer-lavf-o now: %s\n",s); mpv_free(s);
  const char*c[]={"loadfile","/tmp/t_vod.mp4",NULL}; mpv_command(h,c); for(int i=0;i<30;i++){mpv_event*e=mpv_wait_event(h,0.2); if(e->event_id==MPV_EVENT_FILE_LOADED)break;}
  usleep(300000);
  sp(h,"video-aspect-override","no"); sp(h,"video-aspect-override","1.777778"); sp(h,"video-aspect-override","-1");
  sp(h,"video-crop","100x100+10+10"); sp(h,"video-crop",""); sp(h,"video-crop","0x0+0+0");
  sp(h,"video-rotate","90"); sp(h,"video-rotate","no"); sp(h,"video-rotate","0");
  sp(h,"video-zoom","0.5"); sp(h,"panscan","1.0"); sp(h,"keepaspect","no"); sp(h,"keepaspect","yes"); sp(h,"video-unscaled","yes"); sp(h,"video-unscaled","no");
  sp(h,"sid","no"); sp(h,"sub-pos","90"); sp(h,"sub-font-size","40"); sp(h,"sub-visibility","no"); sp(h,"audio-delay","0.25"); sp(h,"speed","1.5"); sp(h,"volume","80"); sp(h,"volume","300");
  sp(h,"hwdec","mediacodec,mediacodec-copy"); sp(h,"deinterlace","yes");
  mpv_terminate_destroy(h);
}
