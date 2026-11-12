package com.frak89.geniescan;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.util.Locale;

/** Validates a pre-downloaded model file via Storage Access Framework.
 *  No AI inference is claimed or performed by this class.
 */
public final class ModelFile {
    public static final class Descriptor {
        public final Uri uri;
        public final String name, format, note;
        public final long bytes;
        private Descriptor(Uri uri,String name,String format,long bytes,String note) {
            this.uri=uri;this.name=name;this.format=format;this.bytes=bytes;this.note=note;
        }
        public String summary() {
            return name+" · "+format+(bytes>=0?" · "+String.format(Locale.ROOT,"%.1f MB",bytes/1e6):"")+
                "\n"+note;
        }
    }
    private ModelFile(){}

    public static Descriptor describe(Context context,Uri uri) {
        String name="Modello locale";long bytes=-1;
        try(Cursor c=context.getContentResolver().query(uri,null,null,null,null)) {
            if(c!=null&&c.moveToFirst()){
                int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),b=c.getColumnIndex(OpenableColumns.SIZE);
                if(n>=0&&!c.isNull(n))name=c.getString(n);
                if(b>=0&&!c.isNull(b))bytes=c.getLong(b);
            }
        }catch(Exception ignored){}
        String lower=name.toLowerCase(Locale.ROOT);
        String format,note;
        if(lower.endsWith(".tflite")||lower.endsWith(".lite")){
            format="TFLite";note="Serve un interprete TFLite e una specifica di input/output; il formato non garantisce supporto immagini.";
        }else if(lower.endsWith(".onnx")){
            format="ONNX";note="Serve ONNX Runtime e pre/postprocessing associato al modello.";
        }else if(lower.endsWith(".gguf")){
            format="GGUF";note="Serve un runtime llama.cpp compatibile; la visione richiede modello multimodale e componenti visivi.";
        }else if(lower.endsWith(".safetensors")){
            format="Safetensors";note="Pesi del modello, senza architettura e tokenizer non utilizzabili direttamente.";
        }else{
            format="Formato da identificare";note="Verificare estensione e architettura del modello prima di eseguire inferenza.";
        }
        return new Descriptor(uri,name,format,bytes,note);
    }

    public static Descriptor choose(Context context,Uri uri,Intent returned) {
        int flags=returned.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if(flags==0)flags=Intent.FLAG_GRANT_READ_URI_PERMISSION;
        context.getContentResolver().takePersistableUriPermission(uri,flags);
        Descriptor d=describe(context,uri);
        context.getSharedPreferences("geniescan_model",Context.MODE_PRIVATE)
            .edit().putString("uri",uri.toString()).putString("name",d.name).apply();
        return d;
    }

    public static Descriptor selected(Context context) {
        String uri=context.getSharedPreferences("geniescan_model",Context.MODE_PRIVATE)
            .getString("uri",null);
        if(uri==null)return null;
        return describe(context,Uri.parse(uri));
    }
}
