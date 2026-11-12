package com.frak89.geniescan;

import java.util.*;
import java.util.Locale;

/** Primitive parametriche conservative: non spacciate per riconoscimento IA. */
public final class Geometry {
    private Geometry() {}

    public static final class Cloud {
        private final LinkedHashMap<Long,float[]> cells = new LinkedHashMap<>();
        public int accepted = 0, rejected = 0;
        public synchronized void clear() { cells.clear(); accepted=0; rejected=0; }
        public synchronized int size() { return cells.size(); }
        public synchronized void add(float x,float y,float z,float confidence) {
            if(confidence<0.35f || !Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)) {rejected++;return;}
            if(Math.abs(x)>150||Math.abs(y)>150||Math.abs(z)>150) {rejected++;return;}
            float step = .035f;
            int xi=Math.round(x/step), yi=Math.round(y/step), zi=Math.round(z/step);
            long key= (((long)(xi+1048576)&2097151L)<<42) | (((long)(yi+1048576)&2097151L)<<21) | ((long)(zi+1048576)&2097151L);
            if(cells.containsKey(key)) return;
            if(cells.size()>=45000) {rejected++;return;}
            cells.put(key,new float[]{x,y,z});
            accepted++;
        }
        public synchronized float[] snapshot() {
            float[] out=new float[cells.size()*3]; int i=0;
            for(float[] a:cells.values()) for(int j=0;j<3;j++) out[i++]=a[j];
            return out;
        }
        public synchronized float[] bounds() {
            if(cells.isEmpty()) return new float[]{-.25f,-.25f,-.25f,.25f,.25f,.25f};
            float[] b={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
            for(float[] p:cells.values()) for(int i=0;i<3;i++) {b[i]=Math.min(b[i],p[i]);b[i+3]=Math.max(b[i+3],p[i]);}
            return b;
        }
    }

    public static final class Mesh {
        public final ArrayList<float[]> vertices=new ArrayList<>();
        public final ArrayList<int[]> faces=new ArrayList<>();
        public int quads;
        public int vertex(float x,float y,float z) {vertices.add(new float[]{x,y,z});return vertices.size();}
        public void quad(int a,int b,int c,int d) {faces.add(new int[]{a,b,c});faces.add(new int[]{a,c,d});quads++;}
        public void tri(int a,int b,int c) {faces.add(new int[]{a,b,c});}
        public void box(float cx,float cy,float cz,float sx,float sy,float sz) {
            float x0=cx-sx/2,x1=cx+sx/2,y0=cy-sy/2,y1=cy+sy/2,z0=cz-sz/2,z1=cz+sz/2;
            int a=vertex(x0,y0,z0),b=vertex(x1,y0,z0),c=vertex(x1,y1,z0),d=vertex(x0,y1,z0);
            int e=vertex(x0,y0,z1),f=vertex(x1,y0,z1),g=vertex(x1,y1,z1),h=vertex(x0,y1,z1);
            quad(a,d,c,b);quad(e,f,g,h);quad(a,e,h,d);quad(b,c,g,f);quad(a,b,f,e);quad(d,h,g,c);
        }
        public void cylinder(float cx,float bottom,float cz,float rx,float rz,float h,int sides) {
            int[] lo=new int[sides],up=new int[sides];
            for(int i=0;i<sides;i++) {
                float ang=(float)(2*Math.PI*i/sides);
                lo[i]=vertex(cx+rx*(float)Math.cos(ang),bottom,cz+rz*(float)Math.sin(ang));
                up[i]=vertex(cx+rx*(float)Math.cos(ang),bottom+h,cz+rz*(float)Math.sin(ang));
            }
            int lc=vertex(cx,bottom,cz),uc=vertex(cx,bottom+h,cz);
            for(int i=0;i<sides;i++) {int j=(i+1)%sides;quad(lo[i],lo[j],up[j],up[i]);tri(lc,lo[j],lo[i]);tri(uc,up[i],up[j]);}
        }
        public void lathe(float cx,float cy,float cz,float rx,float rz,float h,int sides) {
            float[] heights={0,.13f,.34f,.48f,.56f,.68f,.86f,1};
            float[] radii={.80f,1,.85f,.20f,.19f,.85f,1,.70f};
            int[][] rings=new int[heights.length][sides];
            for(int j=0;j<heights.length;j++) for(int i=0;i<sides;i++) {
                double a=2*Math.PI*i/sides;
                rings[j][i]=vertex(cx+rx*radii[j]*(float)Math.cos(a),cy+h*heights[j],cz+rz*radii[j]*(float)Math.sin(a));
            }
            for(int j=0;j<heights.length-1;j++) for(int i=0;i<sides;i++) {
                int k=(i+1)%sides;quad(rings[j][i],rings[j][k],rings[j+1][k],rings[j+1][i]);
            }
            int bottom=vertex(cx,cy,cz),top=vertex(cx,cy+h,cz);
            for(int i=0;i<sides;i++) {int k=(i+1)%sides;tri(bottom,rings[0][k],rings[0][i]);tri(top,rings[heights.length-1][i],rings[heights.length-1][k]);}
        }
        public float[] triangles() {
            float[] out=new float[faces.size()*9];int k=0;
            for(int[] f:faces) for(int v:f) for(float x:vertices.get(v-1)) out[k++]=x;
            return out;
        }
        public float[] edges() {
            HashSet<Long> seen=new HashSet<>();ArrayList<Float> ar=new ArrayList<>();
            for(int[] f:faces) for(int i=0;i<3;i++){
                int a=f[i],b=f[(i+1)%3];long key=((long)Math.min(a,b)<<32)|Math.max(a,b);
                if(!seen.add(key))continue;
                for(float x:vertices.get(a-1))ar.add(x);
                for(float x:vertices.get(b-1))ar.add(x);
            }
            float[] out=new float[ar.size()];for(int i=0;i<out.length;i++)out[i]=ar.get(i);return out;
        }
        public String obj() {
            StringBuilder s=new StringBuilder("# GenieScan Smart Primitive - approximate geometry\n");
            for(float[] v:vertices) s.append(String.format(Locale.US,"v %.6f %.6f %.6f\n",v[0],v[1],v[2]));
            for(int[] f:faces) s.append("f ").append(f[0]).append(" ").append(f[1]).append(" ").append(f[2]).append("\n");
            return s.toString();
        }
    }
    public static Mesh fit(String shape,int level,float[] bb) {
        float x=(bb[0]+bb[3])/2,y=(bb[1]+bb[4])/2,z=(bb[2]+bb[5])/2;
        float sx=Math.max(.08f,bb[3]-bb[0]),sy=Math.max(.08f,bb[4]-bb[1]),sz=Math.max(.08f,bb[5]-bb[2]);
        int n=level==0?6:level==1?12:32;Mesh m=new Mesh();
        switch(shape) {
            case "TAVOLO":
                float th=Math.max(.04f,sy*.1f),r=Math.max(.025f,Math.min(sx,sz)*.09f);
                m.box(x,bb[4]-th/2,z,sx,th,sz);
                for(int xi=-1;xi<=1;xi+=2)for(int zi=-1;zi<=1;zi+=2)
                    m.box(x+xi*(sx/2-r),bb[1]+(sy-th)/2,z+zi*(sz/2-r),r*1.3f,sy-th,r*1.3f);
                break;
            case "CILINDRO":m.cylinder(x,bb[1],z,sx/2,sz/2,sy,n);break;
            case "VASO":m.lathe(x,bb[1],z,sx/2,sz/2,sy,n);break;
            default:m.box(x,y,z,sx,sy,sz);
        }
        return m;
    }
}
