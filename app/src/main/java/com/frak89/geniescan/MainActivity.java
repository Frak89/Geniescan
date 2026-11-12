package com.frak89.geniescan;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final int CAMERA_REQUEST=71, SAVE_REQUEST=72, MODEL_REQUEST=73;
    private GLSurfaceView gl;
    private ScanRenderer render;
    private Session session;
    private TextView info;
    private boolean requestedInstall=false;
    private boolean studio=false, points=true, wire=false, solid=true, cull=false, scanning=true;
    private int level=0, shape=0;
    private final String[] shapes={"BOX","CILINDRO","VASO","TAVOLO"};
    private final String[] levels={"LOW","MID","HIGH"};
    private int cyan=Color.rgb(26,195,196), violet=Color.rgb(160,76,234);
    private float lastX,lastY;
    private boolean canGlResume=false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(12,18,23));
        getWindow().setNavigationBarColor(Color.rgb(12,18,23));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12,18,23));
        TextView title=new TextView(this);title.setText("GENIESCAN   •   CLOUD / SMART POLY   v0.1");
        title.setTextColor(Color.WHITE);title.setTextSize(15);title.setPadding(18,12,10,10);
        title.setTypeface(null,1);root.addView(title);
        info=new TextView(this);info.setTextColor(0xffb0ced8);info.setTextSize(12);
        info.setMinLines(2);info.setPadding(14,4,14,10);
        info.setText("Avvio ARCore. Muovi lentamente la camera per rilevare la geometria.");
        root.addView(info);
        gl=new GLSurfaceView(this); gl.setEGLContextClientVersion(2);
        gl.setPreserveEGLContextOnPause(true);
        render=new ScanRenderer(this);
        gl.setRenderer(render); gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(gl,new LinearLayout.LayoutParams(-1,0,1));
        gl.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE && studio){
                float dx=e.getX()-lastX, dy=e.getY()-lastY;
                gl.queueEvent(()->render.orbit(dx,dy));lastX=e.getX();lastY=e.getY();return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP && !studio){
                float x=e.getX(),y=e.getY();
                gl.queueEvent(()->render.focusAt(x,y));
                return true;
            }
            return true;
        });
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);
        root.addView(actions);
        HorizontalScrollView row1=scroll(actions);LinearLayout b1=(LinearLayout)row1.getChildAt(0);
        HorizontalScrollView row2=scroll(actions);LinearLayout b2=(LinearLayout)row2.getChildAt(0);
        add(b1,"SCANSIONE",true,()->{scanning=!scanning;render.scanning=scanning;refresh();});
        add(b1,"STUDIO",false,()->{studio=!studio;render.studio=studio;refresh();});
        add(b1,"PUNTI",true,()->{points=!points;render.points=points;refresh();});
        add(b1,"SOLIDO",true,()->{solid=!solid;render.solid=solid;refresh();});
        add(b1,"WIRE",false,()->{wire=!wire;render.wire=wire;refresh();});
        add(b1,"CULL",false,()->{cull=!cull;render.cull=cull;refresh();});
        add(b2,"FOCUS",false,()->{render.requestFocus();toast("Inquadra e tocca l'oggetto da isolare");});
        add(b2,"FORMA: "+shapes[shape],false,()->{shape=(shape+1)%shapes.length;rebuild();refresh();});
        add(b2,"POLY: "+levels[level],false,()->{level=(level+1)%3;rebuild();refresh();});
        add(b2,"RICOSTRUISCI",false,this::rebuild);
        add(b2,"FOTO",false,()->{render.requestPhoto();toast("Cattura JPEG: filtro nitidezza attivo");});
        add(b2,"AUTO FOTO",false,()->{render.autoPhotos=!render.autoPhotos;refresh();});
        add(b2,"VIOLA",false,()->{
            render.trimPreview=!render.trimPreview;
            toast(render.trimPreview?"Punti nel raggio FOCUS evidenziati in viola":"Selezione disattivata");
            refresh();
        });
        add(b2,"ELIMINA",false,()->{gl.queueEvent(()->{
            int count=render.trimFocused();
            runOnUiThread(()->toast("Punti eliminati: "+count));
        });refresh();});
        add(b2,"OBJ",false,this::export);
        add(b2,"MODELLO IA",false,this::chooseModel);
        add(b2,"CAMERE",false,this::showCameras);
        add(b2,"PULISCI",false,()->{gl.queueEvent(()->render.clear());toast("Nuvola ripulita");});
        setContentView(root);
        refresh();
    }
    private HorizontalScrollView scroll(LinearLayout parent) {
        HorizontalScrollView sc=new HorizontalScrollView(this);sc.setFillViewport(false);sc.setHorizontalScrollBarEnabled(false);
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(4,3,4,3);sc.addView(l);parent.addView(sc);return sc;
    }
    private void add(LinearLayout l,String label,boolean selected,Runnable run) {
        Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(10);
        b.setMinHeight(42);b.setAllCaps(false);b.setTag(label.split(":")[0]);
        b.setBackgroundTintList(ColorStateList.valueOf(selected?cyan:0xff33444c));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,48);lp.setMargins(3,1,3,1);l.addView(b,lp);
        b.setOnClickListener(v->run.run());
    }
    private void refresh() {
        LinearLayout outer=(LinearLayout)((View)gl.getParent());
        LinearLayout actions=(LinearLayout)outer.getChildAt(outer.getChildCount()-1);
        for(int i=0;i<actions.getChildCount();i++){
            LinearLayout row=(LinearLayout)((HorizontalScrollView)actions.getChildAt(i)).getChildAt(0);
            for(int j=0;j<row.getChildCount();j++){
                Button b=(Button)row.getChildAt(j);String txt=b.getText().toString();
                if(txt.startsWith("FORMA:"))b.setText("FORMA: "+shapes[shape]);
                if(txt.startsWith("POLY:"))b.setText("POLY: "+levels[level]);
                String label=b.getText().toString();
                boolean active=(label.equals("SCANSIONE")&&scanning)||(label.equals("STUDIO")&&studio)||
                    (label.equals("PUNTI")&&points)||(label.equals("WIRE")&&wire)||
                    (label.equals("SOLIDO")&&solid)||(label.equals("CULL")&&cull)||
                    (label.equals("AUTO FOTO")&&render.autoPhotos)||(label.equals("VIOLA")&&render.trimPreview);
                b.setBackgroundTintList(ColorStateList.valueOf(active?cyan:0xff33444c));
            }
        }
    }
    private void rebuild() {
        gl.queueEvent(()->{render.build(shapes[shape],level);});
    }
    public void status(String text) {runOnUiThread(()->info.setText(text));}
    private void toast(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_SHORT).show());}
    private void export() {
        Intent in=new Intent(Intent.ACTION_CREATE_DOCUMENT);in.setType("model/obj");
        in.addCategory(Intent.CATEGORY_OPENABLE);in.putExtra(Intent.EXTRA_TITLE,"GenieScan_scene.obj");
        startActivityForResult(in,SAVE_REQUEST);
    }
    private void chooseModel() {
        ModelFile.Descriptor old=null;
        try { old=ModelFile.selected(this); }catch(Exception ignored){}
        if(old!=null) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("Modello IA già selezionato")
                .setMessage(old.summary()+"\n\nVuoi cambiare file?")
                .setNegativeButton("MANTIENI",null)
                .setPositiveButton("CAMBIA",(dialog,which)->openModelPicker())
                .show();
        } else openModelPicker();
    }
    private void openModelPicker() {
        Intent in=new Intent(Intent.ACTION_OPEN_DOCUMENT);in.setType("*/*");
        in.addCategory(Intent.CATEGORY_OPENABLE);
        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(in,MODEL_REQUEST);
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null) return;
        Uri uri=data.getData();
        if(req==SAVE_REQUEST){
            gl.queueEvent(()->{
                String obj=render.exportObj();
                runOnUiThread(()->{
                    try(OutputStream o=getContentResolver().openOutputStream(uri)){
                        if(o==null)throw new Exception("Output null");
                        o.write(obj.getBytes(StandardCharsets.UTF_8));
                        toast("OBJ salvato ("+obj.length()+" caratteri)");
                    }catch(Exception e){toast("Errore esportazione: "+e.getMessage());}
                });
            });
        } else if(req==MODEL_REQUEST) {
            try{
                ModelFile.Descriptor model=ModelFile.choose(this,uri,data);
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Modello IA locale")
                    .setMessage(model.summary()+"\n\nFile esterno preservato. L'inferenza non è ancora integrata.")
                    .setPositiveButton("OK",null).show();
            }catch(Exception e){toast("Modello non disponibile: "+e.getMessage());}
        }
    }
    private void showCameras() {
        StringBuilder s=new StringBuilder();
        try{
            CameraManager cm=(CameraManager)getSystemService(CAMERA_SERVICE);
            for(String id:cm.getCameraIdList()){
                CameraCharacteristics c=cm.getCameraCharacteristics(id);
                float[] lens=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float[] aps=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                int[] caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                android.util.SizeF sensor=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                boolean depth=false;if(caps!=null)for(int a:caps)if(a==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)depth=true;
                s.append("Cam ").append(id).append("  f(mm): ").append(java.util.Arrays.toString(lens))
                  .append("  f/: ").append(java.util.Arrays.toString(aps))
                  .append("  sensor: ").append(sensor).append("  depth output: ").append(depth).append("\n");
            }
        }catch(Exception e){s.append(e.getMessage());}
        new android.app.AlertDialog.Builder(this).setTitle("Fotocamere / Camera2")
            .setMessage(s.toString()).setPositiveButton("OK",null).show();
    }
    @Override protected void onResume() {
        super.onResume();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);return;
        }
        try {
            if(session==null) {
                ArCoreApk.InstallStatus st=ArCoreApk.getInstance().requestInstall(this,!requestedInstall);
                if(st==ArCoreApk.InstallStatus.INSTALL_REQUESTED){requestedInstall=true;return;}
                session=new Session(this);
                Config config=new Config(session);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                if(session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                session.configure(config);render.setSession(session);
            }
            session.resume();
            gl.onResume(); canGlResume=true;
        }catch(Exception e){status("ARCore non disponibile: "+e.getClass().getSimpleName()+" — "+e.getMessage());}
    }
    @Override protected void onPause() {
        super.onPause();
        if(canGlResume){gl.onPause();canGlResume=false;}
        if(session!=null)session.pause();
    }
    @Override protected void onDestroy(){super.onDestroy();if(session!=null){session.close();session=null;}}
    @Override public void onRequestPermissionsResult(int req,String[] perms,int[] results){
        super.onRequestPermissionsResult(req,perms,results);
        if(req==CAMERA_REQUEST&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)onResume();
        else status("Permesso fotocamera richiesto per scansione.");
    }
}
