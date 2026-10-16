#include <stdio.h>
#include <mpv/client.h>
int main(){
  mpv_handle*h=mpv_create(); mpv_set_option_string(h,"vo","null"); mpv_set_option_string(h,"ao","null"); mpv_set_option_string(h,"idle","yes");
  mpv_initialize(h);
  const char*cmd[]={"loadfile","/tmp/t_vod.mp4",NULL};
  char*s; int r;
  const char*props[]={"mpv-version","ffmpeg-version","audio-device-list","option-info/sub-pos/min","option-info/sub-pos/max","option-info/sub-pos/default-value","option-info/cache-secs/default-value","option-info/demuxer-max-bytes/default-value","option-info/network-timeout/default-value","option-info/sub-font-size/default-value","option-info/demuxer-readahead-secs/default-value","option-info/panscan/max","option-info/does-not-exist/name","option-info/hwdec/choices","metadata","playlist","demuxer-cache-state",NULL};
  for(int i=0;props[i];i++){ r=mpv_get_property(h,props[i],MPV_FORMAT_STRING,&s); printf("%-48s -> %s %s\n",props[i], r<0?mpv_error_string(r):"OK", r<0?"":s); if(r>=0)mpv_free(s);}
  printf("client api: %lu\n", mpv_client_api_version());
  printf("set bad option: %d (%s)\n", r=mpv_set_option_string(h,"no-such-option","1"), mpv_error_string(r));
  printf("set bad value : %d (%s)\n", r=mpv_set_option_string(h,"cache","maybe"), mpv_error_string(r));
  printf("set range bad : %d (%s)\n", r=mpv_set_option_string(h,"sub-pos","500"), mpv_error_string(r));
  printf("ok value      : %d\n", mpv_set_option_string(h,"demuxer-max-bytes","64MiB"));
  printf("lavf-o        : %d\n", mpv_set_option_string(h,"stream-lavf-o","reconnect=1,reconnect_streamed=1,reconnect_delay_max=5,reconnect_on_network_error=1"));
  printf("bogus lavf-o  : %d\n", mpv_set_option_string(h,"stream-lavf-o","bogus_key_xyz=1"));
  mpv_terminate_destroy(h);
}
