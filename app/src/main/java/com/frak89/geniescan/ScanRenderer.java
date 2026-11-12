package com.frak89.geniescan;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.Surface;
import com.google.ar.core.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.*;
import java.util.*;
import java.util.Locale;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** ARCore point/depth capture + interactive OpenGL ES preview, no false AI claims. */
public final class ScanRenderer implements GLSurfaceView.Renderer {
    private final MainActivity activity;
    private final Geometry.Cloud cloud=new Geometry.Cloud();
    private volatile Session session;
    public volatile boolean studio=false,scanning=true,points=true,wire=false,solid=true,cull=false,autoPhotos=false,trimPreview=false;
    private volatile boolean focusNext=false,photoNext=false;
    private volatile float tapX=-1,tapY=-1;
    private boolean focus=false;
    private float[] focusPosition=new float[3];
    private float radius=.65f;
    private int width=1,height=1,tex=0,bgProgram=0,modelProgram=0;
    private int frames=0,photos=0,rejectedPhotos=0,coverageMask=0;
    private long lastAutoAttempt=0;
    private float[] lastCapturedPosition;

    private long lastDepth=0,lastStatus=0;
    private FloatBuffer cloudBuffer,triangleBuffer,edgeBuffer;
    private int cachedCount=-1;
    private float[] mvp=new float[16],view=new float[16],projection=new float[16];
    private float yaw=.7f,pitch=.25f,distance=2.6f;
    private float[] centre={0,0,0};
    private volatile Geometry.Mesh mesh;
    private String shape="BOX";private int level=0;
    private final float[] QUAD={-1,-1,1,-1,-1,1,1,1};
    private final float[] UV={0,1,1,1,0,0,1,0};
    private FloatBuffer uvBuffer;
    private FloatBuffer quadBuffer;

    ScanRenderer(MainActivity a){activity=a;}
    void setSession(Session s){session=s;}
    void requestFocus(){focusNext=true;}
    void focusAt(float x,float y){tapX=x;tapY=y;focusNext=true;}
    void requestPhoto(){photoNext=true;}
    void orbit(float dx,float dy){yaw+=dx*.008f;pitch=Math.max(-1.25f,Math.min(1.25f,pitch+dy*.008f));}
    void clear(){cloud.clear();mesh=null;cachedCount=-1;focus=false;photos=0;coverageMask=0;trimPreview=false;lastCapturedPosition=null;}
    int trimFocused(){
        if(!focus)return 0;
        int removed=cloud.removeSphere(focusPosition,radius);
        cachedCount=-1;trimPreview=false;mesh=null;return removed;
    }
    void build(String s,int l){
        shape=s;level=l;float[] b=cloud.bounds();
        if(focus){float pad=radius; b=new float[]{focusPosition[0]-pad,focusPosition[1]-pad,focusPosition[2]-pad,
                                                  focusPosition[0]+pad,focusPosition[1]+pad,focusPosition[2]+pad};}
        mesh=Geometry.fit(s,l,b);
        centre=new float[]{(b[0]+b[3])/2,(b[1]+b[4])/2,(b[2]+b[5])/2};
        triangleBuffer=buffer(mesh.triangles());edgeBuffer=buffer(mesh.edges());
    }
    String exportObj(){
        Geometry.Mesh m=mesh;if(m!=null)return m.obj();
        float[] p=cloud.snapshot();StringBuilder s=new StringBuilder("# GenieScan raw 3D point cloud: vertices only\n");
        for(int i=0;i<p.length;i+=3)s.append(String.format(Locale.US,"v %.6f %.6f %.6f\n",p[i],p[i+1],p[i+2]));
        return s.toString();
    }
    @Override public void onSurfaceCreated(GL10 unused,EGLConfig cfg){
        GLES20.glClearColor(.06f,.085f,.10f,1);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        int[] a=new int[1];GLES20.glGenTextures(1,a,0);tex=a[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        String bgVertex="attribute vec2 aPos;attribute vec2 aUv;varying vec2 vUv;void main(){vUv=aUv;gl_Position=vec4(aPos,0.999,1.);}";
        String bgFrag="#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 vUv;uniform samplerExternalOES uTex;void main(){gl_FragColor=texture2D(uTex,vUv);}";
        bgProgram=program(bgVertex,bgFrag);
        String modelVertex="uniform mat4 uMvp;attribute vec3 aPosition;void main(){gl_Position=uMvp*vec4(aPosition,1.);gl_PointSize=4.0;}";
        String modelFragment="precision mediump float;uniform vec4 uColor;void main(){gl_FragColor=uColor;}";
        modelProgram=program(modelVertex,modelFragment);
        quadBuffer=buffer(QUAD);uvBuffer=buffer(UV);
        if(mesh!=null){triangleBuffer=buffer(mesh.triangles());edgeBuffer=buffer(mesh.edges());}
        cachedCount=-1;
    }
    @Override public void onSurfaceChanged(GL10 unused,int w,int h){
        width=Math.max(1,w);height=Math.max(1,h);GLES20.glViewport(0,0,width,height);
    }
    @Override public void onDrawFrame(GL10 unused){
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
        Session s=session;
        if(s==null)return;
        try {
            s.setCameraTextureName(tex);
            int rotation=activity.getWindowManager().getDefaultDisplay().getRotation();
            s.setDisplayGeometry(rotation,width,height);
            Frame frame=s.update();Camera camera=frame.getCamera();
            if(!studio)drawBackground(frame);
            if(camera.getTrackingState()==TrackingState.TRACKING){
                if(focusNext){tryFocus(frame,camera);focusNext=false;}
                if(scanning)acquire(frame,camera);
                if(photoNext){photoNext=false;capturePhoto(frame);}
                if(scanning && autoPhotos && photos<48){
                    long clock=System.currentTimeMillis();
                    if(clock-lastAutoAttempt>2600){
                        float[] cameraPosition=camera.getPose().getTranslation();
                        float motion=lastCapturedPosition==null?1:dist(cameraPosition,lastCapturedPosition);
                        if(motion>.16f){
                            lastAutoAttempt=clock;
                            if(capturePhoto(frame))lastCapturedPosition=cameraPosition;
                        }
                    }
                }
                if(focus){
                    float[] p=camera.getPose().getTranslation();
                    int sector=(int)Math.floor((Math.atan2(p[2]-focusPosition[2],p[0]-focusPosition[0])+Math.PI)*4/Math.PI);
                    coverageMask |= (1<<(Math.floorMod(sector,8)));
                }
                if(studio) {
                    float cx=centre[0],cy=centre[1],cz=centre[2];
                    float ex=cx+distance*(float)(Math.sin(yaw)*Math.cos(pitch));
                    float ey=cy+distance*(float)Math.sin(pitch);
                    float ez=cz+distance*(float)(Math.cos(yaw)*Math.cos(pitch));
                    Matrix.setLookAtM(view,0,ex,ey,ez,cx,cy,cz,0,1,0);
                    Matrix.perspectiveM(projection,0,60,(float)width/height,.05f,150);
                } else {
                    camera.getViewMatrix(view,0);camera.getProjectionMatrix(projection,0,.05f,150);
                }
                Matrix.multiplyMM(mvp,0,projection,0,view,0);
                if(points)drawCloud();
                if(mesh!=null){if(solid)drawMesh();if(wire)drawWire();}
            }
            long now=System.currentTimeMillis();
            if(now-lastStatus>1100){
                lastStatus=now;
                Geometry.Mesh m=mesh;
                String info=(studio?"STUDIO":"CAMERA")+" · Tracking "+camera.getTrackingState()+
                    "\nPunti: "+cloud.size()+" · Foto: "+photos+" (scartate: "+rejectedPhotos+")"+
                    (focus?" · Focus r="+radius+"m · angoli "+Integer.bitCount(coverageMask)+"/8":"")+
                    (trimPreview?" · VIOLA = area da eliminare":"")+
                    (autoPhotos?" · AUTO FOTO":"")+
                    (m!=null?"\n"+shape+" · Vertici "+m.vertices.size()+" · Tris "+m.faces.size()+" · Quads "+m.quads+" · "+(level==0?"LOW":level==1?"MID":"HIGH"):
                    "\nMuovi il telefono lentamente; tocca per FOCUS.");
                activity.status(info);
            }
        }catch(Exception e){
            long now=System.currentTimeMillis();
            if(now-lastStatus>1400){lastStatus=now;activity.status("Acquisizione sospesa: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        }
    }
    private void tryFocus(Frame f,Camera c){
        List<HitResult> hits=f.hitTest(tapX>=0?tapX:width/2f,tapY>=0?tapY:height/2f);
        tapX=-1;tapY=-1;
        if(!hits.isEmpty()){focusPosition=hits.get(0).getHitPose().getTranslation();}
        else {
            Pose pose=c.getPose();focusPosition=new float[3];
            pose.transformPoint(new float[]{0,0,-.85f},0,focusPosition,0);
        }
        focus=true;centre=focusPosition.clone();
    }
    private void acquire(Frame f,Camera camera){
        frames++;
        if(frames%4==0)try(com.google.ar.core.PointCloud pc=f.acquirePointCloud()){
            FloatBuffer buf=pc.getPoints();buf.rewind();
            while(buf.remaining()>=4){float x=buf.get(),y=buf.get(),z=buf.get(),conf=buf.get();store(x,y,z,conf);}
        }catch(Exception ignored){}
        long now=System.currentTimeMillis();
        if(now-lastDepth<230)return;lastDepth=now;
        try(Image depth=f.acquireDepthImage16Bits()){
            CameraIntrinsics in=camera.getImageIntrinsics();
            float[] focal=new float[2],principal=new float[2];int[] dims=new int[2];
            in.getFocalLength(focal,0);in.getPrincipalPoint(principal,0);in.getImageDimensions(dims,0);
            int dw=depth.getWidth(),dh=depth.getHeight();float sx=(float)dw/dims[0],sy=(float)dh/dims[1];
            float fx=focal[0]*sx,fy=focal[1]*sy,cx=principal[0]*sx,cy=principal[1]*sy;
            Image.Plane pl=depth.getPlanes()[0];ByteBuffer bb=pl.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
            int step=Math.max(8,Math.min(dw,dh)/28);
            Pose pose=camera.getPose();float[] local=new float[3],world=new float[3];
            for(int y=step/2;y<dh;y+=step)for(int x=step/2;x<dw;x+=step){
                int at=y*pl.getRowStride()+x*pl.getPixelStride();
                if(at+1>=bb.limit())continue;
                int millimeters=bb.getShort(at)&0xffff;
                if(millimeters<200||millimeters>6000)continue;
                float z=millimeters/1000f;
                local[0]=(x-cx)*z/fx;local[1]=-(y-cy)*z/fy;local[2]=-z;
                pose.transformPoint(local,0,world,0);
                store(world[0],world[1],world[2],.8f);
            }
        }catch(Exception ignored){}
    }
    private void store(float x,float y,float z,float conf){
        if(focus){float dx=x-focusPosition[0],dy=y-focusPosition[1],dz=z-focusPosition[2];
            if(dx*dx+dy*dy+dz*dz>radius*radius)return;
        }
        cloud.add(x,y,z,conf);
    }
    private void drawBackground(Frame frame){
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        float[] coords=new float[8];
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,QUAD,
                                      Coordinates2d.TEXTURE_NORMALIZED,coords);
        uvBuffer=buffer(coords);
        GLES20.glUseProgram(bgProgram);
        int p=GLES20.glGetAttribLocation(bgProgram,"aPos"),uv=GLES20.glGetAttribLocation(bgProgram,"aUv");
        GLES20.glEnableVertexAttribArray(p);quadBuffer.position(0);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,0,quadBuffer);
        GLES20.glEnableVertexAttribArray(uv);uvBuffer.position(0);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,uvBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,tex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProgram,"uTex"),0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(p);GLES20.glDisableVertexAttribArray(uv);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }
    private void drawCloud(){
        int n=cloud.size();
        if(n!=cachedCount||cloudBuffer==null){cachedCount=n;cloudBuffer=buffer(cloud.snapshot());}
        draw(cloudBuffer,n,GLES20.GL_POINTS,new float[]{.25f,.97f,.94f,1},false);
        if(trimPreview&&focus){
            float[] selected=cloud.insideSphere(focusPosition,radius);
            draw(buffer(selected),selected.length/3,GLES20.GL_POINTS,new float[]{.84f,.20f,.98f,1},false);
        }
    }
    private void drawMesh(){
        if(triangleBuffer==null)triangleBuffer=buffer(mesh.triangles());
        draw(triangleBuffer,mesh.faces.size()*3,GLES20.GL_TRIANGLES,new float[]{.76f,.79f,.86f,1},cull);
    }
    private void drawWire(){
        if(edgeBuffer==null)edgeBuffer=buffer(mesh.edges());
        draw(edgeBuffer,edgeBuffer.capacity()/3,GLES20.GL_LINES,new float[]{.75f,.30f,.96f,1},false);
    }
    private void draw(FloatBuffer data,int vertexCount,int mode,float[] color,boolean faceCull){
        if(data==null||vertexCount==0)return;
        GLES20.glUseProgram(modelProgram);
        if(faceCull){GLES20.glEnable(GLES20.GL_CULL_FACE);GLES20.glCullFace(GLES20.GL_BACK);}
        else GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(modelProgram,"uMvp"),1,false,mvp,0);
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(modelProgram,"uColor"),1,color,0);
        int pos=GLES20.glGetAttribLocation(modelProgram,"aPosition");
        data.position(0);GLES20.glEnableVertexAttribArray(pos);
        GLES20.glVertexAttribPointer(pos,3,GLES20.GL_FLOAT,false,0,data);
        GLES20.glDrawArrays(mode,0,vertexCount);
        GLES20.glDisableVertexAttribArray(pos);GLES20.glDisable(GLES20.GL_CULL_FACE);
    }
    private boolean capturePhoto(Frame frame){
        try(Image im=frame.acquireCameraImage()){
            int w=im.getWidth(),h=im.getHeight();
            Image.Plane[] planes=im.getPlanes();ByteBuffer y=planes[0].getBuffer();
            int stride=planes[0].getRowStride(),pixel=planes[0].getPixelStride();
            double sharpness=0;int n=0;
            for(int yy=20;yy<h-20;yy+=12)for(int xx=20;xx<w-20;xx+=12){
                int at=yy*stride+xx*pixel;
                sharpness+=Math.abs((y.get(at)&255)-(y.get(at+pixel*3)&255));n++;
            }
            if(n==0||sharpness/n<7.0){rejectedPhotos++;return false;}
            byte[] nv21=new byte[w*h*3/2];
            for(int yy=0;yy<h;yy++)for(int xx=0;xx<w;xx++)nv21[yy*w+xx]=y.get(yy*stride+xx*pixel);
            for(int yy=0;yy<h/2;yy++)for(int xx=0;xx<w/2;xx++){
                int dst=w*h+yy*w+xx*2;
                for(int k=0;k<2;k++){
                    Image.Plane p=planes[k==0?2:1];
                    int at=yy*p.getRowStride()+xx*p.getPixelStride();
                    if(at<p.getBuffer().limit())nv21[dst+k]=p.getBuffer().get(at);
                }
            }
            File dir=new File(activity.getCacheDir(),"keyframes");dir.mkdirs();
            File out=new File(dir,"view_"+System.currentTimeMillis()+".jpg");
            try(FileOutputStream stream=new FileOutputStream(out)){
                new YuvImage(nv21,ImageFormat.NV21,w,h,null).compressToJpeg(new Rect(0,0,w,h),87,stream);
            }
            photos++;return true;
        }catch(Exception ignored){rejectedPhotos++;return false;}
    }
    private float dist(float[] a,float[] b){
        float x=a[0]-b[0],y=a[1]-b[1],z=a[2]-b[2];
        return (float)Math.sqrt(x*x+y*y+z*z);
    }
    private FloatBuffer buffer(float[] f){
        ByteBuffer b=ByteBuffer.allocateDirect(f.length*4).order(ByteOrder.nativeOrder());
        FloatBuffer out=b.asFloatBuffer();out.put(f);out.position(0);return out;
    }
    private int shader(int type,String src){
        int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);
        int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0)throw new RuntimeException("GL shader: "+GLES20.glGetShaderInfoLog(s));
        return s;
    }
    private int program(String vs,String fs){
        int p=GLES20.glCreateProgram();GLES20.glAttachShader(p,shader(GLES20.GL_VERTEX_SHADER,vs));
        GLES20.glAttachShader(p,shader(GLES20.GL_FRAGMENT_SHADER,fs));GLES20.glLinkProgram(p);
        int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new RuntimeException("GL program: "+GLES20.glGetProgramInfoLog(p));
        return p;
    }
}
