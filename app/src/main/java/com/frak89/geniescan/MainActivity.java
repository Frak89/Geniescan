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
import android.os.Build;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.text.TextUtils;
import android.view.WindowInsets;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
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
    private GridLayout actionGrid;
    private boolean requestedInstall=false;
    private boolean studio=false, points=true, wire=false, solid=true, cull=false, scanning=true;
    private int level=0, shape=0;
    private final String[] shapes={"BOX","CILINDRO","VASO","TAVOLO"};
    private final String[] levels={"LOW","MID","HIGH"};
    private int cyan=Color.rgb(26,195,196), violet=Color.rgb(160,76,234);
    private float lastX,lastY;
    private boolean canGlResume=false;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(12,18,23));
        getWindow().setNavigationBarColor(Color.rgb(12,18,23));

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12,18,23));

        // Android 15+ enforces edge-to-edge for apps targeting modern SDKs.
        // Explicit system bar/cutout padding avoids hiding the title and buttons.
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){
                Insets safe=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;
            } else {
                left=insets.getSystemWindowInsetLeft();
                top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight();
                bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left,top,right,bottom);
            return insets;
        });

        TextView title=new TextView(this);
        title.setText("GENIESCAN  •  CLOUD / SMART POLY");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setTypeface(null,1);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(9),dp(4),dp(9),dp(2));
        root.addView(title,new LinearLayout.LayoutParams(-1,dp(25)));

        info=new TextView(this);
        info.setTextColor(0xffb0ced8);
        info.setTextSize(10.5f);
        info.setMinLines(1);
        info.setMaxLines(2);
        info.setEllipsize(TextUtils.TruncateAt.END);
        info.setPadding(dp(9),dp(1),dp(9),dp(3));
        info.setText("Avvio ARCore • Muovi lentamente la fotocamera");
        info.setOnClickListener(v->new android.app.AlertDialog.Builder(this)
            .setTitle("Dettagli scansione")
            .setMessage(info.getText())
            .setPositiveButton("OK",null).show());
        root.addView(info,new LinearLayout.LayoutParams(-1,-2));

        gl=new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setPreserveEGLContextOnPause(true);
        render=new ScanRenderer(this);
        gl.setRenderer(render);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(gl,new LinearLayout.LayoutParams(-1,0,1));
        gl.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                lastX=e.getX();lastY=e.getY();return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE && studio){
                float dx=e.getX()-lastX,dy=e.getY()-lastY;
                gl.queueEvent(()->render.orbit(dx,dy));
                lastX=e.getX();lastY=e.getY();return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP && !studio){
                float x=e.getX(),y=e.getY();
                gl.queueEvent(()->render.focusAt(x,y));return true;
            }
            return true;
        });

        int columns=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?6:4;
        actionGrid=new GridLayout(this);
        actionGrid.setColumnCount(columns);
        actionGrid.setOrientation(GridLayout.HORIZONTAL);
        actionGrid.setUseDefaultMargins(false);
        actionGrid.setPadding(dp(3),dp(2),dp(3),dp(4));
        root.addView(actionGrid,new LinearLayout.LayoutParams(-1,-2));

        add("SCAN",true,()->{scanning=!scanning;render.scanning=scanning;refresh();});
        add("3D",false,()->{studio=!studio;render.studio=studio;refresh();});
        add("PUNTI",true,()->{points=!points;render.points=points;refresh();});
        add("SOLIDO",true,()->{solid=!solid;render.solid=solid;refresh();});
        add("WIRE",false,()->{wire=!wire;render.wire=wire;refresh();});
        add("CULL",false,()->{cull=!cull;render.cull=cull;refresh();});
        add("FOCUS",false,()->{render.requestFocus();toast("Inquadra e tocca l'oggetto");});
        add("FORMA\n"+shapes[shape],false,()->{shape=(shape+1)%shapes.length;rebuild();refresh();});
        add("POLY\n"+levels[level],false,()->{level=(level+1)%3;rebuild();refresh();});
        add("RICOST.",false,this::rebuild);
        add("FOTO",false,()->{render.requestPhoto();toast("Scatto JPEG con filtro nitidezza");});
        add("AUTO FOTO",false,()->{render.autoPhotos=!render.autoPhotos;refresh();});
        add("VIOLA",false,()->{
            render.trimPreview=!render.trimPreview;
            toast(render.trimPreview?"Punti in viola: area selezionata":"Selezione disattivata");
            refresh();
        });
        add("ELIMINA",false,()->{
            gl.queueEvent(()->{
                int count=render.trimFocused();
                runOnUiThread(()->toast("Punti eliminati: "+count));
            });
            refresh();
        });
        add("OBJ",false,this::export);
        add("IA LOCALE",false,this::chooseModel);
        add("CAMERE",false,this::showCameras);
        add("PULISCI",false,()->{
            gl.queueEvent(()->render.clear());
            toast("Nuvola ripulita");
        });

        setContentView(root);
        root.requestApplyInsets();
        refresh();
    }

    private void add(String label,boolean selected,Runnable run) {
        Button b=new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(9.5f);
        b.setAllCaps(false);
        b.setMaxLines(2);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0,0,0,0);
        b.setMinHeight(0);b.setMinimumHeight(0);
        b.setMinWidth(0);b.setMinimumWidth(0);
        b.setTag(label.startsWith("FORMA")?"FORMA":label.startsWith("POLY")?"POLY":label);
        b.setBackgroundTintList(ColorStateList.valueOf(selected?cyan:0xff33444c));
        GridLayout.LayoutParams lp=new GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),GridLayout.spec(GridLayout.UNDEFINED,1f));
        lp.width=0;
        lp.height=dp(39);
        lp.setMargins(dp(1),dp(1),dp(1),dp(1));
        actionGrid.addView(b,lp);
        b.setOnClickListener(v->run.run());
    }

    private void refresh() {
        if(actionGrid==null)return;
        for(int i=0;i<actionGrid.getChildCount();i++){
            Button b=(Button)actionGrid.getChildAt(i);
            String key=(String)b.getTag();
            if("FORMA".equals(key)) b.setText("FORMA\n"+shapes[shape]);
            if("POLY".equals(key)) b.setText("POLY\n"+levels[level]);
            boolean active=("SCAN".equals(key)&&scanning)||
                ("3D".equals(key)&&studio)||
                ("PUNTI".equals(key)&&points)||
                ("SOLIDO".equals(key)&&solid)||
                ("WIRE".equals(key)&&wire)||
                ("CULL".equals(key)&&cull)||
                ("AUTO FOTO".equals(key)&&render.autoPhotos)||
                ("VIOLA".equals(key)&&render.trimPreview);
            b.setBackgroundTintList(ColorStateList.valueOf(active?cyan:0xff33444c));
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
