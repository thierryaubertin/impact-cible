package com.example.impacttarget;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    TextureView texture; TextView status; Button reference, scan, reset; Spinner distanceSpinner, sensitivitySpinner; OverlayView overlay;
    CameraDevice camera; CameraCaptureSession session; ImageReader reader; Size imageSize;
    HandlerThread cameraThread; Handler cameraHandler; Bitmap baseline; boolean busy=false;
    final ArrayList<PointF> impacts=new ArrayList<>();
    static final int REQ=10;
    int distanceMeters=25;
    int sensitivity=42;

    @Override public void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_main);
        texture=findViewById(R.id.texture); status=findViewById(R.id.status); reference=findViewById(R.id.reference); scan=findViewById(R.id.scan); reset=findViewById(R.id.reset);
        distanceSpinner=findViewById(R.id.distance); sensitivitySpinner=findViewById(R.id.sensitivity);
        ArrayAdapter<String> dAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"25 m","100 m"});
        distanceSpinner.setAdapter(dAdapter);
        distanceSpinner.setSelection(0);
        distanceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){distanceMeters=(pos==0?25:100);} public void onNothingSelected(AdapterView<?> p){}});
        ArrayAdapter<String> sAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Faible","Normal","Élevée"});
        sensitivitySpinner.setAdapter(sAdapter);
        sensitivitySpinner.setSelection(1);
        sensitivitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){sensitivity=(pos==0?55:(pos==1?42:30));} public void onNothingSelected(AdapterView<?> p){}});
        overlay=new OverlayView(this); addContentView(overlay,new ViewGroup.LayoutParams(-1,-1));
        reference.setOnClickListener(v->capture(true)); scan.setOnClickListener(v->capture(false)); reset.setOnClickListener(v->{baseline=null; impacts.clear(); overlay.invalidate(); status.setText("Nouvelle série : cadrez la cible puis appuyez sur RÉFÉRENCE");});
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){ public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){openCamera();} public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){} public boolean onSurfaceTextureDestroyed(SurfaceTexture s){return true;} public void onSurfaceTextureUpdated(SurfaceTexture s){} });
    }
    @Override protected void onResume(){super.onResume(); cameraThread=new HandlerThread("Camera");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());if(texture.isAvailable())openCamera();}
    @Override protected void onPause(){closeCamera();if(cameraThread!=null){cameraThread.quitSafely();try{cameraThread.join();}catch(Exception e){}}super.onPause();}
    void openCamera(){ if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},REQ);return;} CameraManager cm=(CameraManager)getSystemService(CAMERA_SERVICE);try{String id=null;for(String x:cm.getCameraIdList()){CameraCharacteristics c=cm.getCameraCharacteristics(x);Integer f=c.get(CameraCharacteristics.LENS_FACING);if(f!=null&&f==CameraCharacteristics.LENS_FACING_BACK){id=x;break;}}if(id==null)id=cm.getCameraIdList()[0];CameraCharacteristics cc=cm.getCameraCharacteristics(id);Size[] sizes=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);imageSize=chooseSize(sizes);reader=ImageReader.newInstance(imageSize.getWidth(),imageSize.getHeight(),ImageFormat.JPEG,2);reader.setOnImageAvailableListener(r->{Image im=null;try{im=r.acquireLatestImage();if(im==null)return;ByteBuffer buf=im.getPlanes()[0].getBuffer();byte[] data=new byte[buf.remaining()];buf.get(data);Bitmap bm=BitmapFactory.decodeByteArray(data,0,data.length);processCapture(bm);}finally{if(im!=null)im.close();}},cameraHandler);
        cm.openCamera(id,new CameraDevice.StateCallback(){public void onOpened(CameraDevice c){camera=c;startPreview();}public void onDisconnected(CameraDevice c){c.close();camera=null;}public void onError(CameraDevice c,int e){c.close();camera=null;runOnUiThread(()->status.setText("Erreur caméra : "+e));}},cameraHandler);
    }catch(Exception e){runOnUiThread(()->status.setText("Caméra indisponible : "+e.getMessage()));}}
    Size chooseSize(Size[] s){Size best=s[0];double target=16.0/9.0;for(Size x:s){if(x.getWidth()<800)continue;double ar=(double)x.getWidth()/x.getHeight();if(Math.abs(ar-target)<Math.abs((double)best.getWidth()/best.getHeight()-target))best=x;}return best;}
    void startPreview(){try{SurfaceTexture st=texture.getSurfaceTexture();st.setDefaultBufferSize(imageSize.getWidth(),imageSize.getHeight());Surface preview=new Surface(st);CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);b.addTarget(preview);camera.createCaptureSession(Arrays.asList(preview,reader),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession s){session=s;try{s.setRepeatingRequest(b.build(),null,cameraHandler);}catch(Exception e){}}public void onConfigureFailed(CameraCaptureSession s){}},cameraHandler);}catch(Exception e){}}
    void capture(boolean makeReference){if(camera==null||session==null||busy)return;busy=true;status.setText("Capture…");try{CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);b.addTarget(reader.getSurface());session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){},cameraHandler);}catch(Exception e){busy=false;status.setText("Capture impossible");}}
    void processCapture(Bitmap bm){if(bm==null){busy=false;return;}if(baseline==null){baseline=bm;runOnUiThread(()->status.setText("Référence enregistrée. Tirez puis appuyez sur DÉTECTER."));busy=false;return;}ArrayList<PointF> found=detect(baseline,bm);baseline=bm;impacts.addAll(found);runOnUiThread(()->{status.setText("Nouveaux impacts : "+found.size()+"   |   Total : "+impacts.size());overlay.invalidate();});busy=false;}
    ArrayList<PointF> detect(Bitmap a,Bitmap b){int W=640,H=Math.max(1,(int)(640.0*a.getHeight()/a.getWidth()));Bitmap aa=Bitmap.createScaledBitmap(a,W,H,true),bb=Bitmap.createScaledBitmap(b,W,H,true);int[] pa=new int[W*H],pb=new int[W*H];aa.getPixels(pa,0,W,0,0,W,H);bb.getPixels(pb,0,W,0,0,W,H);boolean[] m=new boolean[W*H];for(int y=2;y<H-2;y++)for(int x=2;x<W-2;x++){int i=y*W+x;int ga=(pa[i]>>16&255)*30+(pa[i]>>8&255)*59+(pa[i]&255)*11;int gb=(pb[i]>>16&255)*30+(pb[i]>>8&255)*59+(pb[i]&255)*11;ga/=100;gb/=100;int d=Math.abs(ga-gb);if(d>sensitivity && gb<150)m[i]=true;}
        // Remove isolated pixels and lighting edges using local neighbor count.
        boolean[] clean=new boolean[W*H];for(int y=2;y<H-2;y++)for(int x=2;x<W-2;x++){int i=y*W+x;if(!m[i])continue;int n=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if(m[(y+dy)*W+x+dx])n++;if(n>=4)clean[i]=true;}
        boolean[] seen=new boolean[W*H];ArrayList<PointF> out=new ArrayList<>();int[] q=new int[W*H];for(int y=3;y<H-3;y++)for(int x=3;x<W-3;x++){int st=y*W+x;if(!clean[st]||seen[st])continue;int head=0,tail=0; q[tail++]=st;seen[st]=true;int count=0,sx=0,sy=0,minx=x,maxx=x,miny=y,maxy=y;while(head<tail){int p=q[head++],py=p/W,px=p%W;count++;sx+=px;sy+=py;minx=Math.min(minx,px);maxx=Math.max(maxx,px);miny=Math.min(miny,py);maxy=Math.max(maxy,py);for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){if(dx==0&&dy==0)continue;int nx=px+dx,ny=py+dy;if(nx<1||ny<1||nx>=W-1||ny>=H-1)continue;int np=ny*W+nx;if(clean[np]&&!seen[np]){seen[np]=true;q[tail++]=np;}}}int bw=maxx-minx+1,bh=maxy-miny+1;int minCount=(distanceMeters==100?6:12); if(count>=minCount&&count<=5000&&bw>=2&&bh>=2&&bw<=140&&bh<=140){out.add(new PointF((float)sx/count/W,(float)sy/count/H));}}
        aa.recycle();bb.recycle();return out;}
    void closeCamera(){try{if(session!=null)session.close();if(camera!=null)camera.close();if(reader!=null)reader.close();}catch(Exception e){}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)openCamera();else status.setText("Autorisation caméra nécessaire.");}

    class OverlayView extends View {
        Paint p=new Paint(3);
        public OverlayView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setBackgroundColor(Color.TRANSPARENT);}
        protected void onDraw(Canvas c){
            super.onDraw(c);
            if(impacts.isEmpty()) return;
            // Liaison rouge entre les impacts, dans l'ordre de détection.
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4); p.setColor(Color.RED);
            for(int i=1;i<impacts.size();i++){
                PointF a=impacts.get(i-1), b=impacts.get(i);
                c.drawLine(a.x*getWidth(),a.y*getHeight(),b.x*getWidth(),b.y*getHeight(),p);
            }
            // Impacts rouges et numérotés.
            p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(150,255,0,0));
            for(int i=0;i<impacts.size();i++){
                PointF q=impacts.get(i); float x=q.x*getWidth(), y=q.y*getHeight();
                c.drawCircle(x,y,15,p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(Color.RED); c.drawCircle(x,y,22,p);
                p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); p.setTextSize(18); p.setTextAlign(Paint.Align.CENTER);
                c.drawText(String.valueOf(i+1),x,y+6,p);
                p.setColor(Color.argb(150,255,0,0));
            }
            // Centre moyen du groupement : petite croix blanche.
            float sx=0,sy=0; for(PointF q:impacts){sx+=q.x;sy+=q.y;} sx/=impacts.size(); sy/=impacts.size();
            float cx=sx*getWidth(), cy=sy*getHeight();
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5); p.setColor(Color.WHITE);
            c.drawLine(cx-28,cy,cx+28,cy,p); c.drawLine(cx,cy-28,cx,cy+28,p);
            p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); p.setTextSize(15); p.setTextAlign(Paint.Align.LEFT);
            c.drawText("Centre du groupement",cx+32,cy-10,p);
        }
    }

}
